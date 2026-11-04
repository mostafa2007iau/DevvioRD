package ai.devvio.rd;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

/**
 * Draws the remote desktop.
 *
 * <p>Decoding happens off the UI thread (see {@link Decoder}) and frames are
 * pushed as immutable bitmaps. The view letterboxes the remote screen inside its
 * own bounds so touch coordinates map 1:1 onto the remote desktop.
 */
public class RemoteView extends View {

    private static final String TAG = "DevvioRD/view";

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();
    private final Rect src = new Rect();

    private volatile Bitmap frame;
    private int serverW = 1280;
    private int serverH = 800;

    private RemoteActivity session;
    private boolean mouseMode;

    // local cursor (mouse mode) in normalized remote coordinates
    private volatile float cx = 0.5f;
    private volatile float cy = 0.5f;
    private boolean showLocalCursor = true;

    private final Paint debugPaint = new Paint();

    // fps measurement
    private long fpsWindowStart;
    private int fpsCount;
    private volatile int measuredFps;

    private int lastDrawnW;
    private int lastDrawnH;
    private final RectF lastDst = new RectF();

    public RemoteView(Context c) {
        super(c);
        setBackgroundColor(Color.BLACK);
        setFocusable(true);
        setClickable(true);
        cursorPaint.setStyle(Paint.Style.FILL);
        cursorPaint.setColor(Color.WHITE);
        debugPaint.setColor(0x66FF0000);
        // No explicit layer type: drawing a bitmap into the window is already
        // GPU accelerated, and a hardware layer would copy every frame twice.
    }

    public void setSession(RemoteActivity s) {
        this.session = s;
    }

    public void setServerSize(int w, int h) {
        if (w > 0 && h > 0 && (w != serverW || h != serverH)) {
            serverW = w;
            serverH = h;
            postInvalidate();
        }
    }

    public int serverWidth() {
        return serverW;
    }

    public int serverHeight() {
        return serverH;
    }

    public void setMouseMode(boolean m) {
        mouseMode = m;
        postInvalidate();
    }

    public float cursorX() {
        return cx;
    }

    public float cursorY() {
        return cy;
    }

    public void moveLocalCursor(float dxPx, float dyPx) {
        // Normalise against the drawn image, not the raw view: otherwise the
        // cursor and the finger diverge by the letterbox margins.
        computeDst();
        float w = Math.max(1, dst.width());
        float h = Math.max(1, dst.height());
        cx = clamp01(cx + dxPx / w);
        cy = clamp01(cy + dyPx / h);
        postInvalidateOnAnimation();
    }

    public void serverCursor(float x, float y, boolean visible) {
        if (x >= 0 && y >= 0) {
            cx = clamp01(x);
            cy = clamp01(y);
        }
        showLocalCursor = visible;
        postInvalidateOnAnimation();
    }

