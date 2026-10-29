package app.onelly.edit;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Onelly Edit "Turbo" export engine. Independent of Fast Export, Standard Export and Media3.
 *
 * Pipeline: hardware decoder -> SurfaceTexture -> OpenGL (video + overlay layers) -> hardware encoder surface -> MP4.
 * Nothing runs on the CPU per frame except bookkeeping, so the speed is limited by the phone's video chip only.
 *
 * Stage 1: one clip, text / sticker / photo overlays (timed picture slices made by the web side), source audio copied as is.
 * Layers are a plain list ("Layer"), so more layer kinds (video overlay, filter, transition...) can be added without touching the rest.
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
    private static final String FS_2D = "precision mediump float;varying vec2 vTc;uniform sampler2D sTex;"
            + "void main(){gl_FragColor=texture2D(sTex,vTc);}";

    /** One overlay layer = a list of timed picture slices (time in output milliseconds, box in output pixels). */
    private static final class Layer {
        long[] from, to;
        String[] path;
        int[] bx, by, bw, bh;
        int tex = 0;
        String loaded = null;
        int idx = -1;
        long frames = 0, hits = 0;
        boolean hasPic = false;
    }

    private final File cache;
    private final Progress pg;

    TurboRender(File cache, Progress pg) {
        this.cache = cache;
        this.pg = pg;
    }

    // ---- GL state ----
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private int progOes, progTex, oesTex;
    private final FloatBuffer vb = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final float[] texMat = new float[16];
    private final Object frameLock = new Object();
    private boolean frameReady = false;

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

        // ---- layers from the web side ----
        List<Layer> layers = new ArrayList<>();
        JSONArray ovs = a.optJSONArray("overlays");
        if (ovs != null) {
            for (int i = 0; i < ovs.length(); i++) {
                JSONObject o = ovs.getJSONObject(i);
                if (o.has("video")) throw new Exception("turbo: video overlay not supported yet");
                JSONArray sl = o.optJSONArray("slices");
                if (sl == null || sl.length() == 0) continue;
                Layer L = new Layer();
                int n = sl.length();
                L.from = new long[n];
                L.to = new long[n];
                L.path = new String[n];
                L.bx = new int[n];
                L.by = new int[n];
                L.bw = new int[n];
                L.bh = new int[n];
                for (int k = 0; k < n; k++) {
                    JSONObject s = sl.getJSONObject(k);
                    L.from[k] = s.getLong("fromMs");
                    L.to[k] = s.getLong("toMs");
                    L.path[k] = s.optString("path", "");
                    L.bx[k] = s.optInt("x", 0);
                    L.by[k] = s.optInt("y", 0);
                    L.bw[k] = s.optInt("w", 0);
                    L.bh[k] = s.optInt("h", 0);
                }
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
            try {
                if (vf.containsKey(MediaFormat.KEY_FRAME_RATE)) fps = Math.max(1, Math.min(120, vf.getInteger(MediaFormat.KEY_FRAME_RATE)));
            } catch (Throwable ignore) {
            }
            long durUs = vf.containsKey(MediaFormat.KEY_DURATION) ? vf.getLong(MediaFormat.KEY_DURATION) : 0;
            final long outUs = outMs > 0 ? outMs * 1000L : durUs;
            if (outUs <= inUs) throw new Exception("turbo: empty range");
            final double span = outUs - inUs;

            // ---- encoder (hardware preferred) ----
            MediaFormat ef = MediaFormat.createVideoFormat("video/avc", W, H);
            ef.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            ef.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            ef.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            ef.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            try {
                ef.setInteger("priority", 1); // best effort = as fast as the chip can
                ef.setInteger("operating-rate", Short.MAX_VALUE);
            } catch (Throwable ignore) {
            }
            try {
                ef.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
                ef.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41);
            } catch (Throwable ignore) {
            }
            enc = MediaCodec.createEncoderByType("video/avc");
            try {
                enc.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            } catch (Throwable e) { // some chips refuse High/4.1: plain settings
                try {
                    enc.release();
                } catch (Throwable ignore) {
                }
                ef.removeKey(MediaFormat.KEY_PROFILE);
                ef.removeKey(MediaFormat.KEY_LEVEL);
                enc = MediaCodec.createEncoderByType("video/avc");
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

            // ---- decoder -> SurfaceTexture ----
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

            // ---- audio: copied as it is from the source ----
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

            while (!encDone) {
                if (cancel) throw new Exception("cancelled");
                while (paused && !cancel) Thread.sleep(80);

                // 1) feed the decoder
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

                // 2) decoder output -> GL -> encoder
                if (!decDone) {
                    int oi = dec.dequeueOutputBuffer(di, 2000);
                    if (oi >= 0) {
                        boolean eos = (di.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        long pts = di.presentationTimeUs;
                        boolean render = di.size > 0 && pts >= inUs && pts < outUs;
                        dec.releaseOutputBuffer(oi, render);
                        if (render) {
                            waitFrame();
                            st.updateTexImage();
                            st.getTransformMatrix(texMat);
                            long tl = (pts - inUs) / 1000L; // output time in ms
                            drawFrame(layers, tl, W, H);
                            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, (pts - inUs) * 1000L);
                            EGL14.eglSwapBuffers(eglDisplay, eglSurface);
                            frames++;
                            lastPtsUs = pts;
                        }
                        if (eos || pts >= outUs) decDone = true;
                    }
                }

                // 3) encoder output -> muxer
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

                // 4) audio copied alongside the video (keeps the file interleaved)
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

            // finish the rest of the audio
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

    // ------------------------------------------------------------------ drawing

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

    private void drawFrame(List<Layer> layers, long tlMs, int W, int H) {
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        // video
        GLES20.glUseProgram(progOes);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progOes, "sTex"), 0);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(progOes, "uTex"), 1, false, texMat, 0);
        quad(progOes, -1f, -1f, 1f, 1f, true);
        // overlay layers, in the order they were given
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA); // pictures are premultiplied
        GLES20.glUseProgram(progTex);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progTex, "sTex"), 0);
        for (Layer L : layers) {
            L.frames++;
            int i = find(L, tlMs);
            if (i < 0) continue;
            if (i != L.idx) {
                L.idx = i;
                String p = L.path[i];
                if (p != null && p.length() > 0) {
                    if (!p.equals(L.loaded)) {
                        Bitmap b = BitmapFactory.decodeFile(new File(cache, p).getAbsolutePath());
                        if (b != null) {
                            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, L.tex);
                            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0);
                            b.recycle();
                            L.loaded = p;
                            L.hasPic = true;
                        } else {
                            L.hasPic = false;
                            L.loaded = null;
                        }
                    }
                } else {
                    L.hasPic = false;
                }
            }
            if (!L.hasPic) continue;
            int bw = L.bw[i], bh = L.bh[i];
            float l, r, tp, bt;
            if (bw > 0 && bh > 0) {
                l = L.bx[i] * 2f / W - 1f;
                r = (L.bx[i] + bw) * 2f / W - 1f;
                tp = 1f - L.by[i] * 2f / H;
                bt = 1f - (L.by[i] + bh) * 2f / H;
            } else { // whole picture (fades)
                l = -1f;
                r = 1f;
                tp = 1f;
                bt = -1f;
            }
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, L.tex);
            quad(progTex, l, bt, r, tp, false);
            L.hits++;
        }
    }

    /** Active slice index at time ms (slices are sorted by time), -1 when none. */
    private static int find(Layer L, long ms) {
        int lo = 0, hi = L.from.length - 1, idx = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (L.from[mid] <= ms) {
                idx = mid;
                lo = mid + 1;
            } else hi = mid - 1;
        }
        if (idx >= 0 && ms >= L.to[idx]) idx = -1;
        return idx;
    }

    /** Draws a rectangle (l,b)-(r,t) in clip space. video=true: texture coords fill the SurfaceTexture, picture: top row first. */
    private void quad(int prog, float l, float b, float r, float t, boolean video) {
        float[] v = video
                ? new float[]{l, b, 0f, 0f, r, b, 1f, 0f, l, t, 0f, 1f, r, t, 1f, 1f}
                : new float[]{l, b, 0f, 1f, r, b, 1f, 1f, l, t, 0f, 0f, r, t, 1f, 0f};
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

    // ------------------------------------------------------------------ EGL / GL helpers

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
