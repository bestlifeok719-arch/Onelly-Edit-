package app.onelly.edit;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Range;

import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.BitmapOverlay;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.Presentation;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.google.common.collect.ImmutableList;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.view.Surface;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Onelly Edit - native fast export.
 * Hardware decode -> GPU (OpenGL) compositing of text/sticker/image overlays -> hardware encode,
 * with no GPU->CPU read-back per frame (this is what CapCut / KineMaster do).
 */
@UnstableApi
@CapacitorPlugin(name = "FastRender")
public class FastRenderPlugin extends Plugin {

    private Transformer transformer;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** Copy a chunk of a source file (base64) into the app cache. */
    @PluginMethod
    public void write(PluginCall call) {
        try {
            String path = call.getString("path");
            byte[] d = Base64.decode(call.getString("data"), Base64.DEFAULT);
            boolean app = Boolean.TRUE.equals(call.getBoolean("append", false));
            File f = new File(getContext().getCacheDir(), path);
            long pos = call.getData().optLong("pos", -1);
            if (pos >= 0) { // v16: write at an exact position (streamed audio file, no big buffer on the web side)
                try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "rw")) {
                    raf.seek(pos);
                    raf.write(d);
                }
            } else {
                try (FileOutputStream o = new FileOutputStream(f, app)) {
                    o.write(d);
                }
            }
            call.resolve();
        } catch (Exception e) {
            call.reject(e.toString());
        }
    }

    @PluginMethod
    public void remove(PluginCall call) {
        try {
            String path = call.getString("path");
            if (path != null) new File(getContext().getCacheDir(), path).delete();
            call.resolve();
        } catch (Exception e) {
            call.reject(e.toString());
        }
    }

    /** Size of a file already in the app cache (lets the web side reuse a source it copied earlier). */
    @PluginMethod
    public void stat(PluginCall call) {
        try {
            String path = call.getString("path");
            String abs = call.getString("abs");
            File f = abs != null ? new File(abs) : new File(getContext().getCacheDir(), path == null ? "" : path);
            JSObject o = new JSObject();
            o.put("exists", f.isFile());
            o.put("size", f.isFile() ? f.length() : 0);
            call.resolve(o);
        } catch (Exception e) {
            call.reject(e.toString());
        }
    }

    // ---- state of the export that is running now (so Cancel can really stop it) ----
    private PluginCall renderCall;
    private List<OverlayVideo> renderVos;
    private File renderOut;
    private static final Object PAUSE_LOCK = new Object();
    private static volatile boolean paused = false;

    /** Overlay callbacks run once per frame on the GPU thread; blocking here pauses the whole export. */
    private static final java.util.concurrent.atomic.AtomicInteger pauseWaiters = new java.util.concurrent.atomic.AtomicInteger();

    static void waitIfPaused() {
        if (!paused) return;
        pauseWaiters.incrementAndGet();
        try {
            synchronized (PAUSE_LOCK) {
                while (paused) {
                    try {
                        PAUSE_LOCK.wait(200);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        } finally {
            pauseWaiters.decrementAndGet();
        }
    }

    /** How many render threads are really waiting because of Pause (proof that Pause holds the work). */
    @PluginMethod
    public void pauseState(PluginCall call) {
        JSObject o = new JSObject();
        o.put("waiting", pauseWaiters.get());
        o.put("paused", paused);
        call.resolve(o);
    }

    private static void setPausedFlag(boolean p) {
        synchronized (PAUSE_LOCK) {
            paused = p;
            TurboRender.paused = p;
            PAUSE_LOCK.notifyAll();
        }
    }

    @PluginMethod
    public void setPaused(PluginCall call) {
        setPausedFlag(Boolean.TRUE.equals(call.getBoolean("paused", false)));
        call.resolve();
    }

    /** What this APK can do (the web side adapts to older APKs). */
    @PluginMethod
    public void caps(PluginCall call) {
        JSObject o = new JSObject();
        o.put("pause", true);
        o.put("upload", true);
        o.put("diag", true);
        o.put("par", true);
        o.put("mux", true);
        o.put("turbo", true);
        o.put("v", 16);
        call.resolve(o);
    }

    /** v16: joins a video-only file (native GPU render) with a ready AAC audio file (mixed on the web side). No re-encoding, only copying samples. */
    @PluginMethod
    public void muxAudio(final PluginCall call) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                MediaExtractor ve = null, ae = null;
                MediaMuxer mm = null;
                boolean started = false;
                File cache = getContext().getCacheDir();
                File vf = new File(cache, call.getString("video", ""));
                File af = new File(cache, call.getString("audio", ""));
                File of = new File(cache, call.getString("out", "final.mp4"));
                try {
                    if (of.exists()) of.delete();
                    ve = new MediaExtractor();
                    ve.setDataSource(vf.getAbsolutePath());
                    ae = new MediaExtractor();
                    ae.setDataSource(af.getAbsolutePath());
                    int vt = -1, at = -1;
                    for (int i = 0; i < ve.getTrackCount(); i++) {
                        String m = ve.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                        if (m != null && m.startsWith("video/") && vt < 0) vt = i;
                    }
                    for (int i = 0; i < ae.getTrackCount(); i++) {
                        String m = ae.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                        if (m != null && m.startsWith("audio/") && at < 0) at = i;
                    }
                    if (vt < 0 || at < 0) throw new Exception("mux: missing " + (vt < 0 ? "video" : "audio") + " track");
                    MediaFormat vfm = ve.getTrackFormat(vt);
                    mm = new MediaMuxer(of.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                    if (vfm.containsKey(MediaFormat.KEY_ROTATION)) {
                        try {
                            mm.setOrientationHint(vfm.getInteger(MediaFormat.KEY_ROTATION));
                        } catch (Throwable ignore) {
                        }
                    }
                    int vi = mm.addTrack(vfm);
                    int ai = mm.addTrack(ae.getTrackFormat(at));
                    mm.start();
                    started = true;
                    ve.selectTrack(vt);
                    ae.selectTrack(at);
                    ByteBuffer buf = ByteBuffer.allocateDirect(8 << 20);
                    MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
                    long vtm = ve.getSampleTime(), atm = ae.getSampleTime();
                    while (vtm >= 0 || atm >= 0) {
                        boolean pickV = vtm >= 0 && (atm < 0 || vtm <= atm);
                        MediaExtractor ex = pickV ? ve : ae;
                        buf.clear();
                        int n = ex.readSampleData(buf, 0);
                        if (n < 0) {
                            if (pickV) vtm = -1;
                            else atm = -1;
                            continue;
                        }
                        int fl = (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                        bi.set(0, n, ex.getSampleTime(), fl);
                        mm.writeSampleData(pickV ? vi : ai, buf, bi);
                        ex.advance();
                        if (pickV) vtm = ve.getSampleTime();
                        else atm = ae.getSampleTime();
                    }
                    mm.stop();
                    started = false;
                    mm.release();
                    mm = null;
                    try {
                        vf.delete();
                        af.delete();
                    } catch (Throwable ignore) {
                    }
                    JSObject o = new JSObject();
                    o.put("path", of.getName());
                    o.put("sizeMB", Math.round(of.length() / 1048.576) / 1000.0);
                    call.resolve(o);
                } catch (Throwable e) {
                    try {
                        if (mm != null) {
                            if (started) mm.stop();
                            mm.release();
                        }
                    } catch (Throwable ignore) {
                    }
                    call.reject("mux: " + e);
                } finally {
                    try {
                        if (ve != null) ve.release();
                        if (ae != null) ae.release();
                    } catch (Throwable ignore) {
                    }
                }
            }
        }, "onelly-mux").start();
    }

    /** v16: what the phone's video chip can do for Turbo (size supported, speed, H.265). Used by the Turbo settings screen. */
    @PluginMethod
    public void turboInfo(PluginCall call) {
        try {
            int w = call.getInt("width", 1280), h = call.getInt("height", 720);
            JSObject o = new JSObject();
            String[] mimes = {"video/avc", "video/hevc"};
            for (int z = 0; z < 2; z++) {
                String key = z == 0 ? "avc" : "hevc";
                boolean has = false, hw = false, sizeOk = false;
                int maxFps = 0;
                String name = "";
                android.media.MediaCodecInfo[] list = new android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).getCodecInfos();
                for (android.media.MediaCodecInfo ci : list) {
                    if (!ci.isEncoder()) continue;
                    boolean sup = false;
                    for (String t : ci.getSupportedTypes()) if (t.equalsIgnoreCase(mimes[z])) sup = true;
                    if (!sup) continue;
                    boolean isHw;
                    if (android.os.Build.VERSION.SDK_INT >= 29) isHw = ci.isHardwareAccelerated();
                    else isHw = !(ci.getName().startsWith("OMX.google.") || ci.getName().startsWith("c2.android."));
                    if (!isHw && has) continue;
                    try {
                        android.media.MediaCodecInfo.VideoCapabilities vc = ci.getCapabilitiesForType(mimes[z]).getVideoCapabilities();
                        boolean so = vc.isSizeSupported(w, h);
                        int mf = 0;
                        if (so) {
                            try {
                                mf = vc.getSupportedFrameRatesFor(w, h).getUpper().intValue();
                            } catch (Throwable ignore) {
                            }
                        }
                        if (!has || (isHw && !hw) || (isHw == hw && mf > maxFps)) {
                            has = true;
                            hw = isHw;
                            sizeOk = so;
                            maxFps = mf;
                            name = ci.getName();
                        }
                    } catch (Throwable ignore) {
                    }
                }
                JSObject e = new JSObject();
                e.put("has", has);
                e.put("hw", hw);
                e.put("sizeOk", sizeOk);
                e.put("maxFps", maxFps);
                e.put("name", name);
                o.put(key, e);
            }
            call.resolve(o);
        } catch (Throwable e) {
            call.reject("turboInfo: " + e);
        }
    }

    /** v16: Turbo export = own engine (TurboRender): hardware decoder -> OpenGL -> hardware encoder. Independent of Media3, Fast Export and Standard Export. */
    @PluginMethod
    public void turbo(final PluginCall call) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    TurboRender tr = new TurboRender(getContext().getCacheDir(), new TurboRender.Progress() {
                        @Override
                        public void on(double percent, double fps) {
                            JSObject o = new JSObject();
                            o.put("p", percent);
                            o.put("fps", fps);
                            notifyListeners("progress", o);
                        }
                    });
                    org.json.JSONObject r = tr.run(call.getData());
                    call.resolve(new JSObject(r.toString()));
                } catch (Throwable e) {
                    call.reject("turbo: " + e);
                }
            }
        }, "onelly-turbo").start();
    }

    @PluginMethod
    public void cancel(PluginCall call) {
        TurboRender.cancel = true;
        setPausedFlag(false);
        main.post(() -> {
            if (transformer != null) {
                try {
                    transformer.cancel();
                } catch (Throwable ignore) {
                }
            }
            parGen++;
            parCancel = true;
            parStopAll();
            PluginCall rc = renderCall;
            renderCall = null;
            if (renderVos != null) {
                for (OverlayVideo v : renderVos) {
                    try {
                        v.closeDecoder();
                    } catch (Throwable ignore) {
                    }
                }
            }
            if (renderOut != null) renderOut.delete();
            // Transformer.cancel() does not call the listener, so the waiting JS call is ended here
            if (rc != null) rc.reject("cancelled");
        });
        call.resolve();
    }

    // ---- fast copy of big source videos: a tiny server on 127.0.0.1 (only with the random token) receives the file as raw bytes, no base64 ----
    private ServerSocket upSrv;
    private int upPort;
    private String upToken;

    private synchronized void ensureServer() throws Exception {
        if (upSrv != null && !upSrv.isClosed()) return;
        upSrv = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        upPort = upSrv.getLocalPort();
        byte[] r = new byte[16];
        new SecureRandom().nextBytes(r);
        StringBuilder sb = new StringBuilder();
        for (byte b : r) sb.append(String.format("%02x", b));
        upToken = sb.toString();
        final ServerSocket srv = upSrv;
        Thread t = new Thread(() -> {
            while (!srv.isClosed()) {
                try {
                    final Socket s = srv.accept();
                    Thread h = new Thread(() -> handleUpload(s));
                    h.setDaemon(true);
                    h.start();
                } catch (Exception e) {
                    break;
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    @PluginMethod
    public void upInfo(PluginCall call) {
        try {
            ensureServer();
            JSObject o = new JSObject();
            o.put("port", upPort);
            o.put("token", upToken);
            call.resolve(o);
        } catch (Exception e) {
            call.reject(e.toString());
        }
    }

    private static String readLine(InputStream in) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) throw new java.io.IOException("header too long");
        }
        if (c == -1 && sb.length() == 0) return null;
        return sb.toString();
    }

    private void reply(OutputStream out, int code, String text) {
        try {
            String body = code == 200 ? "ok" : text;
            String head = "HTTP/1.1 " + code + " " + (code == 200 ? "OK" : code == 204 ? "No Content" : "Error") + "\r\n"
                    + "Access-Control-Allow-Origin: *\r\n"
                    + "Access-Control-Allow-Methods: POST, OPTIONS\r\n"
                    + "Access-Control-Allow-Headers: *\r\n"
                    + "Access-Control-Allow-Private-Network: true\r\n"
                    + "Content-Length: " + (code == 204 ? 0 : body.length()) + "\r\n"
                    + "Connection: close\r\n\r\n" + (code == 204 ? "" : body);
            out.write(head.getBytes("UTF-8"));
            out.flush();
        } catch (Exception ignore) {
        }
    }

    private void handleUpload(Socket s) {
        try {
            s.setSoTimeout(60000);
            InputStream in = new java.io.BufferedInputStream(s.getInputStream(), 1 << 16);
            OutputStream out = s.getOutputStream();
            String req = readLine(in);
            if (req == null) return;
            String[] rp = req.split(" ");
            long len = -1;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:")) len = Long.parseLong(line.substring(15).trim());
            }
            if (rp.length < 2) return;
            if ("OPTIONS".equals(rp[0])) {
                reply(out, 204, "");
                return;
            }
            String name = null, tok = null;
            long wpos = -1; // v16: write this body at an exact position of the file (export is streamed into the app while it is made)
            int q = rp[1].indexOf('?');
            if (q >= 0) {
                for (String kv : rp[1].substring(q + 1).split("&")) {
                    int e = kv.indexOf('=');
                    if (e < 0) continue;
                    String k = kv.substring(0, e), v = java.net.URLDecoder.decode(kv.substring(e + 1), "UTF-8");
                    if ("name".equals(k)) name = v;
                    else if ("t".equals(k)) tok = v;
                    else if ("pos".equals(k)) wpos = Long.parseLong(v);
                }
            }
            if (tok == null || !tok.equals(upToken) || name == null || !name.matches("[A-Za-z0-9._-]{1,64}") || len < 0) {
                reply(out, 403, "no");
                return;
            }
            File dst = new File(getContext().getCacheDir(), name);
            if (wpos >= 0) {
                try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(dst, "rw")) {
                    raf.seek(wpos);
                    byte[] buf = new byte[1 << 18];
                    long left = len;
                    while (left > 0) {
                        int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                        if (n < 0) throw new java.io.IOException("short body");
                        raf.write(buf, 0, n);
                        left -= n;
                    }
                } catch (Exception e) {
                    reply(out, 500, "fail");
                    return;
                }
                reply(out, 200, "");
                return;
            }
            File tmp = new File(getContext().getCacheDir(), name + ".part");
            try (FileOutputStream fo = new FileOutputStream(tmp)) {
                byte[] buf = new byte[1 << 18];
                long left = len;
                while (left > 0) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n < 0) throw new java.io.IOException("short body");
                    fo.write(buf, 0, n);
                    left -= n;
                }
            } catch (Exception e) {
                tmp.delete();
                reply(out, 500, "fail");
                return;
            }
            if (dst.exists()) dst.delete();
            if (!tmp.renameTo(dst)) {
                tmp.delete();
                reply(out, 500, "rename");
                return;
            }
            reply(out, 200, "");
        } catch (Exception ignore) {
        } finally {
            try {
                s.close();
            } catch (Exception ignore) {
            }
        }
    }

    // ---- what limits the speed: source format + which decoder / encoder this phone offers ----
    private static boolean isHw(MediaCodecInfo ci) {
        if (Build.VERSION.SDK_INT >= 29) return ci.isHardwareAccelerated();
        String n = ci.getName().toLowerCase();
        return !(n.startsWith("omx.google") || n.startsWith("c2.android"));
    }

    @PluginMethod
    public void diag(PluginCall call) {
        try {
            File f = new File(getContext().getCacheDir(), call.getString("path", ""));
            int w = call.getInt("width", 1280), h = call.getInt("height", 720);
            String mime = "video/avc";
            int sw = 0, sh = 0;
            float fps = 0;
            try {
                MediaExtractor ex = new MediaExtractor();
                ex.setDataSource(f.getAbsolutePath());
                for (int i = 0; i < ex.getTrackCount(); i++) {
                    MediaFormat mf = ex.getTrackFormat(i);
                    String m = mf.getString(MediaFormat.KEY_MIME);
                    if (m != null && m.startsWith("video/")) {
                        mime = m;
                        if (mf.containsKey(MediaFormat.KEY_WIDTH)) sw = mf.getInteger(MediaFormat.KEY_WIDTH);
                        if (mf.containsKey(MediaFormat.KEY_HEIGHT)) sh = mf.getInteger(MediaFormat.KEY_HEIGHT);
                        if (mf.containsKey(MediaFormat.KEY_FRAME_RATE)) fps = mf.getInteger(MediaFormat.KEY_FRAME_RATE);
                        break;
                    }
                }
                ex.release();
            } catch (Throwable ignore) {
            }
            StringBuilder dec = new StringBuilder(), enc = new StringBuilder();
            int nd = 0, ne = 0;
            for (MediaCodecInfo ci : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                for (String ty : ci.getSupportedTypes()) {
                    if (!ci.isEncoder() && ty.equalsIgnoreCase(mime) && nd < 2) {
                        dec.append(nd++ > 0 ? ", " : "").append(ci.getName()).append(isHw(ci) ? " HW" : " SW");
                    } else if (ci.isEncoder() && ty.equalsIgnoreCase("video/avc") && ne < 3) {
                        String mx = "";
                        try {
                            Range<Double> r = ci.getCapabilitiesForType(ty).getVideoCapabilities().getSupportedFrameRatesFor(w, h);
                            mx = " max " + Math.round(r.getUpper()) + "fps";
                        } catch (Throwable ignore) {
                        }
                        enc.append(ne++ > 0 ? ", " : "").append(ci.getName()).append(isHw(ci) ? " HW" : " SW").append(mx);
                    }
                }
            }
            long freeMb = getContext().getCacheDir().getUsableSpace() / 1048576L;
            String txt = "free " + (freeMb >= 1024 ? Math.round(freeMb / 102.4) / 10.0 + " GB" : freeMb + " MB") + " · src " + sw + "x" + sh + " " + mime.replace("video/", "") + (fps > 0 ? " " + Math.round(fps) + "fps" : "")
                    + " · dec " + (dec.length() > 0 ? dec : "?") + " · enc " + (enc.length() > 0 ? enc : "?");
            JSObject o = new JSObject();
            o.put("txt", txt);
            call.resolve(o);
        } catch (Throwable e) {
            call.reject(e.toString());
        }
    }

    /** Overlay whose bitmap changes with time (pre-rendered by the web side). */
    private static class TimedOverlay extends BitmapOverlay {
        final long[] from, to;
        final String[] paths;
        final Bitmap blank = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        Bitmap cur;
        int curIdx = -2;

        final long offMs;
        long absStartMs = -1, absInMs = 0, firstUs = Long.MIN_VALUE;
        boolean absMode = false;
        long calls = 0, hits = 0;
        int[] bx, by, bw, bh;
        int fw = 0, fh = 0;
        Bitmap fullA, fullB;
        boolean useA = true;

        /** Slices that carry a box (x, y, w, h) are small cropped pictures; they are drawn onto a transparent frame-size bitmap. */
        void setBoxes(int[] x, int[] y, int[] w, int[] h, int frameW, int frameH) {
            this.bx = x;
            this.by = y;
            this.bw = w;
            this.bh = h;
            this.fw = frameW;
            this.fh = frameH;
        }

        TimedOverlay(long[] from, long[] to, String[] paths) {
            this(from, to, paths, 0L);
        }

        /** offMs: where this piece starts on the whole timeline (used when the video is exported in parallel pieces). */
        String diag() {
            return calls + "/" + hits + (absMode ? "A" : "") + "@" + (firstUs == Long.MIN_VALUE ? -1 : firstUs / 1000);
        }

        TimedOverlay(long[] from, long[] to, String[] paths, long offMs) {
            this.from = from;
            this.to = to;
            this.paths = paths;
            this.offMs = offMs;
        }

        @Override
        public Bitmap getBitmap(long presentationTimeUs) throws VideoFrameProcessingException {
            waitIfPaused();
            if (firstUs == Long.MIN_VALUE) {
                firstUs = presentationTimeUs;
                // v16: if this Media3 build hands us ABSOLUTE source time (piece start ~ bounds[i]) instead of time-from-piece-start, detect it
                if (absStartMs > 2000 && Math.abs(presentationTimeUs / 1000 - absStartMs) < 1500) absMode = true;
            }
            long ms = absMode ? presentationTimeUs / 1000 - absInMs : presentationTimeUs / 1000 + offMs;
            calls++;
            int lo = 0, hi = from.length - 1, idx = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (from[mid] <= ms) {
                    idx = mid;
                    lo = mid + 1;
                } else hi = mid - 1;
            }
            if (idx >= 0 && ms >= to[idx]) idx = -1;
            if (idx >= 0) hits++;
            if (idx != curIdx) {
                curIdx = idx;
                Bitmap b = idx < 0 ? blank : BitmapFactory.decodeFile(paths[idx]);
                if (b != null && idx >= 0 && bw != null && bw[idx] > 0 && fw > 0 && fh > 0) {
                    // alternate two frame-size bitmaps so the GPU sees a different bitmap each time and re-uploads it
                    Bitmap full;
                    if (useA) {
                        if (fullA == null) fullA = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888);
                        full = fullA;
                    } else {
                        if (fullB == null) fullB = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888);
                        full = fullB;
                    }
                    useA = !useA;
                    full.eraseColor(Color.TRANSPARENT);
                    new Canvas(full).drawBitmap(b, bx[idx], by[idx], null);
                    b.recycle();
                    cur = full;
                } else {
                    cur = b != null ? b : blank;
                }
            }
            return cur;
        }
    }

    // ---- PARALLEL EXPORT: one long video is cut into pieces, every piece is rendered at the same time (GPU encoder is used fully),
    //      then the pieces are joined WITHOUT re-encoding. Any problem -> automatic fallback to the normal one-piece export. ----
    private Transformer[] parTrs;
    private File[] parFiles;
    private volatile boolean parCancel = false;
    private int parGen = 0;
    private String parNote = "";
    private String lastOvDiag = "";

    private void parStopAll() {
        if (renderVos != null) {
            for (OverlayVideo v : renderVos) {
                try {
                    v.closeDecoder();
                } catch (Throwable ignore) {
                }
            }
        }
        if (parTrs != null) {
            for (Transformer pt : parTrs) {
                if (pt != null) {
                    try {
                        pt.cancel();
                    } catch (Throwable ignore) {
                    }
                }
            }
        }
        if (parFiles != null) {
            for (File pf : parFiles) if (pf != null) pf.delete();
        }
        parTrs = null;
        parFiles = null;
    }

    @PluginMethod
    public void render(final PluginCall call) {
        main.post(() -> {
            boolean started;
            parNote = "";
            try {
                started = startParallel(call);
            } catch (Throwable e) {
                parGen++;
                parStopAll();
                parFallbackNote("start: " + e);
                started = false;
            }
            if (!started) renderSingle(call);
        });
    }

    /** Tell the web side right away why parallel mode was dropped (the normal one-piece export then starts). */
    private void parFallbackNote(String why) {
        parNote = why;
        try {
            JSObject o = new JSObject();
            o.put("msg", why);
            notifyListeners("note", o);
        } catch (Throwable ignore) {
        }
    }

    private static String encNameOf(ExportResult r) {
        try {
            Object en = ExportResult.class.getField("videoEncoderName").get(r);
            return en != null ? String.valueOf(en) : "";
        } catch (Throwable ignore) {
            return "";
        }
    }

    private boolean startParallel(final PluginCall call) throws Exception {
        final int nSeg = call.getInt("segments", 1);
        if (nSeg < 2) return false;
        JSONArray clips = call.getData().getJSONArray("clips");
        if (clips.length() != 1) return false;
        JSONArray ovs = call.getData().optJSONArray("overlays");
        final JSONObject c = clips.getJSONObject(0);
        final long inMs = c.getLong("inMs"), outMs = c.getLong("outMs"), dur = outMs - inMs;
        if (dur < nSeg * 60000L) return false;

        final File cache = getContext().getCacheDir();
        final int w = call.getInt("width", 1280), h = call.getInt("height", 720);
        final int br = call.getInt("bitrate", 4000000);
        final String out = call.getString("out", "out.mp4");
        final File outFile = new File(cache, out);
        if (outFile.exists()) outFile.delete();
        renderOut = outFile;
        renderCall = call;
        final List<OverlayVideo> vosAll = new ArrayList<>();
        final List<TimedOverlay> tovAll = new ArrayList<>();
        renderVos = vosAll;
        transformer = null;
        parCancel = false;
        final int gen = ++parGen;

        final long[] bounds = new long[nSeg + 1];
        for (int i = 0; i <= nSeg; i++) bounds[i] = inMs + dur * i / nSeg;
        bounds[nSeg] = outMs;

        // overlay pictures (same for every piece; only the time shift differs)
        final List<long[]> fL = new ArrayList<>(), tL = new ArrayList<>();
        final List<String[]> pL = new ArrayList<>();
        final List<int[][]> bL = new ArrayList<>();
        final List<JSONObject> vjL = new ArrayList<>();
        final List<int[]> order = new ArrayList<>(); // {0, index in fL} = pictures, {1, index in vjL} = overlay video (keeps the stacking order)
        if (ovs != null) {
            for (int i = 0; i < ovs.length(); i++) {
                JSONObject vjo = ovs.getJSONObject(i).optJSONObject("video");
                if (vjo != null) {
                    vjL.add(vjo);
                    order.add(new int[]{1, vjL.size() - 1});
                    continue;
                }
                JSONArray sl = ovs.getJSONObject(i).getJSONArray("slices");
                int n = sl.length();
                long[] f = new long[n], tt = new long[n];
                String[] p = new String[n];
                int[][] bb = new int[4][n];
                for (int j = 0; j < n; j++) {
                    JSONObject s = sl.getJSONObject(j);
                    f[j] = s.getLong("fromMs");
                    tt[j] = s.getLong("toMs");
                    p[j] = new File(cache, s.getString("path")).getAbsolutePath();
                    bb[0][j] = s.optInt("x", 0);
                    bb[1][j] = s.optInt("y", 0);
                    bb[2][j] = s.optInt("w", 0);
                    bb[3][j] = s.optInt("h", 0);
                }
                fL.add(f);
                tL.add(tt);
                pL.add(p);
                bL.add(bb);
                order.add(new int[]{0, fL.size() - 1});
            }
        }

        final Transformer[] trs = new Transformer[nSeg];
        final Composition[] comps = new Composition[nSeg];
        final File[] files = new File[nSeg];
        final boolean[] done = new boolean[nSeg];
        final int[] doneCount = {0};
        final String[] encName = {""};
        final boolean[] finished = {false};
        final long tStart = System.currentTimeMillis();
        final long[] pieceMs = new long[nSeg];
        parTrs = trs;
        parFiles = files;

        for (int i = 0; i < nSeg; i++) {
            final int idx = i;
            files[i] = new File(cache, "seg" + i + ".mp4");
            if (files[i].exists()) files[i].delete();

            MediaItem.ClippingConfiguration cc = new MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(bounds[i])
                    .setEndPositionMs(bounds[i + 1])
                    .build();
            MediaItem mi = new MediaItem.Builder()
                    .setUri(Uri.fromFile(new File(cache, c.getString("path"))))
                    .setClippingConfiguration(cc)
                    .build();
            List<EditedMediaItem> items = new ArrayList<>();
            List<MediaItem> miList = new ArrayList<>();
            miList.add(mi);
            items.add(new EditedMediaItem.Builder(mi).setRemoveAudio(Boolean.TRUE.equals(call.getBoolean("mute", false))).build());

            List<Effect> vfx = new ArrayList<>();
            vfx.add(Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT));
            List<androidx.media3.effect.TextureOverlay> ovl = new ArrayList<>();
            for (int[] e : order) {
                if (e[0] == 1) {
                    JSONObject vj = vjL.get(e[1]);
                    OverlayVideo vo = new OverlayVideo(
                            new File(cache, vj.getString("path")).getAbsolutePath(), w, h,
                            vj.getLong("fromMs") * 1000L, vj.getLong("toMs") * 1000L,
                            vj.optLong("durMs", 0L) * 1000L,
                            (float) vj.getDouble("x"), (float) vj.getDouble("y"),
                            (float) vj.getDouble("sc"), (float) vj.getDouble("rot"),
                            vj.optDouble("ar", 0.5625));
                    vo.setInUs(vj.optLong("inMs", 0L) * 1000L);
                    vo.setOffsetUs((bounds[i] - inMs) * 1000L);
                    vosAll.add(vo);
                    ovl.add(vo);
                } else {
                    TimedOverlay to = new TimedOverlay(fL.get(e[1]), tL.get(e[1]), pL.get(e[1]), bounds[i] - inMs);
                    to.absStartMs = bounds[i];
                    to.absInMs = inMs;
                    tovAll.add(to);
                    int[][] bb = bL.get(e[1]);
                    to.setBoxes(bb[0], bb[1], bb[2], bb[3], w, h);
                    ovl.add(to);
                }
            }
            if (!ovl.isEmpty()) vfx.add(new OverlayEffect(ImmutableList.copyOf(ovl)));
            // v16: effects go on the media item itself (composition-level effects never called the overlays: 'calls 0')
            items.set(0, new EditedMediaItem.Builder(miList.get(0))
                    .setRemoveAudio(Boolean.TRUE.equals(call.getBoolean("mute", false)))
                    .setEffects(new Effects(ImmutableList.<AudioProcessor>of(), ImmutableList.copyOf(vfx))).build());
            comps[i] = new Composition.Builder(ImmutableList.of(new EditedMediaItemSequence(ImmutableList.copyOf(items))))
                    .build();

            Transformer.Builder tb = new Transformer.Builder(getContext());
            try {
                Transformer.Builder.class.getMethod("setMaxDelayBetweenMuxerSamplesMs", long.class).invoke(tb, C.TIME_UNSET);
            } catch (Throwable ignore) {
            }
            trs[i] = tb
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEncoderFactory(new DefaultEncoderFactory.Builder(getContext())
                            .setRequestedVideoEncoderSettings(fastEnc(br))
                            .build())
                    .addListener(new Transformer.Listener() {
                        @Override
                        public void onCompleted(Composition composition, ExportResult r) {
                            if (gen != parGen || renderCall != call) return;
                            if (idx == 0) encName[0] = encNameOf(r);
                            done[idx] = true;
                            pieceMs[idx] = System.currentTimeMillis() - tStart;
                            if (++doneCount[0] == nSeg) {
                                finished[0] = true;
                                StringBuilder dg = new StringBuilder();
                                for (TimedOverlay q : tovAll) dg.append(dg.length() > 0 ? " " : "").append(q.diag());
                                lastOvDiag = dg.toString();
                                long ovTot = 0;
                                for (OverlayVideo v : vosAll) {
                                    ovTot += v.spentMs();
                                    v.closeDecoder();
                                }
                                finishParallel(call, gen, bounds, inMs, files, outFile, out, encName[0], nSeg, pieceMs, ovTot);
                            }
                        }

                        @Override
                        public void onError(Composition composition, ExportResult r, ExportException e) {
                            if (gen != parGen || renderCall != call) return;
                            finished[0] = true;
                            parGen++;
                            String why = "piece " + (idx + 1) + ": " + e.getErrorCodeName() + " " + e.getMessage();
                            parStopAll();
                            if (nSeg > 3) { // v16: too many encoder sessions for this phone? retry with 3 pieces before the slow one-piece mode
                                try {
                                    call.getData().put("segments", 3);
                                    if (startParallel(call)) return;
                                } catch (Throwable ignore) {
                                }
                            }
                            parFallbackNote(why);
                            renderSingle(call);
                        }
                    })
                    .build();
        }
        for (int i = 0; i < nSeg; i++) trs[i].start(comps[i], files[i].getAbsolutePath());

        final ProgressHolder ph = new ProgressHolder();
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (finished[0] || gen != parGen) return;
                if (paused) {
                    main.postDelayed(this, 500);
                    return;
                }
                float sum = 0;
                for (int i = 0; i < nSeg; i++) {
                    if (done[i]) sum += 100;
                    else if (trs[i].getProgress(ph) == Transformer.PROGRESS_STATE_AVAILABLE) sum += ph.progress;
                }
                JSObject o = new JSObject();
                o.put("p", Math.min(99.0, sum / nSeg));
                notifyListeners("progress", o);
                main.postDelayed(this, 500);
            }
        }, 500);
        return true;
    }

    private void finishParallel(final PluginCall call, final int gen, final long[] bounds, final long inMs, final File[] files,
                                final File outFile, final String out, final String enc, final int nSeg, final long[] pieceMs, final long ovTot) {
        final long t0 = System.currentTimeMillis();
        Thread th = new Thread(() -> {
            Exception err = null;
            try {
                concatSegments(files, bounds, inMs, outFile);
            } catch (Exception e) {
                err = e;
            }
            final Exception fe = err;
            main.post(() -> {
                if (gen != parGen) {
                    outFile.delete();
                    return;
                }
                if (fe != null) {
                    parGen++;
                    parStopAll();
                    outFile.delete();
                    parFallbackNote(String.valueOf(fe.getMessage()));
                    renderSingle(call);
                    return;
                }
                renderCall = null;
                setPausedFlag(false);
                parTrs = null;
                parFiles = null;
                JSObject o = new JSObject();
                o.put("ovMs", ovTot);
                if (!enc.isEmpty()) o.put("enc", enc);
                o.put("path", out);
                o.put("sizeMB", Math.round(outFile.length() / 1048.576) / 1000.0);
                o.put("par", nSeg);
                o.put("ovDiag", lastOvDiag);
                StringBuilder ps = new StringBuilder();
                for (int q = 0; q < pieceMs.length; q++) ps.append(q > 0 ? "/" : "").append(Math.round(pieceMs[q] / 1000.0));
                o.put("pieces", ps.toString());
                o.put("joinMs", System.currentTimeMillis() - t0);
                call.resolve(o);
            });
        });
        th.setDaemon(true);
        th.start();
    }

    private static byte[] csdOf(MediaFormat f, String key) {
        if (!f.containsKey(key)) return new byte[0];
        ByteBuffer b = f.getByteBuffer(key);
        if (b == null) return new byte[0];
        byte[] a = new byte[b.remaining()];
        b.duplicate().get(a);
        return a;
    }

    /** Joins the finished pieces into one MP4 by copying the already-encoded samples (no re-encode), shifting each piece to its place in time. */
    private void concatSegments(File[] segs, long[] bounds, long inMs, File outFile) throws Exception {
        MediaMuxer mx = null;
        boolean started = false;
        String stage = "open";
        try {
            int vOut = -1, aOut = -1;
            boolean hasAudio = false;
            byte[] c0 = null, c1 = null;
            ByteBuffer buf = ByteBuffer.allocateDirect(8 << 20);
            MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
            long lastA = Long.MIN_VALUE;
            for (int i = 0; i < segs.length; i++) {
                if (parCancel) throw new Exception("cancelled");
                stage = "piece " + (i + 1) + " open";
                MediaExtractor ex = new MediaExtractor();
                try {
                    ex.setDataSource(segs[i].getAbsolutePath());
                    int vIn = -1, aIn = -1;
                    MediaFormat vf = null, af = null;
                    for (int t = 0; t < ex.getTrackCount(); t++) {
                        MediaFormat f = ex.getTrackFormat(t);
                        String m = f.getString(MediaFormat.KEY_MIME);
                        if (m == null) continue;
                        if (m.startsWith("video/") && vIn < 0) {
                            vIn = t;
                            vf = f;
                        } else if (m.startsWith("audio/") && aIn < 0) {
                            aIn = t;
                            af = f;
                        }
                    }
                    if (vIn < 0) throw new Exception("no video track in piece " + (i + 1));
                    if (i == 0) {
                        mx = new MediaMuxer(outFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                        if (Build.VERSION.SDK_INT >= 23 && vf.containsKey(MediaFormat.KEY_ROTATION)) {
                            int rot = vf.getInteger(MediaFormat.KEY_ROTATION);
                            if (rot != 0) mx.setOrientationHint(rot);
                        }
                        vOut = mx.addTrack(vf);
                        if (aIn >= 0) {
                            aOut = mx.addTrack(af);
                            hasAudio = true;
                        }
                        mx.start();
                        started = true;
                        c0 = csdOf(vf, "csd-0");
                        c1 = csdOf(vf, "csd-1");
                    } else {
                        if (!Arrays.equals(c0, csdOf(vf, "csd-0")) || !Arrays.equals(c1, csdOf(vf, "csd-1"))) {
                            throw new Exception("pieces use different video settings");
                        }
                        if ((aIn >= 0) != hasAudio) throw new Exception("audio differs between pieces");
                    }
                    stage = "piece " + (i + 1) + " copy";
                    ex.selectTrack(vIn);
                    if (aIn >= 0) ex.selectTrack(aIn);
                    long offUs = (bounds[i] - inMs) * 1000L;
                    int cnt = 0;
                    while (true) {
                        int tr = ex.getSampleTrackIndex();
                        if (tr < 0) break;
                        buf.clear();
                        int sz = ex.readSampleData(buf, 0);
                        if (sz < 0) break;
                        long ts = ex.getSampleTime() + offUs;
                        int fl = ex.getSampleFlags();
                        boolean isV = tr == vIn;
                        if (!isV) {
                            if (ts <= lastA) {
                                ex.advance();
                                continue;
                            }
                            lastA = ts;
                        }
                        bi.set(0, sz, ts, (fl & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                        mx.writeSampleData(isV ? vOut : aOut, buf, bi);
                        ex.advance();
                        if ((++cnt & 1023) == 0 && parCancel) throw new Exception("cancelled");
                    }
                } finally {
                    try {
                        ex.release();
                    } catch (Throwable ignore) {
                    }
                }
                segs[i].delete(); // free the disk space as soon as a piece is joined
            }
            stage = "finish";
            mx.stop();
            started = false;
        } catch (Exception e) {
            if (mx != null && started) {
                try {
                    mx.stop();
                } catch (Throwable ignore) {
                }
            }
            outFile.delete();
            throw new Exception("join/" + stage + ": " + e.getClass().getSimpleName() + " " + e.getMessage());
        } finally {
            if (mx != null) {
                try {
                    mx.release();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    private void renderSingle(final PluginCall call) {
        main.post(() -> {
            try {
                File cache = getContext().getCacheDir();
                int w = call.getInt("width", 1280), h = call.getInt("height", 720);
                int br = call.getInt("bitrate", 4000000);
                String out = call.getString("out", "out.mp4");
                File outFile = new File(cache, out);
                if (outFile.exists()) outFile.delete();
                renderOut = outFile;
                renderCall = call;

                JSONArray clips = call.getData().getJSONArray("clips");
                List<EditedMediaItem> items = new ArrayList<>();
                List<MediaItem> miList = new ArrayList<>();
                for (int i = 0; i < clips.length(); i++) {
                    JSONObject c = clips.getJSONObject(i);
                    MediaItem.ClippingConfiguration cc = new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(c.getLong("inMs"))
                            .setEndPositionMs(c.getLong("outMs"))
                            .build();
                    MediaItem mi = new MediaItem.Builder()
                            .setUri(Uri.fromFile(new File(cache, c.getString("path"))))
                            .setClippingConfiguration(cc)
                            .build();
                    miList.add(mi);
                    items.add(new EditedMediaItem.Builder(mi).setRemoveAudio(Boolean.TRUE.equals(call.getBoolean("mute", false))).build());
                }

                List<Effect> vfx = new ArrayList<>();
                vfx.add(Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT));
                JSONArray ovs = call.getData().optJSONArray("overlays");
                List<androidx.media3.effect.TextureOverlay> overlays = new ArrayList<>();
                final List<OverlayVideo> vos = new ArrayList<>();
                final List<TimedOverlay> singleTovs = new ArrayList<>();
                renderVos = vos;
                if (ovs != null) {
                    for (int i = 0; i < ovs.length(); i++) {
                        JSONObject vj = ovs.getJSONObject(i).optJSONObject("video");
                        if (vj != null) {
                            OverlayVideo vo = new OverlayVideo(
                                    new File(cache, vj.getString("path")).getAbsolutePath(), w, h,
                                    vj.getLong("fromMs") * 1000L, vj.getLong("toMs") * 1000L,
                                    vj.optLong("durMs", 0L) * 1000L,
                                    (float) vj.getDouble("x"), (float) vj.getDouble("y"),
                                    (float) vj.getDouble("sc"), (float) vj.getDouble("rot"),
                                    vj.optDouble("ar", 0.5625));
                    vo.setInUs(vj.optLong("inMs", 0L) * 1000L);
                            overlays.add(vo);
                            vos.add(vo);
                            continue;
                        }
                        JSONArray sl = ovs.getJSONObject(i).getJSONArray("slices");
                        int n = sl.length();
                        long[] f = new long[n], t = new long[n];
                        String[] p = new String[n];
                        int[] bx = new int[n], by = new int[n], bw = new int[n], bh = new int[n];
                        for (int j = 0; j < n; j++) {
                            JSONObject s = sl.getJSONObject(j);
                            f[j] = s.getLong("fromMs");
                            t[j] = s.getLong("toMs");
                            p[j] = new File(cache, s.getString("path")).getAbsolutePath();
                            bx[j] = s.optInt("x", 0);
                            by[j] = s.optInt("y", 0);
                            bw[j] = s.optInt("w", 0);
                            bh[j] = s.optInt("h", 0);
                        }
                        TimedOverlay tov = new TimedOverlay(f, t, p);
                        tov.setBoxes(bx, by, bw, bh, w, h);
                        overlays.add(tov);
                        singleTovs.add(tov);
                    }
                }
                if (!overlays.isEmpty()) vfx.add(new OverlayEffect(ImmutableList.copyOf(overlays)));

                Composition comp;
                if (items.size() == 1) { // v16: item-level effects (see parallel path)
                    items.set(0, new EditedMediaItem.Builder(miList.get(0))
                            .setRemoveAudio(Boolean.TRUE.equals(call.getBoolean("mute", false)))
                            .setEffects(new Effects(ImmutableList.<AudioProcessor>of(), ImmutableList.copyOf(vfx))).build());
                    comp = new Composition.Builder(ImmutableList.of(new EditedMediaItemSequence(ImmutableList.copyOf(items)))).build();
                } else {
                    comp = new Composition.Builder(ImmutableList.of(new EditedMediaItemSequence(ImmutableList.copyOf(items))))
                            .setEffects(new Effects(ImmutableList.<AudioProcessor>of(), ImmutableList.copyOf(vfx)))
                            .build();
                }

                final boolean[] finished = {false};
                Transformer.Builder tb = new Transformer.Builder(getContext());
                try {
                    Transformer.Builder.class.getMethod("setMaxDelayBetweenMuxerSamplesMs", long.class).invoke(tb, C.TIME_UNSET);
                } catch (Throwable ignore) {
                }
                transformer = tb
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .setEncoderFactory(new DefaultEncoderFactory.Builder(getContext())
                                .setRequestedVideoEncoderSettings(fastEnc(br))
                                .build())
                        .addListener(new Transformer.Listener() {
                            @Override
                            public void onCompleted(Composition composition, ExportResult r) {
                                if (renderCall != call) return;
                                renderCall = null;
                                setPausedFlag(false);
                                finished[0] = true;
                                long ovMs = 0;
                                for (OverlayVideo v : vos) {
                                    ovMs += v.spentMs();
                                    v.closeDecoder();
                                }
                                JSObject o = new JSObject();
                                o.put("ovMs", ovMs);
                                StringBuilder dg1 = new StringBuilder();
                                for (TimedOverlay q : singleTovs) dg1.append(dg1.length() > 0 ? " " : "").append(q.diag());
                                o.put("ovDiag", dg1.toString());
                                try {
                                    Object en = ExportResult.class.getField("videoEncoderName").get(r);
                                    if (en != null) o.put("enc", String.valueOf(en));
                                    o.put("frames", ExportResult.class.getField("videoFrameCount").getInt(r));
                                } catch (Throwable ignore) {
                                }
                                o.put("path", out);
                                o.put("sizeMB", Math.round(outFile.length() / 1048.576) / 1000.0);
                                if (!parNote.isEmpty()) o.put("parWhy", parNote);
                                parNote = "";
                                call.resolve(o);
                            }

                            @Override
                            public void onError(Composition composition, ExportResult r, ExportException e) {
                                if (renderCall != call) return;
                                renderCall = null;
                                setPausedFlag(false);
                                finished[0] = true;
                                for (OverlayVideo v : vos) v.closeDecoder();
                                call.reject(String.valueOf(e.getMessage()) + " (" + e.getErrorCodeName() + ")");
                            }
                        })
                        .build();

                transformer.start(comp, outFile.getAbsolutePath());

                final ProgressHolder ph = new ProgressHolder();
                main.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (finished[0]) return;
                        if (!paused && transformer.getProgress(ph) == Transformer.PROGRESS_STATE_AVAILABLE) {
                            JSObject o = new JSObject();
                            o.put("p", ph.progress);
                            notifyListeners("progress", o);
                        }
                        main.postDelayed(this, 500);
                    }
                }, 500);
            } catch (Exception e) {
                call.reject(e.toString());
            }
        });
    }

    /** Encoder settings; asks the hardware encoder for maximum throughput when this Media3 version supports it. */
    private static VideoEncoderSettings fastEnc(int br) {
        VideoEncoderSettings.Builder b = new VideoEncoderSettings.Builder().setBitrate(br);
        try {
            VideoEncoderSettings.Builder.class
                    .getMethod("setEncoderPerformanceParameters", int.class, int.class)
                    .invoke(b, Integer.MAX_VALUE, 1);
        } catch (Throwable ignore) {
        }
        return b.build();
    }

    private File exportsDir() {
        File d = new File(getContext().getFilesDir(), "exports");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    /** True only for files inside the app's own exports folder. */
    private boolean inExports(File f) {
        try {
            return f.getCanonicalPath().startsWith(exportsDir().getCanonicalPath() + File.separator);
        } catch (Exception e) {
            return false;
        }
    }

    /** Move the finished video from the cache into the app's own exports folder (stays inside the app until the person saves it to the phone). */
    @PluginMethod
    public void keepExport(PluginCall call) {
        try {
            File src = new File(getContext().getCacheDir(), call.getString("path"));
            String name = call.getString("name", "onelly-edit.mp4").replaceAll("[^A-Za-z0-9._-]", "_");
            File dst = new File(exportsDir(), name);
            if (dst.exists()) dst.delete();
            if (!src.renameTo(dst)) {
                try (InputStream i = new FileInputStream(src); OutputStream o = new FileOutputStream(dst)) {
                    byte[] buf = new byte[1 << 20];
                    int n;
                    while ((n = i.read(buf)) > 0) o.write(buf, 0, n);
                }
                src.delete();
            }
            JSObject o = new JSObject();
            o.put("abs", dst.getAbsolutePath());
            o.put("size", dst.length());
            call.resolve(o);
        } catch (Exception e) {
            call.reject(e.toString());
        }
    }

    /** Delete one video from the app's exports folder. */
    @PluginMethod
    public void deleteExport(PluginCall call) {
        try {
            File f = new File(call.getString("abs", ""));
            if (inExports(f)) f.delete();
            call.resolve();
        } catch (Exception e) {
            call.reject(e.toString());
        }
    }

    /** Open the system share / save sheet for a video already saved in the gallery. */
    @PluginMethod
    public void shareVideo(PluginCall call) {
        try {
            String uri = call.getString("uri");
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("video/mp4");
            i.putExtra(Intent.EXTRA_STREAM, Uri.parse(uri));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent c = Intent.createChooser(i, "Onelly Edit");
            c.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(c);
            call.resolve();
        } catch (Exception e) {
            call.reject(e.toString());
        }
    }

    /** Save the finished video into Movies/OnellyEdit (gallery). */
    @PluginMethod
    public void saveToGallery(PluginCall call) {
        try {
            String absSrc = call.getString("abs");
            File src = absSrc != null ? new File(absSrc) : new File(getContext().getCacheDir(), call.getString("path"));
            boolean keep = Boolean.TRUE.equals(call.getBoolean("keep", false));
            String name = call.getString("name", "onelly-edit.mp4");
            String where;
            String uriStr = null;
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver r = getContext().getContentResolver();
                ContentValues v = new ContentValues();
                v.put(MediaStore.Video.Media.DISPLAY_NAME, name);
                v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/OnellyEdit");
                v.put(MediaStore.Video.Media.IS_PENDING, 1);
                Uri u = r.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
                try (OutputStream o = r.openOutputStream(u); InputStream i = new FileInputStream(src)) {
                    byte[] buf = new byte[1 << 20];
                    int n;
                    while ((n = i.read(buf)) > 0) o.write(buf, 0, n);
                }
                v.clear();
                v.put(MediaStore.Video.Media.IS_PENDING, 0);
                r.update(u, v, null, null);
                uriStr = u.toString();
                where = "Movies/OnellyEdit/" + name;
            } else {
                File d = getContext().getExternalFilesDir(Environment.DIRECTORY_MOVIES);
                if (d != null) d.mkdirs();
                File dst = new File(d, name);
                try (OutputStream o = new FileOutputStream(dst); InputStream i = new FileInputStream(src)) {
                    byte[] buf = new byte[1 << 20];
                    int n;
                    while ((n = i.read(buf)) > 0) o.write(buf, 0, n);
                }
                where = dst.getAbsolutePath();
            }
            if (!keep) src.delete();
            JSObject o = new JSObject();
            o.put("where", where);
            if (uriStr != null) o.put("uri", uriStr);
            call.resolve(o);
        } catch (Exception e) {
            call.reject(e.toString());
        }
    }
}

/**
 * Onelly Edit "Turbo" export engine. Independent of Fast Export, Standard Export and Media3.
 *
 * Pipeline: hardware decoder -> SurfaceTexture -> OpenGL (video + overlay layers) -> hardware encoder surface -> MP4.
 * Every overlay (text, sticker, photo) is ONE picture (or a few, e.g. typing text) plus a motion track
 * (position, size, rotation, transparency over time). The GPU moves the picture; nothing is re-drawn on the CPU per frame,
 * so a 3 hour video with hundreds of moving overlays costs the same as one without.
 * Layers are a plain list, so more kinds (video overlay, filter, transition...) can be added without touching the rest.
 */
final class TurboRender {

    interface Progress {
        void on(double percent, double fps);
    }

    static volatile boolean cancel = false;
    static volatile boolean paused = false;

    private static final String VS_OES = "uniform mat4 uTex;attribute vec4 aPos;attribute vec4 aTc;varying vec2 vTc;"
            + "void main(){gl_Position=aPos;vTc=(uTex*aTc).xy;}";
    private static final String FS_OES = "#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float;varying vec2 vTc;uniform samplerExternalOES sTex;"
            + "void main(){gl_FragColor=texture2D(sTex,vTc);}";
    private static final String VS_2D = "attribute vec4 aPos;attribute vec2 aTc;varying vec2 vTc;"
            + "void main(){gl_Position=aPos;vTc=aTc;}";
    private static final String FS_2D = "precision mediump float;varying vec2 vTc;uniform sampler2D sTex;uniform float uA;"
            + "void main(){gl_FragColor=texture2D(sTex,vTc)*uA;}";

    /** One overlay: picture variants (time ranges) + a motion track (affine matrix + opacity samples). */
    private static final class Layer {
        long[] vt0, vt1;
        String[] vpath;
        float[] vox, voy;
        long[] ts;
        double[] m; // 7 numbers per sample: a b c d e f opacity (canvas style: x' = a x + c y + e)
        boolean[] brk; // true: do not blend from the previous sample (jump)
        int tex = 0, vidx = -1, bw = 0, bh = 0;
        String loaded = null;
        boolean has = false;
        long frames = 0, hits = 0;
    }

    private final File cache;
    private final Progress pg;

    TurboRender(File cache, Progress pg) {
        this.cache = cache;
        this.pg = pg;
    }

    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private int progOes, progTex, oesTex;
    private final FloatBuffer vb = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final float[] texMat = new float[16];
    private final Object frameLock = new Object();
    private boolean frameReady = false;
    private long outFrames = 0, lastOutUs = 0;

    JSONObject run(JSONObject a) throws Exception {
        cancel = false;
        paused = false;
        final long t0 = System.nanoTime();
        JSONArray clips = a.getJSONArray("clips");
        if (clips.length() != 1) throw new Exception("turbo: one clip only");
        JSONObject c0 = clips.getJSONObject(0);
        final String srcPath = new File(cache, c0.getString("path")).getAbsolutePath();
        final long inUs = c0.optLong("inMs", 0) * 1000L;
        long outMs = c0.optLong("outMs", 0);
        final int W = (a.getInt("width") / 2) * 2, H = (a.getInt("height") / 2) * 2;
        final int bitrate = a.getInt("bitrate");
        final boolean mute = a.optBoolean("mute", false);
        final File outFile = new File(cache, a.optString("out", "out.mp4"));
        if (outFile.exists()) outFile.delete();

        List<Layer> layers = new ArrayList<>();
        JSONArray ovs = a.optJSONArray("overlays");
        if (ovs != null) {
            for (int i = 0; i < ovs.length(); i++) {
                JSONObject o = ovs.getJSONObject(i);
                JSONArray va = o.optJSONArray("v"), ta = o.optJSONArray("ts"), ma = o.optJSONArray("m"), ba = o.optJSONArray("brk");
                if (va == null || va.length() == 0 || ta == null || ta.length() == 0 || ma == null || ma.length() < 7 * ta.length())
                    throw new Exception("turbo: bad overlay data");
                Layer L = new Layer();
                int nv = va.length();
                L.vt0 = new long[nv];
                L.vt1 = new long[nv];
                L.vpath = new String[nv];
                L.vox = new float[nv];
                L.voy = new float[nv];
                for (int k = 0; k < nv; k++) {
                    JSONObject v = va.getJSONObject(k);
                    L.vt0[k] = v.getLong("t0");
                    L.vt1[k] = v.getLong("t1");
                    L.vpath[k] = v.optString("path", "");
                    L.vox[k] = (float) v.optDouble("ox", 0);
                    L.voy[k] = (float) v.optDouble("oy", 0);
                }
                int n = ta.length();
                L.ts = new long[n];
                L.brk = new boolean[n];
                L.m = new double[7 * n];
                for (int k = 0; k < n; k++) {
                    L.ts[k] = ta.getLong(k);
                    L.brk[k] = ba != null && k < ba.length() && ba.optInt(k, 0) != 0;
                }
                for (int k = 0; k < 7 * n; k++) L.m[k] = ma.getDouble(k);
                layers.add(L);
            }
        }

        MediaExtractor ex = new MediaExtractor();
        MediaExtractor aex = null;
        MediaCodec dec = null, enc = null;
        MediaMuxer mux = null;
        SurfaceTexture st = null;
        Surface decSurface = null, encSurface = null;
        boolean muxStarted = false;
        try {
            ex.setDataSource(srcPath);
            int vt = -1, at = -1;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                String m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("video/") && vt < 0) vt = i;
                if (m != null && m.startsWith("audio/") && at < 0) at = i;
            }
            if (vt < 0) throw new Exception("turbo: no video track");
            MediaFormat vf = ex.getTrackFormat(vt);
            if (vf.containsKey(MediaFormat.KEY_ROTATION) && vf.getInteger(MediaFormat.KEY_ROTATION) != 0)
                throw new Exception("turbo: rotated video not supported yet");
            String vmime = vf.getString(MediaFormat.KEY_MIME);
            int fps = 30;
            if (vf.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                try {
                    fps = vf.getInteger(MediaFormat.KEY_FRAME_RATE);
                } catch (Throwable e1) {
                    try {
                        fps = Math.round(vf.getFloat(MediaFormat.KEY_FRAME_RATE));
                    } catch (Throwable ignore) {
                    }
                }
                fps = Math.max(1, Math.min(240, fps));
            }
            final int tfps = a.optInt("fps", 0);
            final boolean hevc = "hevc".equals(a.optString("codec", "avc"));
            final String emime = hevc ? "video/hevc" : "video/avc";
            final boolean tickMode = tfps > 0 && Math.abs(tfps - fps) > 1; // other frame rate than the video: frames are repeated / skipped on a fixed clock, overlays still move smoothly
            final int outFps = tickMode ? tfps : fps;
            final double tickDt = 1e6 / outFps;
            final long slack = (long) Math.min(tickDt / 4.0, 4000.0);
            long durUs = vf.containsKey(MediaFormat.KEY_DURATION) ? vf.getLong(MediaFormat.KEY_DURATION) : 0;
            final long outUs = outMs > 0 ? outMs * 1000L : durUs;
            if (outUs <= inUs) throw new Exception("turbo: empty range");
            final double span = outUs - inUs;

            MediaFormat ef = MediaFormat.createVideoFormat(emime, W, H);
            ef.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            ef.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            ef.setInteger(MediaFormat.KEY_FRAME_RATE, outFps);
            ef.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            try {
                ef.setInteger("priority", 1);
                ef.setInteger("operating-rate", Short.MAX_VALUE);
            } catch (Throwable ignore) {
            }
            if (!hevc) {
                try {
                    ef.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
                    ef.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41);
                } catch (Throwable ignore) {
                }
            }
            enc = MediaCodec.createEncoderByType(emime);
            try {
                enc.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            } catch (Throwable e) {
                try {
                    enc.release();
                } catch (Throwable ignore) {
                }
                ef.removeKey(MediaFormat.KEY_PROFILE);
                ef.removeKey(MediaFormat.KEY_LEVEL);
                enc = MediaCodec.createEncoderByType(emime);
                enc.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            }
            encSurface = enc.createInputSurface();
            enc.start();

            initEgl(encSurface);
            progOes = program(VS_OES, FS_OES);
            progTex = program(VS_2D, FS_2D);
            int[] t = new int[1];
            GLES20.glGenTextures(1, t, 0);
            oesTex = t[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            for (Layer L : layers) {
                GLES20.glGenTextures(1, t, 0);
                L.tex = t[0];
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, L.tex);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            }
            GLES20.glViewport(0, 0, W, H);

            st = new SurfaceTexture(oesTex);
            st.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
                @Override
                public void onFrameAvailable(SurfaceTexture s) {
                    synchronized (frameLock) {
                        frameReady = true;
                        frameLock.notifyAll();
                    }
                }
            });
            decSurface = new Surface(st);
            dec = MediaCodec.createDecoderByType(vmime);
            dec.configure(vf, decSurface, null, 0);
            dec.start();
            ex.selectTrack(vt);
            ex.seekTo(inUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);

            MediaFormat af = null;
            int aTrack = -1;
            if (!mute && at >= 0) {
                aex = new MediaExtractor();
                aex.setDataSource(srcPath);
                aex.selectTrack(at);
                aex.seekTo(inUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                af = aex.getTrackFormat(at);
            }

            mux = new MediaMuxer(outFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int vTrack = -1;
            ByteBuffer aBuf = ByteBuffer.allocateDirect(1 << 20);
            MediaCodec.BufferInfo ai = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo di = new MediaCodec.BufferInfo();
            MediaCodec.BufferInfo ei = new MediaCodec.BufferInfo();
            boolean inDone = false, decDone = false, encDone = false, eosSent = false;
            boolean audioDone = (aex == null);
            long frames = 0, lastNs = System.nanoTime(), lastFrames = 0;
            double fpsNow = 0;
            long lastPtsUs = inUs;
            boolean haveFrame = false;
            long tickBase = 0, nextTick = 0, tickN = 0;
            outFrames = 0;
            lastOutUs = inUs;

            while (!encDone) {
                if (cancel) throw new Exception("cancelled");
                FastRenderPlugin.waitIfPaused(); // same pause mechanism as the rest of the plugin (counted by pauseState)
                if (cancel) throw new Exception("cancelled");

                if (!inDone) {
                    int ii = dec.dequeueInputBuffer(0);
                    if (ii >= 0) {
                        ByteBuffer ib = dec.getInputBuffer(ii);
                        int sz = ex.readSampleData(ib, 0);
                        long ts = ex.getSampleTime();
                        if (sz < 0 || ts > outUs + 2000000L) {
                            dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            dec.queueInputBuffer(ii, 0, sz, ts, 0);
                            ex.advance();
                        }
                    }
                }

                if (!decDone) {
                    int oi = dec.dequeueOutputBuffer(di, 2000);
                    if (oi >= 0) {
                        boolean eos = (di.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        long pts = di.presentationTimeUs;
                        boolean use = di.size > 0 && pts >= inUs && pts < outUs;
                        if (tickMode && use && haveFrame) { // ticks that still belong to the previous video frame
                            while (nextTick + slack < pts && nextTick < outUs) {
                                emit(layers, nextTick, inUs, W, H);
                                tickN++;
                                nextTick = tickBase + (long) (tickN * tickDt);
                            }
                        }
                        dec.releaseOutputBuffer(oi, use);
                        if (use) {
                            waitFrame();
                            st.updateTexImage();
                            st.getTransformMatrix(texMat);
                            if (!haveFrame) {
                                haveFrame = true;
                                tickBase = pts;
                                tickN = 0;
                                nextTick = pts;
                            }
                            if (!tickMode) emit(layers, pts, inUs, W, H);
                        }
                        if (eos || pts >= outUs) {
                            if (tickMode && haveFrame) {
                                while (nextTick < outUs) {
                                    emit(layers, nextTick, inUs, W, H);
                                    tickN++;
                                    nextTick = tickBase + (long) (tickN * tickDt);
                                }
                            }
                            decDone = true;
                        }
                    }
                }
                frames = outFrames;
                lastPtsUs = lastOutUs;

                while (true) {
                    int ei2 = enc.dequeueOutputBuffer(ei, 0);
                    if (ei2 == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                    if (ei2 == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        vTrack = mux.addTrack(enc.getOutputFormat());
                        if (af != null) aTrack = mux.addTrack(af);
                        mux.start();
                        muxStarted = true;
                    } else if (ei2 >= 0) {
                        ByteBuffer eb = enc.getOutputBuffer(ei2);
                        if ((ei.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) ei.size = 0;
                        if (ei.size > 0 && muxStarted) {
                            eb.position(ei.offset);
                            eb.limit(ei.offset + ei.size);
                            mux.writeSampleData(vTrack, eb, ei);
                        }
                        boolean last = (ei.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        enc.releaseOutputBuffer(ei2, false);
                        if (last) {
                            encDone = true;
                            break;
                        }
                    }
                }

                if (muxStarted && !audioDone) {
                    long upto = (lastPtsUs - inUs) + 500000L;
                    while (true) {
                        long ts = aex.getSampleTime();
                        if (ts < 0 || ts > outUs) {
                            audioDone = true;
                            break;
                        }
                        if (ts - inUs > upto) break;
                        aBuf.clear();
                        int n = aex.readSampleData(aBuf, 0);
                        if (n < 0) {
                            audioDone = true;
                            break;
                        }
                        if (ts >= inUs) {
                            ai.set(0, n, ts - inUs, (aex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                            mux.writeSampleData(aTrack, aBuf, ai);
                        }
                        aex.advance();
                    }
                }

                if (decDone && !eosSent) {
                    enc.signalEndOfInputStream();
                    eosSent = true;
                }

                long now = System.nanoTime();
                if (now - lastNs > 500_000_000L) {
                    fpsNow = (frames - lastFrames) * 1e9 / (now - lastNs);
                    lastNs = now;
                    lastFrames = frames;
                    pg.on(Math.min(99.0, (lastPtsUs - inUs) * 100.0 / span), fpsNow);
                }
            }

            if (muxStarted && !audioDone) {
                while (true) {
                    long ts = aex.getSampleTime();
                    if (ts < 0 || ts > outUs) break;
                    aBuf.clear();
                    int n = aex.readSampleData(aBuf, 0);
                    if (n < 0) break;
                    if (ts >= inUs) {
                        ai.set(0, n, ts - inUs, (aex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                        mux.writeSampleData(aTrack, aBuf, ai);
                    }
                    aex.advance();
                }
            }
            if (muxStarted) {
                mux.stop();
                muxStarted = false;
            }
            mux.release();
            mux = null;

            StringBuilder dg = new StringBuilder();
            for (Layer L : layers) dg.append(dg.length() > 0 ? " " : "").append(L.frames).append('/').append(L.hits).append("@0");
            JSONObject res = new JSONObject();
            res.put("path", outFile.getName());
            res.put("sizeMB", Math.round(outFile.length() / 1048.576) / 1000.0);
            res.put("frames", frames);
            res.put("sec", Math.round((System.nanoTime() - t0) / 1e8) / 10.0);
            res.put("fps", Math.round(frames * 1e9 / Math.max(1, System.nanoTime() - t0)));
            res.put("enc", enc.getName());
            res.put("ovDiag", dg.toString());
            return res;
        } catch (Throwable e) {
            try {
                if (outFile.exists()) outFile.delete();
            } catch (Throwable ignore) {
            }
            if (e instanceof Exception) throw (Exception) e;
            throw new Exception(e);
        } finally {
            try {
                if (mux != null) {
                    if (muxStarted) mux.stop();
                    mux.release();
                }
            } catch (Throwable ignore) {
            }
            try {
                if (dec != null) {
                    dec.stop();
                    dec.release();
                }
            } catch (Throwable ignore) {
            }
            try {
                if (enc != null) {
                    enc.stop();
                    enc.release();
                }
            } catch (Throwable ignore) {
            }
            try {
                if (st != null) st.release();
                if (decSurface != null) decSurface.release();
                if (encSurface != null) encSurface.release();
            } catch (Throwable ignore) {
            }
            try {
                ex.release();
                if (aex != null) aex.release();
            } catch (Throwable ignore) {
            }
            releaseEgl();
        }
    }

    /** Draws the latest video frame + overlays for output time tUs and hands the picture to the encoder. */
    private void emit(List<Layer> layers, long tUs, long inUs, int W, int H) {
        drawFrame(layers, (tUs - inUs) / 1000.0, W, H);
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, (tUs - inUs) * 1000L);
        EGL14.eglSwapBuffers(eglDisplay, eglSurface);
        outFrames++;
        lastOutUs = tUs;
    }

    private void waitFrame() throws Exception {
        synchronized (frameLock) {
            long end = System.currentTimeMillis() + 5000;
            while (!frameReady) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) throw new Exception("turbo: frame timeout");
                frameLock.wait(left);
            }
            frameReady = false;
        }
    }

    private void drawFrame(List<Layer> layers, double tl, int W, int H) {
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(progOes);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progOes, "sTex"), 0);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(progOes, "uTex"), 1, false, texMat, 0);
        quadRect(progOes, -1f, -1f, 1f, 1f, true);
        if (layers.isEmpty()) return;
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA); // pictures are premultiplied
        GLES20.glUseProgram(progTex);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progTex, "sTex"), 0);
        int uA = GLES20.glGetUniformLocation(progTex, "uA");
        float[] q = new float[8];
        for (Layer L : layers) {
            L.frames++;
            int vi = findVariant(L, tl);
            if (vi < 0) continue;
            if (vi != L.vidx) {
                L.vidx = vi;
                String p = L.vpath[vi];
                if (p != null && p.length() > 0) {
                    if (!p.equals(L.loaded)) {
                        Bitmap b = BitmapFactory.decodeFile(new File(cache, p).getAbsolutePath());
                        if (b != null) {
                            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, L.tex);
                            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
                            L.bw = b.getWidth();
                            L.bh = b.getHeight();
                            b.recycle();
                            L.loaded = p;
                            L.has = true;
                        } else {
                            L.has = false;
                            L.loaded = null;
                        }
                    } else L.has = true;
                } else L.has = false;
            }
            if (!L.has) continue;
            // motion track at this time
            int n = L.ts.length, i = 0, lo = 0, hi = n - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (L.ts[mid] <= tl) {
                    i = mid;
                    lo = mid + 1;
                } else hi = mid - 1;
            }
            double a, b, c, d, e, f, op;
            int k = 7 * i;
            if (i >= n - 1 || L.brk[i + 1] || tl <= L.ts[i]) {
                a = L.m[k]; b = L.m[k + 1]; c = L.m[k + 2]; d = L.m[k + 3]; e = L.m[k + 4]; f = L.m[k + 5]; op = L.m[k + 6];
            } else {
                double u = (tl - L.ts[i]) / (double) (L.ts[i + 1] - L.ts[i]);
                int k2 = k + 7;
                a = L.m[k] + (L.m[k2] - L.m[k]) * u;
                b = L.m[k + 1] + (L.m[k2 + 1] - L.m[k + 1]) * u;
                c = L.m[k + 2] + (L.m[k2 + 2] - L.m[k + 2]) * u;
                d = L.m[k + 3] + (L.m[k2 + 3] - L.m[k + 3]) * u;
                e = L.m[k + 4] + (L.m[k2 + 4] - L.m[k + 4]) * u;
                f = L.m[k + 5] + (L.m[k2 + 5] - L.m[k + 5]) * u;
                op = L.m[k + 6] + (L.m[k2 + 6] - L.m[k + 6]) * u;
            }
            if (op <= 0.002) continue;
            float ox = L.vox[vi], oy = L.voy[vi];
            float[] px = {-ox, L.bw - ox, -ox, L.bw - ox}, py = {-oy, -oy, L.bh - oy, L.bh - oy}; // TL TR BL BR
            for (int z = 0; z < 4; z++) {
                double X = a * px[z] + c * py[z] + e, Y = b * px[z] + d * py[z] + f;
                q[2 * z] = (float) (X * 2.0 / W - 1.0);
                q[2 * z + 1] = (float) (1.0 - Y * 2.0 / H);
            }
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, L.tex);
            GLES20.glUniform1f(uA, (float) Math.min(1.0, op));
            quadPts(progTex, q);
            L.hits++;
        }
    }

    private static int findVariant(Layer L, double ms) {
        int lo = 0, hi = L.vt0.length - 1, idx = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (L.vt0[mid] <= ms) {
                idx = mid;
                lo = mid + 1;
            } else hi = mid - 1;
        }
        if (idx >= 0 && ms >= L.vt1[idx]) idx = -1;
        return idx;
    }

    /** Video: whole-frame rectangle with the SurfaceTexture matrix. */
    private void quadRect(int prog, float l, float b, float r, float t, boolean video) {
        float[] v = {l, b, 0f, 0f, r, b, 1f, 0f, l, t, 0f, 1f, r, t, 1f, 1f};
        draw(prog, v);
    }

    /** Picture: corners TL, TR, BL, BR in clip space (matching picture corners). */
    private void quadPts(int prog, float[] q) {
        float[] v = {q[0], q[1], 0f, 0f, q[2], q[3], 1f, 0f, q[4], q[5], 0f, 1f, q[6], q[7], 1f, 1f};
        draw(prog, v);
    }

    private void draw(int prog, float[] v) {
        vb.clear();
        vb.put(v).position(0);
        int aPos = GLES20.glGetAttribLocation(prog, "aPos"), aTc = GLES20.glGetAttribLocation(prog, "aTc");
        vb.position(0);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, vb);
        GLES20.glEnableVertexAttribArray(aPos);
        vb.position(2);
        GLES20.glVertexAttribPointer(aTc, 2, GLES20.GL_FLOAT, false, 16, vb);
        GLES20.glEnableVertexAttribArray(aTc);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aTc);
    }

    private void initEgl(Surface target) throws Exception {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] ver = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) throw new Exception("turbo: egl init");
        int[] attrs = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, 0x3142 /* EGL_RECORDABLE_ANDROID */, 1, EGL14.EGL_NONE};
        EGLConfig[] cfg = new EGLConfig[1];
        int[] n = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, attrs, 0, cfg, 0, 1, n, 0) || n[0] < 1) throw new Exception("turbo: egl config");
        int[] ca = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        eglContext = EGL14.eglCreateContext(eglDisplay, cfg[0], EGL14.EGL_NO_CONTEXT, ca, 0);
        int[] sa = {EGL14.EGL_NONE};
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, cfg[0], target, sa, 0);
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) throw new Exception("turbo: egl current");
    }

    private void releaseEgl() {
        try {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface);
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext);
                EGL14.eglReleaseThread();
                EGL14.eglTerminate(eglDisplay);
            }
        } catch (Throwable ignore) {
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY;
        eglContext = EGL14.EGL_NO_CONTEXT;
        eglSurface = EGL14.EGL_NO_SURFACE;
    }

    private static int program(String vs, String fs) throws Exception {
        int v = shader(GLES20.GL_VERTEX_SHADER, vs), f = shader(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new Exception("turbo: link " + GLES20.glGetProgramInfoLog(p));
        return p;
    }

    private static int shader(int type, String src) throws Exception {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new Exception("turbo: shader " + GLES20.glGetShaderInfoLog(s));
        return s;
    }
}
