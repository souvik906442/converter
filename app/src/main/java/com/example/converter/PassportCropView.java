package com.example.converter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * নির্দিষ্ট অনুপাতের ফ্রেম মাঝখানে থাকে। ছবি আঙুলে সরিয়ে (drag) ও জুম (pinch) করে
 * ফ্রেমের ভেতরে ঠিক করতে হয়। ফ্রেমের বাইরের অংশ কাটা পড়ে।
 */
public class PassportCropView extends View {
    private Bitmap bmp;
    private final Matrix imgMatrix = new Matrix();
    private final RectF frame = new RectF();
    private float aspect = 35f / 45f;

    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint shade = new Paint();
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float lastX, lastY;
    private boolean dragging;
    private final ScaleGestureDetector scaleDetector;

    public PassportCropView(Context c) {
        super(c);
        shade.setColor(0xAA000000);
        border.setColor(Color.WHITE);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(4f);
        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                imgMatrix.postScale(d.getScaleFactor(), d.getScaleFactor(), d.getFocusX(), d.getFocusY());
                limitScale();
                fixMatrix();
                invalidate();
                return true;
            }
        });
    }

    public void setBitmap(Bitmap b) {
        bmp = b;
        initMatrix();
        invalidate();
    }

    public void setAspect(int w, int h) {
        aspect = w / (float) h;
        computeFrame();
        initMatrix();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        computeFrame();
        initMatrix();
    }

    private void computeFrame() {
        if (getWidth() == 0 || getHeight() == 0) return;
        float pad = 32f;
        float availW = getWidth() - 2 * pad;
        float availH = getHeight() - 2 * pad;
        float fw, fh;
        if (availW / availH > aspect) {
            fh = availH;
            fw = fh * aspect;
        } else {
            fw = availW;
            fh = fw / aspect;
        }
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        frame.set(cx - fw / 2, cy - fh / 2, cx + fw / 2, cy + fh / 2);
    }

    private void initMatrix() {
        if (bmp == null || frame.width() <= 0f) return;
        float s = Math.max(frame.width() / bmp.getWidth(), frame.height() / bmp.getHeight());
        imgMatrix.reset();
        imgMatrix.postScale(s, s);
        imgMatrix.postTranslate(frame.centerX() - bmp.getWidth() * s / 2f,
                frame.centerY() - bmp.getHeight() * s / 2f);
    }

    private float currentScale() {
        float[] v = new float[9];
        imgMatrix.getValues(v);
        return v[Matrix.MSCALE_X];
    }

    private float minScale() {
        if (bmp == null) return 1f;
        return Math.max(frame.width() / bmp.getWidth(), frame.height() / bmp.getHeight());
    }

    private void limitScale() {
        float s = currentScale();
        float mx = minScale() * 8f;
        if (s > mx) imgMatrix.postScale(mx / s, mx / s, frame.centerX(), frame.centerY());
    }

    /** ছবি যেন সবসময় পুরো ফ্রেম ঢেকে রাখে */
    private void fixMatrix() {
        if (bmp == null) return;
        float s = currentScale();
        float mn = minScale();
        if (s < mn) imgMatrix.postScale(mn / s, mn / s, frame.centerX(), frame.centerY());
        RectF r = new RectF(0, 0, bmp.getWidth(), bmp.getHeight());
        imgMatrix.mapRect(r);
        float dx = 0f, dy = 0f;
        if (r.left > frame.left) dx = frame.left - r.left;
        if (r.right < frame.right) dx = frame.right - r.right;
        if (r.top > frame.top) dy = frame.top - r.top;
        if (r.bottom < frame.bottom) dy = frame.bottom - r.bottom;
        imgMatrix.postTranslate(dx, dy);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (bmp == null) return false;
        scaleDetector.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                lastX = e.getX();
                lastY = e.getY();
                dragging = true;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!scaleDetector.isInProgress() && e.getPointerCount() == 1) {
                    if (!dragging) {
                        lastX = e.getX();
                        lastY = e.getY();
                        dragging = true;
                    } else {
                        imgMatrix.postTranslate(e.getX() - lastX, e.getY() - lastY);
                        lastX = e.getX();
                        lastY = e.getY();
                        fixMatrix();
                        invalidate();
                    }
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
                dragging = false;
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                break;
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bmp == null) return;
        canvas.drawBitmap(bmp, imgMatrix, bmpPaint);
        float w = getWidth(), h = getHeight();
        canvas.drawRect(0, 0, w, frame.top, shade);
        canvas.drawRect(0, frame.bottom, w, h, shade);
        canvas.drawRect(0, frame.top, frame.left, frame.bottom, shade);
        canvas.drawRect(frame.right, frame.top, w, frame.bottom, shade);
        canvas.drawRect(frame, border);
    }

    /** ফ্রেমের ভেতরের অংশ outW x outH পিক্সেলে কেটে দেয় */
    public Bitmap getCrop(int outW, int outH) {
        return render(bmp, outW, outH);
    }

    /** মূল ছবির সাথে একই অনুপাতের আরেকটা bitmap (যেমন mask) একই জায়গা থেকে কাটে */
    public Bitmap getCropOf(Bitmap other, int outW, int outH) {
        return render(other, outW, outH);
    }

    private Bitmap render(Bitmap src, int outW, int outH) {
        if (bmp == null || src == null) return null;
        Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(Color.WHITE);
        c.scale(outW / frame.width(), outH / frame.height());
        c.translate(-frame.left, -frame.top);
        c.concat(imgMatrix);
        c.drawBitmap(src, null, new RectF(0, 0, bmp.getWidth(), bmp.getHeight()), bmpPaint);
        return out;
    }
}
