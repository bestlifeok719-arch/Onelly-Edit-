package app.onelly.edit;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.BitmapOverlay;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Overlay VIDEO for the native export. The overlay file is decoded once, strictly in order, by the
 * hardware MediaCodec (no seeking per frame - that is what made long overlay videos impossible).
 * Every frame is converted to a small bitmap of the size it is shown at, drawn at its place
 * (x, y, size, turn) on a transparent full-frame bitmap, and given to the GPU overlay effect.
 */
@UnstableApi
public class OverlayVideo extends BitmapOverlay {
    private final String path;
    private final int W, H, dw, dh;
    private final long fromUs, toUs, durUs;
    private final float px, py, rotDeg;
    private final Bitmap blank = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
    private final Bitmap[] ring = new Bitmap[3];
    private int ri = 0;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

    private MediaExtractor ex;
    private MediaCodec dec;
    private final MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
    private int srcRot = 0, frameNo = 0, lastFrameNo = -1;
    private boolean inEos, outEos, converted, hd;
    private Bitmap vb, last;

    /* The overlay video is decoded on its own thread, ahead of the GPU, so the render never waits for it. */
    private static final class Fr {
        final int[] px;
        final long pts;
        Fr(int[] px, long pts) {
            this.px = px;
            this.pts = pts;
        }
    }

    private static final int QN = 4;
    private final ArrayBlockingQueue<Fr> queue = new ArrayBlockingQueue<>(QN);
    private final ArrayBlockingQueue<int[]> free = new ArrayBlockingQueue<>(QN + 3);
    private Thread worker;
    private volatile boolean stop;
    private volatile Exception err;
    private volatile long target;
    private long spentNs;
    private Fr showFr, pendFr;
    private boolean ended;
    private long offUs = 0;
    private long inUs = 0; // v16: where in the video file this clip starts (split / looped overlay videos)

    public void setInUs(long us) {
        this.inUs = us;
    }

    /** Where this export piece starts on the whole timeline (parallel export). */
    public void setOffsetUs(long us) {
        this.offUs = us;
    }

    public OverlayVideo(String path, int W, int H, long fromUs, long toUs, long durUs,
                        float xPct, float yPct, float sc, float rotDeg, double aspect) {
        this.path = path;
        this.W = W;
        this.H = H;
        this.fromUs = fromUs;
        this.toUs = toUs;
        this.durUs = durUs;
        this.px = xPct;
        this.py = yPct;
        this.rotDeg = rotDeg;
        this.dw = Math.max(2, Math.min(4096, Math.round(W * sc)));
        this.dh = Math.max(2, Math.min(4096, (int) Math.round(this.dw * aspect)));
    }

