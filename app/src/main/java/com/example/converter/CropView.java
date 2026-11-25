package com.example.converter;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/** Image preview with a draggable crop rectangle. All crop values are in ORIGINAL image pixels. */
public class CropView extends View {
    public interface Listener { void onCropChanged(); }

    public Listener listener;
    Bitmap bmp;
    int iw = 1, ih = 1;
    float cx, cy, cw = 1, ch = 1;
    float aspect = 0f; // width / height, 0 = free

    final Paint pBmp = new Paint(Paint.FILTER_BITMAP_FLAG);
    final Paint pDim = new Paint();
    final Paint pLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint pGrid = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint pHandle = new Paint(Paint.ANTI_ALIAS_FLAG);
    final RectF dst = new RectF();
    float scale = 1f;
    int mode = 0; // 0 none, 1 move, 2 TL, 3 TR, 4 BL, 5 BR
    float lastX, lastY, ax, ay;
    final float dens;

    public CropView(Context c) {
        super(c);
        dens = c.getResources().getDisplayMetrics().density;
        pDim.setColor(0xAA000000);
        pLine.setColor(Color.WHITE);
        pLine.setStyle(Paint.Style.STROKE);
        pLine.setStrokeWidth(2f * dens);
        pGrid.setColor(0x88FFFFFF);
        pGrid.setStyle(Paint.Style.STROKE);
        pGrid.setStrokeWidth(1f * dens);
        pHandle.setColor(Color.WHITE);
        setBackgroundColor(0xFF222222);
    }

    public void setImage(Bitmap b, int w, int h) {
        bmp = b;
        iw = Math.max(1, w);
        ih = Math.max(1, h);
        cx = 0; cy = 0; cw = iw; ch = ih;
        invalidate();
    }

    /** Rotate the preview image 90 degrees clockwise; the crop rectangle rotates with it. */
    public void rotateCW() {
        if (bmp == null) return;
        Matrix m = new Matrix();
        m.postRotate(90);
        bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        float ox = cx, oy = cy, ow = cw, oh = ch;
        int oiw = iw, oih = ih;
        iw = oih;
        ih = oiw;
        cw = oh;
        ch = ow;
        cx = clamp(oih - (oy + oh), 0, iw - cw);
        cy = clamp(ox, 0, ih - ch);
        changed();
    }

    public int[] getCrop() {
        return new int[]{Math.round(cx), Math.round(cy), Math.round(cw), Math.round(ch)};
    }

    static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    void changed() {
        invalidate();
        if (listener != null) listener.onCropChanged();
    }

    public void setCrop(float x, float y, float w, float h) {
        w = clamp(w, 1, iw);
        h = clamp(h, 1, ih);
        cw = w; ch = h;
        cx = clamp(x, 0, iw - cw);
        cy = clamp(y, 0, ih - ch);
        changed();
    }

    public void setW(float w) {
        w = clamp(w, 1, iw);
        float h = ch;
        if (aspect > 0) {
            h = w / aspect;
            if (h > ih) { h = ih; w = h * aspect; }
            if (h < 1) { h = 1; w = aspect; }
        }
        setCrop(cx, cy, w, h);
    }

    public void setH(float h) {
        h = clamp(h, 1, ih);
        float w = cw;
        if (aspect > 0) {
            w = h * aspect;
            if (w > iw) { w = iw; h = w / aspect; }
            if (w < 1) { w = 1; h = 1 / aspect; }
        }
        setCrop(cx, cy, w, h);
    }

    public void setX(float x) { setCrop(x, cy, cw, ch); }

    public void setY(float y) { setCrop(cx, y, cw, ch); }

    public void setAspect(float r) {
        aspect = r;
        if (r > 0) {
            float w = cw, h = w / r;
            if (h > ih) { h = ih; w = h * r; }
            if (w > iw) { w = iw; h = w / r; }
            float ncx = cx + cw / 2f - w / 2f, ncy = cy + ch / 2f - h / 2f;
            setCrop(ncx, ncy, w, h);
        } else {
            changed();
        }
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (bmp == null) return;
        float vw = getWidth(), vh = getHeight();
        scale = Math.min(vw / iw, vh / ih);
        float dw = iw * scale, dh = ih * scale;
        dst.set((vw - dw) / 2f, (vh - dh) / 2f, (vw + dw) / 2f, (vh + dh) / 2f);
        c.drawBitmap(bmp, null, dst, pBmp);
        float l = dst.left + cx * scale, t = dst.top + cy * scale;
        float r = l + cw * scale, b = t + ch * scale;
        c.drawRect(dst.left, dst.top, dst.right, t, pDim);
        c.drawRect(dst.left, b, dst.right, dst.bottom, pDim);
        c.drawRect(dst.left, t, l, b, pDim);
        c.drawRect(r, t, dst.right, b, pDim);
        float w3 = (r - l) / 3f, h3 = (b - t) / 3f;
        c.drawLine(l + w3, t, l + w3, b, pGrid);
        c.drawLine(l + 2 * w3, t, l + 2 * w3, b, pGrid);
        c.drawLine(l, t + h3, r, t + h3, pGrid);
        c.drawLine(l, t + 2 * h3, r, t + 2 * h3, pGrid);
        c.drawRect(l, t, r, b, pLine);
        float hr = 8f * dens;
        c.drawCircle(l, t, hr, pHandle);
        c.drawCircle(r, t, hr, pHandle);
        c.drawCircle(l, b, hr, pHandle);
        c.drawCircle(r, b, hr, pHandle);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (bmp == null) return false;
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                getParent().requestDisallowInterceptTouchEvent(true);
                float l = dst.left + cx * scale, t = dst.top + cy * scale;
                float r = l + cw * scale, b = t + ch * scale;
                float tr = 32f * dens;
                mode = 0;
                float best = tr * tr;
                float[][] pts = {{l, t}, {r, t}, {l, b}, {r, b}};
                for (int i = 0; i < 4; i++) {
                    float dx = x - pts[i][0], dy = y - pts[i][1];
                    float d = dx * dx + dy * dy;
                    if (d < best) { best = d; mode = 2 + i; }
                }
                if (mode == 0 && x >= l && x <= r && y >= t && y <= b) mode = 1;
                if (mode >= 2) {
                    // anchor = opposite corner, in image pixels
                    ax = (mode == 2 || mode == 4) ? cx + cw : cx;
                    ay = (mode == 2 || mode == 3) ? cy + ch : cy;
                }
                lastX = x; lastY = y;
                return mode != 0;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mode == 1) {
                    float dx = (x - lastX) / scale, dy = (y - lastY) / scale;
                    cx = clamp(cx + dx, 0, iw - cw);
                    cy = clamp(cy + dy, 0, ih - ch);
                    lastX = x; lastY = y;
                    changed();
                } else if (mode >= 2) {
                    float px = clamp((x - dst.left) / scale, 0, iw);
                    float py = clamp((y - dst.top) / scale, 0, ih);
                    float nw = Math.max(8f, Math.abs(px - ax));
                    float nh = Math.max(8f, Math.abs(py - ay));
                    if (aspect > 0) {
                        if (nw / nh > aspect) nw = nh * aspect; else nh = nw / aspect;
                    }
                    float nx = px >= ax ? ax : ax - nw;
                    float ny = py >= ay ? ay : ay - nh;
                    cw = Math.min(nw, iw);
                    ch = Math.min(nh, ih);
                    cx = clamp(nx, 0, iw - cw);
                    cy = clamp(ny, 0, ih - ch);
                    changed();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mode = 0;
                return true;
        }
        return true;
    }
}