    private static float clamp01(float v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    /** Releases the current frame bitmap (used when leaving the session). */
    public void clearFrame() {
        frame = null;
        postInvalidate();
    }

    public int measuredFps() {
        return measuredFps;
    }

    /** Called from the network thread with a decoded JPEG. */
    public void onFrame(final byte[] buf, final int off, final int len, final int w, final int h) {
        Decoder.INSTANCE.submit(buf, off, len, new Decoder.Callback() {
            @Override
            public void onBitmap(final Bitmap bmp) {
                if (bmp == null) {
                    return;
                }
                if (w > 0 && h > 0) {
                    serverW = w;
                    serverH = h;
                }
                // Note: the previous bitmap is intentionally NOT recycled here.
                // The UI thread may still be drawing it (or be mid-swap when the
                // GC collects without finalizing), and recycling a bitmap that
                // is being drawn crashes with "Canvas: trying to use a recycled
                // bitmap". Letting these short-lived ARGB_8888 frames be
                // collected by the GC is the safe trade-off.
                frame = bmp;
                countFrame();
                postInvalidateOnAnimation();
            }
        });
    }

    private void countFrame() {
        long now = System.currentTimeMillis();
        if (fpsWindowStart == 0) {
            fpsWindowStart = now;
        }
        fpsCount++;
        if (now - fpsWindowStart >= 1000) {
            measuredFps = (int) (fpsCount * 1000L / Math.max(1, now - fpsWindowStart));
            fpsCount = 0;
            fpsWindowStart = now;
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        lastDrawnW = w;
        lastDrawnH = h;
    }

    /** Computes the letterboxed destination rectangle. */
    private void computeDst() {
        int vw = getWidth();
        int vh = getHeight();
        if (vw == 0 || vh == 0) {
            dst.set(0, 0, 1, 1);
            return;
        }
        float srcAspect = (float) serverW / Math.max(1, serverH);
        float viewAspect = (float) vw / vh;
        if (viewAspect > srcAspect) {
            // pillarbox
            float w = vh * srcAspect;
            float left = (vw - w) / 2f;
            dst.set(left, 0, left + w, vh);
        } else {
            // letterbox
            float h = vw / srcAspect;
            float top = (vh - h) / 2f;
            dst.set(0, top, vw, top + h);
        }
        if (!dst.equals(lastDst)) {
            lastDst.set(dst);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Bitmap b = frame;
        if (b == null || b.isRecycled()) {
            return;
        }
        computeDst();
        src.set(0, 0, b.getWidth(), b.getHeight());
        canvas.drawBitmap(b, src, dst, paint);

        if (mouseMode && showLocalCursor) {
            drawCursor(canvas);
        }
    }

    private void drawCursor(Canvas canvas) {
        float x = dst.left + cx * dst.width();
        float y = dst.top + cy * dst.height();
        float s = Ui.dp(getContext(), 1.1f);
        android.graphics.Path p = new android.graphics.Path();
        p.moveTo(x, y);
        p.lineTo(x, y + 17 * s);
        p.lineTo(x + 4.4f * s, y + 12.8f * s);
        p.lineTo(x + 7.4f * s, y + 19.4f * s);
        p.lineTo(x + 10.2f * s, y + 18.1f * s);
        p.lineTo(x + 7.2f * s, y + 11.7f * s);
        p.lineTo(x + 12.4f * s, y + 11.4f * s);
        p.close();
        Paint outline = new Paint(cursorPaint);
        outline.setColor(0xCC000000);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(3.2f * s);
        canvas.drawPath(p, outline);
        cursorPaint.setColor(Color.WHITE);
        canvas.drawPath(p, cursorPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (session != null) {
            session.onLocalTouch(event);
        }
        return true;
    }

    /** Maps a view coordinate to remote normalized coordinates. */
    public float[] toRemote(float x, float y) {
        computeDst();
        float nx = (x - dst.left) / Math.max(1, dst.width());
        float ny = (y - dst.top) / Math.max(1, dst.height());
        return new float[]{clamp01(nx), clamp01(ny)};
    }

    /** True when a tap landed outside the letterboxed image. */
    public boolean isOutsideImage(float x, float y) {
        computeDst();
        return x < dst.left || x > dst.right || y < dst.top || y > dst.bottom;
    }

    // ------------------------------------------------------------------

    /**
     * Single background decoder with latest-wins semantics: while a decode is
     * in flight, incoming frames are coalesced so the UI never lags behind live
     * video (dropping stale frames is the correct behaviour for remote desktop).
     */
    static final class Decoder {
        static final Decoder INSTANCE = new Decoder();

        interface Callback {
            void onBitmap(Bitmap b);
        }

        private final Object lock = new Object();
        private byte[] pending;
        private int pendingOff;
        private int pendingLen;
        private Callback pendingCb;
        private boolean busy;

        private final BitmapFactory.Options opts = new BitmapFactory.Options();
        private final Matrix matrix = new Matrix();

        private Decoder() {
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            opts.inMutable = false;
        }

        void submit(byte[] buf, int off, int len, Callback cb) {
            synchronized (lock) {
                pending = buf;
                pendingOff = off;
                pendingLen = len;
                pendingCb = cb;
                if (busy) {
                    return;
                }
                busy = true;
            }
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    drain();
                }
            }, "devviord-decode");
            t.setPriority(Thread.NORM_PRIORITY + 1);
            t.setDaemon(true);
            t.start();
        }

        private void drain() {
            while (true) {
                byte[] buf;
                int off;
                int len;
                Callback cb;
                synchronized (lock) {
                    if (pending == null) {
                        busy = false;
                        return;
                    }
                    buf = pending;
                    off = pendingOff;
                    len = pendingLen;
                    cb = pendingCb;
                    pending = null;
                    pendingCb = null;
                }
                Bitmap bmp = null;
                try {
                    bmp = BitmapFactory.decodeByteArray(buf, off, len, opts);
                } catch (Throwable t) {
                    Log.w(TAG, "decode failed", t);
                }
                if (bmp != null && cb != null) {
                    cb.onBitmap(bmp);
                } else if (bmp != null) {
                    bmp.recycle();
                }
            }
        }
    }
}