    private void open() throws Exception {
        if (dec != null) return;
        ex = new MediaExtractor();
        ex.setDataSource(path);
        int vi = -1;
        MediaFormat f = null;
        for (int i = 0; i < ex.getTrackCount(); i++) {
            MediaFormat tf = ex.getTrackFormat(i);
            String m = tf.getString(MediaFormat.KEY_MIME);
            if (m != null && m.startsWith("video/")) {
                vi = i;
                f = tf;
                break;
            }
        }
        if (vi < 0 || f == null) throw new Exception("overlay video: no video track");
        ex.selectTrack(vi);
        srcRot = f.containsKey("rotation-degrees") ? f.getInteger("rotation-degrees") : 0;
        int fw = f.containsKey(MediaFormat.KEY_WIDTH) ? f.getInteger(MediaFormat.KEY_WIDTH) : 0;
        int fh = f.containsKey(MediaFormat.KEY_HEIGHT) ? f.getInteger(MediaFormat.KEY_HEIGHT) : 0;
        hd = Math.max(fw, fh) >= 1280;
        try {
            f.setInteger("operating-rate", Integer.MAX_VALUE);
            f.setInteger("priority", 1);
        } catch (Exception ignore) {
        }
        dec = MediaCodec.createDecoderByType(f.getString(MediaFormat.KEY_MIME));
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        dec.configure(f, null, null, 0);
        dec.start();
        vb = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888);
    }

    /** Decode the next frame in order. Returns its time (us) or -1 at the end. Sets 'converted'. */
    private long decodeOne(long mtUs, int[] outArr) throws Exception {
        long waited = 0;
        for (; ; ) {
            if (stop) throw new Exception("stopped");
            if (!inEos) {
                int ii = dec.dequeueInputBuffer(0);
                if (ii >= 0) {
                    ByteBuffer b = dec.getInputBuffer(ii);
                    int n = (b == null) ? -1 : ex.readSampleData(b, 0);
                    if (n < 0) {
                        dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inEos = true;
                    } else {
                        dec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0);
                        ex.advance();
                    }
                }
            }
            int oi = dec.dequeueOutputBuffer(bi, 10000);
            if (oi >= 0) {
                boolean eos = (bi.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                long pts = bi.presentationTimeUs;
                converted = false;
                if (bi.size == 0 && eos) {
                    dec.releaseOutputBuffer(oi, false);
                    outEos = true;
                    return -1;
                }
                if (pts + 60000 >= mtUs) {
                    Image img = dec.getOutputImage(oi);
                    if (img != null) {
                        convert(img, outArr);
                        img.close();
                        converted = true;
                    }
                }
                dec.releaseOutputBuffer(oi, false);
                if (eos) outEos = true;
                return pts;
            }
            waited += 10;
            if (waited > 20000) throw new Exception("overlay video: decoder stalled");
        }
    }

    /** YUV -> ARGB, straight into the size the overlay is shown at (nearest sample), with rotation. */
    private void convert(Image img, int[] out) {
        Image.Plane[] pl = img.getPlanes();
        ByteBuffer yb = pl[0].getBuffer(), ub = pl[1].getBuffer(), vbuf = pl[2].getBuffer();
        int ys = pl[0].getRowStride();
        int us = pl[1].getRowStride(), up = pl[1].getPixelStride();
        int vs = pl[2].getRowStride(), vp = pl[2].getPixelStride();
        Rect cr = img.getCropRect();
        int cw = cr.width(), ch = cr.height(), x0 = cr.left, y0 = cr.top;
        boolean swap = (srcRot == 90 || srcRot == 270);
        int dW = swap ? ch : cw, dH = swap ? cw : ch;
        for (int y = 0; y < dh; y++) {
            int v = (int) (((long) y * 2 + 1) * dH / (2L * dh));
            if (v >= dH) v = dH - 1;
            for (int x = 0; x < dw; x++) {
                int u = (int) (((long) x * 2 + 1) * dW / (2L * dw));
                if (u >= dW) u = dW - 1;
                int sx, sy;
                switch (srcRot) {
                    case 90:
                        sx = v;
                        sy = ch - 1 - u;
                        break;
                    case 180:
                        sx = cw - 1 - u;
                        sy = ch - 1 - v;
                        break;
                    case 270:
                        sx = cw - 1 - v;
                        sy = u;
                        break;
                    default:
                        sx = u;
                        sy = v;
                        break;
                }
                int gx = x0 + sx, gy = y0 + sy;
                int Y = yb.get(gy * ys + gx) & 0xFF;
                int U = ub.get((gy >> 1) * us + (gx >> 1) * up) & 0xFF;
                int V = vbuf.get((gy >> 1) * vs + (gx >> 1) * vp) & 0xFF;
                int yy = Y - 16;
                if (yy < 0) yy = 0;
                int uu = U - 128, vv = V - 128;
                int r, g, b;
                if (hd) {
                    r = (1192 * yy + 1836 * vv) >> 10;
                    g = (1192 * yy - 218 * uu - 546 * vv) >> 10;
                    b = (1192 * yy + 2163 * uu) >> 10;
                } else {
                    r = (1192 * yy + 1634 * vv) >> 10;
                    g = (1192 * yy - 401 * uu - 832 * vv) >> 10;
                    b = (1192 * yy + 2066 * uu) >> 10;
                }
                if (r < 0) r = 0;
                else if (r > 255) r = 255;
                if (g < 0) g = 0;
                else if (g > 255) g = 255;
                if (b < 0) b = 0;
                else if (b > 255) b = 255;
                out[y * dw + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
    }

    /** Producer thread: decode in order, convert, hand the frames over through a small queue. */
    private void produce() {
        int[] arr = null;
        try {
            open();
            for (int i = 0; i < QN + 3; i++) free.add(new int[dw * dh]);
            while (!stop && !outEos) {
                if (arr == null) {
                    while (!stop && arr == null) arr = free.poll(50, TimeUnit.MILLISECONDS);
                    if (stop) break;
                }
                long pts = decodeOne(target, arr);
                if (pts < 0 || !converted) continue;
                Fr f = new Fr(arr, pts);
                arr = null;
                while (!stop && !queue.offer(f, 50, TimeUnit.MILLISECONDS)) {
                }
            }
            if (!stop) {
                Fr end = new Fr(null, Long.MAX_VALUE);
                while (!stop && !queue.offer(end, 50, TimeUnit.MILLISECONDS)) {
                }
            }
        } catch (Exception e) {
            if (!stop) err = e;
        }
    }

    private void startWorker() {
        stop = false;
        err = null;
        ended = false;
        showFr = null;
        pendFr = null;
        queue.clear();
        free.clear();
        inEos = false;
        outEos = false;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                produce();
            }
        }, "overlay-video-decoder");
        worker.start();
    }

    private void stopWorker() {
        stop = true;
        if (worker != null) {
            try {
                worker.join(3000);
            } catch (InterruptedException ignore) {
            }
            worker = null;
        }
        try {
            if (dec != null) {
                dec.stop();
                dec.release();
            }
        } catch (Exception ignore) {
        }
        try {
            if (ex != null) ex.release();
        } catch (Exception ignore) {
        }
        dec = null;
        ex = null;
    }

    private void advance(long mt) throws Exception {
        target = mt;
        if (worker == null) startWorker();
        if (showFr != null && mt + 100000 < showFr.pts) {
            stopWorker();
            startWorker();
        }
        for (; ; ) {
            if (pendFr != null) {
                if (pendFr.pts <= mt) {
                    if (showFr != null) free.offer(showFr.px);
                    showFr = pendFr;
                    pendFr = null;
                    frameNo++;
                    continue;
                }
                break;
            }
            if (ended) break;
            Fr f = null;
            long waited = 0;
            while (f == null) {
                if (err != null) throw err;
                f = queue.poll(20, TimeUnit.MILLISECONDS);
                waited += 20;
                if (f == null && waited > 20000) throw new Exception("overlay video: decoder stalled");
            }
            if (f.px == null) {
                ended = true;
                break;
            }
            pendFr = f;
        }
    }

    @Override
    public Bitmap getBitmap(long presentationTimeUs) throws VideoFrameProcessingException {
        FastRenderPlugin.waitIfPaused();
        presentationTimeUs += offUs;
        long t0 = System.nanoTime();
        try {
            if (presentationTimeUs >= toUs && dec != null) closeDecoder(); // v16: free the decoder as soon as this part is over (many looped parts)
            if (presentationTimeUs < fromUs || presentationTimeUs >= toUs) return blank;
            long mt = presentationTimeUs - fromUs + inUs;
            if (durUs > 0 && mt > durUs) mt = durUs;
            advance(mt);
            if (showFr == null) return blank;
            if (frameNo == lastFrameNo && last != null) return last;
            vb.setPixels(showFr.px, 0, dw, 0, 0, dw, dh);
            Bitmap out = ring[ri];
            if (out == null) {
                out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
                ring[ri] = out;
            }
            ri = (ri + 1) % ring.length;
            out.eraseColor(Color.TRANSPARENT);
            Canvas c = new Canvas(out);
            c.translate(W * px / 100f, H * py / 100f);
            c.rotate(rotDeg);
            c.drawBitmap(vb, -dw / 2f, -dh / 2f, paint);
            last = out;
            lastFrameNo = frameNo;
            return out;
        } catch (Exception e) {
            throw new VideoFrameProcessingException("overlay video: " + e, e);
        } finally {
            spentNs += System.nanoTime() - t0;
        }
    }

    /** Time (ms) the render spent inside this overlay (includes waiting for the decoder). */
    public long spentMs() {
        return spentNs / 1000000L;
    }

    public void closeDecoder() {
        stopWorker();
    }
}
