package com.optimisticat.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * 线框图标，Canvas 现画：不带图片资源，任何分辨率都清楚，颜色随焦点切换（setColor）。
 * 坐标按 24x24 的网格设计（和常见图标库一致），画的时候按实际尺寸缩放。
 */
final class Icons extends Drawable {

    enum Kind { MIC, SEARCH, DOWNLOAD, INPUT, SETTINGS, CHAT, SEND, PAUSE, PLAY, TRASH, SPARK }

    private final Kind kind;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();

    Icons(Kind kind, int color) {
        this.kind = kind;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
        setColor(color);
    }

    void setColor(int color) {
        stroke.setColor(color);
        fill.setColor(color);
        invalidateSelf();
    }

    @Override
    public void draw(Canvas c) {
        float s = Math.min(getBounds().width(), getBounds().height()) / 24f;
        c.save();
        c.translate(getBounds().left + (getBounds().width() - 24 * s) / 2, getBounds().top + (getBounds().height() - 24 * s) / 2);
        c.scale(s, s);
        stroke.setStrokeWidth(2f);
        path.reset();
        switch (kind) {
            case MIC:
                r.set(9, 2, 15, 15);
                c.drawRoundRect(r, 3, 3, stroke);
                path.moveTo(5, 11); path.cubicTo(5, 15, 8, 18, 12, 18); path.cubicTo(16, 18, 19, 15, 19, 11);
                c.drawPath(path, stroke);
                c.drawLine(12, 18, 12, 22, stroke);
                break;
            case SEARCH:
                c.drawCircle(10.5f, 10.5f, 6.5f, stroke);
                c.drawLine(15.5f, 15.5f, 21, 21, stroke);
                break;
            case DOWNLOAD:
                c.drawLine(12, 3, 12, 15, stroke);
                path.moveTo(7, 10); path.lineTo(12, 15); path.lineTo(17, 10);
                c.drawPath(path, stroke);
                path.reset();
                path.moveTo(4, 17); path.lineTo(4, 20); path.lineTo(20, 20); path.lineTo(20, 17);
                c.drawPath(path, stroke);
                break;
            case INPUT:
                r.set(3, 5, 21, 17);
                c.drawRoundRect(r, 2, 2, stroke);
                c.drawLine(8, 21, 16, 21, stroke);
                c.drawLine(12, 17, 12, 21, stroke);
                break;
            case SETTINGS:
                c.drawCircle(12, 12, 3, stroke);
                for (int i = 0; i < 8; i++) {   // 八个齿
                    double a = Math.PI / 4 * i;
                    float x1 = 12 + (float) Math.cos(a) * 6.5f, y1 = 12 + (float) Math.sin(a) * 6.5f;
                    float x2 = 12 + (float) Math.cos(a) * 9.5f, y2 = 12 + (float) Math.sin(a) * 9.5f;
                    c.drawLine(x1, y1, x2, y2, stroke);
                }
                c.drawCircle(12, 12, 6.5f, stroke);
                break;
            case CHAT:
                path.moveTo(4, 5); path.lineTo(20, 5); path.lineTo(20, 16); path.lineTo(10, 16); path.lineTo(6, 20);
                path.lineTo(6, 16); path.lineTo(4, 16); path.close();
                c.drawPath(path, stroke);
                c.drawCircle(9, 10.5f, 1.1f, fill);
                c.drawCircle(12, 10.5f, 1.1f, fill);
                c.drawCircle(15, 10.5f, 1.1f, fill);
                break;
            case SEND:
                path.moveTo(4, 12); path.lineTo(20, 4); path.lineTo(14, 20); path.lineTo(11, 13); path.close();
                c.drawPath(path, stroke);
                break;
            case PAUSE:
                c.drawLine(9, 6, 9, 18, stroke);
                c.drawLine(15, 6, 15, 18, stroke);
                break;
            case PLAY:
                path.moveTo(8, 5); path.lineTo(19, 12); path.lineTo(8, 19); path.close();
                c.drawPath(path, stroke);
                break;
            case TRASH:
                c.drawLine(4, 7, 20, 7, stroke);
                path.moveTo(6, 7); path.lineTo(7, 20); path.lineTo(17, 20); path.lineTo(18, 7);
                c.drawPath(path, stroke);
                c.drawLine(10, 4, 14, 4, stroke);
                break;
            case SPARK:   // Agent 头像：四角星
                path.moveTo(12, 2); path.quadTo(13, 11, 22, 12); path.quadTo(13, 13, 12, 22);
                path.quadTo(11, 13, 2, 12); path.quadTo(11, 11, 12, 2); path.close();
                c.drawPath(path, fill);
                break;
        }
        c.restore();
    }

    @Override public void setAlpha(int alpha) { stroke.setAlpha(alpha); fill.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter cf) { stroke.setColorFilter(cf); fill.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
