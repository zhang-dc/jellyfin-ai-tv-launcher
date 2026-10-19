package com.optimisticat.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Layout;
import android.widget.TextView;

/**
 * 带黑色描边的字幕文字：先拿排好版的 Layout 用描边画笔画一遍，再让 TextView 正常画一遍填充。
 * 系统 TextView 只有阴影没有描边，亮背景上白字会看不清 —— 无底样式全靠这一圈描边。
 *
 * 不在 onDraw 里调 setTextColor 切颜色：那会 invalidate，自己触发自己无限重绘。
 * Layout.draw 用的就是 TextView 的那支画笔，直接改画笔的颜色和样式，画完 super.onDraw 会把颜色设回去。
 */
final class OutlineTextView extends TextView {

    private float strokeWidth = 0;
    private int strokeColor = 0xFF000000;

    OutlineTextView(Context ctx) {
        super(ctx);
    }

    /** 0 = 不描边。 */
    void setOutline(float widthPx, int color) {
        strokeWidth = widthPx;
        strokeColor = color;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Layout layout = getLayout();
        if (strokeWidth > 0 && layout != null) {
            Paint p = getPaint();
            canvas.save();
            canvas.translate(getCompoundPaddingLeft(), getExtendedPaddingTop());
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeWidth(strokeWidth);
            p.setColor(strokeColor);   // 阴影留着：TextView 只在 setShadowLayer 时设一次，这里清掉就再也回不来了
            layout.draw(canvas);
            canvas.restore();
            p.setStyle(Paint.Style.FILL);
            p.setStrokeWidth(0);
        }
        super.onDraw(canvas);   // 按正常文字颜色（和阴影）填充
    }
}
