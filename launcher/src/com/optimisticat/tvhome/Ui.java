package com.optimisticat.tvhome;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** 各页面共用的尺寸和控件样式，和首页保持同一套视觉（纯黑底、白色描边焦点、1920 宽设计稿）。 */
final class Ui {
    static final int BG = 0xFF000000;
    static final int CARD = 0xFF1C1C1E;
    static final int TEXT_DIM = 0xFF8E8E93;
    static final int ACCENT = 0xFF00A4DC;   // Jellyfin 蓝，进度条 / 录音指示

    /** 设计稿按 1920x1080 像素出，按屏宽等比换算 —— 与屏幕密度无关，模拟器和电视看到的一样。 */
    static int px(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().widthPixels / 1920f);
    }

    static TextView text(Activity a, String s, int size, int color, Typeface tf) {
        TextView v = new TextView(a);
        v.setText(s);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(a, size));
        v.setTextColor(color);
        v.setTypeface(tf);
        return v;
    }

    static GradientDrawable rounded(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    static void fullscreen(Activity a) {
        a.getWindow().getDecorView().setBackgroundColor(BG);
        a.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    /** 胶囊按钮：未选中半透明白底，选中纯白底黑字。 */
    static TextView pill(Activity a, String label, View.OnClickListener onClick) {
        TextView v = text(a, label, 24, Color.WHITE, Typeface.DEFAULT);
        v.setPadding(px(a, 28), px(a, 12), px(a, 28), px(a, 12));
        v.setFocusable(true);
        v.setFocusableInTouchMode(true);
        v.setClickable(true);
        noHighlight(v);
        v.setOnClickListener(onClick);
        GradientDrawable idle = rounded(0x33FFFFFF, px(a, 28));
        GradientDrawable focused = rounded(0xFFFFFFFF, px(a, 28));
        v.setBackground(idle);
        v.setOnFocusChangeListener((view, has) -> {
            view.setBackground(has ? focused : idle);
            ((TextView) view).setTextColor(has ? Color.BLACK : Color.WHITE);
        });
        return v;
    }

    /** 列表行 / 卡片的焦点效果：白色描边 + 轻微放大。 */
    static void focusRing(Activity a, View v, float scale) {
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(px(a, 14));
        ring.setStroke(px(a, 4), Color.WHITE);
        v.setFocusable(true);
        v.setFocusableInTouchMode(true);
        v.setClickable(true);
        noHighlight(v);
        v.setOnFocusChangeListener((view, has) -> {
            view.animate().scaleX(has ? scale : 1f).scaleY(has ? scale : 1f).setDuration(140).start();
            view.setForeground(has ? ring : null);
        });
    }

    static String size(long bytes) {
        if (bytes >= 1L << 30) return String.format(java.util.Locale.CHINA, "%.1f GB", bytes / (double) (1L << 30));
        if (bytes >= 1L << 20) return String.format(java.util.Locale.CHINA, "%.0f MB", bytes / (double) (1L << 20));
        return Math.max(0, bytes / 1024) + " KB";
    }

    static String eta(long seconds) {
        if (seconds <= 0 || seconds >= 8640000) return "";
        if (seconds < 60) return "剩 " + seconds + " 秒";
        if (seconds < 3600) return "剩 " + (seconds / 60) + " 分钟";
        return "剩 " + (seconds / 3600) + " 小时 " + (seconds % 3600 / 60) + " 分";
    }

    /**
     * 关掉安卓 8.0+ 给可聚焦控件自动加的灰色方块高亮 —— 我们每个控件都有自己的选中样式，
     * 叠上它就是圆按钮背后多一块方底（2026-10-19 MuMu 截图里看到的）。
     */
    static void noHighlight(View v) {
        if (android.os.Build.VERSION.SDK_INT >= 26) v.setDefaultFocusHighlightEnabled(false);
    }

    // ---------------------------------------------------------------- 组件

    /**
     * 圆形图标按钮（Apple TV 风格）：平时只露半透明圆底 + 白色线框图标，选中变白底黑图标并放大，
     * 文字说明只在选中时淡入显示在下方 —— 顶栏五个按钮不再挤一排字。
     */
    static LinearLayout iconButton(Activity a, Icons.Kind kind, String label, View.OnClickListener onClick) {
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setClipChildren(false);
        FrameLayout circle = new FrameLayout(a);
        circle.setFocusable(true);
        circle.setFocusableInTouchMode(true);
        circle.setClickable(true);
        circle.setOnClickListener(onClick);
        circle.setContentDescription(label);
        noHighlight(circle);
        GradientDrawable idle = new GradientDrawable();
        idle.setShape(GradientDrawable.OVAL);
        idle.setColor(0x26FFFFFF);
        GradientDrawable on = new GradientDrawable();
        on.setShape(GradientDrawable.OVAL);
        on.setColor(Color.WHITE);
        circle.setBackground(idle);
        Icons icon = new Icons(kind, 0xE6FFFFFF);
        ImageView iv = new ImageView(a);
        iv.setImageDrawable(icon);
        circle.addView(iv, new FrameLayout.LayoutParams(px(a, 34), px(a, 34), Gravity.CENTER));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(px(a, 76), px(a, 76));
        cp.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(circle, cp);
        TextView cap = text(a, label, 20, Color.WHITE, Typeface.DEFAULT);
        cap.setAlpha(0f);
        cap.setPadding(0, px(a, 10), 0, 0);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(cap, tp);
        circle.setOnFocusChangeListener((v, has) -> {
            v.setBackground(has ? on : idle);
            icon.setColor(has ? Color.BLACK : 0xE6FFFFFF);
            v.animate().scaleX(has ? 1.12f : 1f).scaleY(has ? 1.12f : 1f).setDuration(140).start();
            cap.animate().alpha(has ? 1f : 0f).setDuration(140).start();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(a, 110), -2);
        col.setLayoutParams(lp);
        return col;
    }

    /** 小标签：清晰度、做种数、状态这类。 */
    static TextView chip(Activity a, String s, int fg, int bg) {
        TextView v = text(a, s, 20, fg, Typeface.DEFAULT_BOLD);
        v.setPadding(px(a, 14), px(a, 4), px(a, 14), px(a, 5));
        v.setBackground(rounded(bg, px(a, 8)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = px(a, 10);
        v.setLayoutParams(lp);
        return v;
    }

    /** 子页面标题栏：左边圆底图标 + 大标题 + 灰色说明。 */
    static LinearLayout pageHeader(Activity a, Icons.Kind kind, String title, String subtitle) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout badge = new FrameLayout(a);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF00A4DC, 0xFF7B5CFF});
        bg.setShape(GradientDrawable.OVAL);
        badge.setBackground(bg);
        ImageView iv = new ImageView(a);
        iv.setImageDrawable(new Icons(kind, Color.WHITE));
        badge.addView(iv, new FrameLayout.LayoutParams(px(a, 36), px(a, 36), Gravity.CENTER));
        row.addView(badge, new LinearLayout.LayoutParams(px(a, 72), px(a, 72)));
        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(px(a, 24), 0, 0, 0);
        texts.addView(text(a, title, 44, Color.WHITE, Typeface.DEFAULT_BOLD));
        TextView sub = text(a, subtitle, 22, TEXT_DIM, Typeface.DEFAULT);
        sub.setPadding(0, px(a, 4), 0, 0);
        texts.addView(sub);
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    static ProgressBar spinner(Activity a) {
        ProgressBar p = new ProgressBar(a);
        p.setIndeterminate(true);
        p.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        p.setLayoutParams(new LinearLayout.LayoutParams(px(a, 40), px(a, 40)));
        return p;
    }

    interface Choice {
        void picked(int index);
    }

    /**
     * 深色弹窗：标题 + 说明 + 一列选项按钮（第一个默认选中）。替换系统 AlertDialog 的白底样式。
     * danger 里的下标显示成红色（删除类操作）。
     */
    static android.app.Dialog choose(Activity a, String title, String message, String[] options, java.util.Set<Integer> danger, Choice onPick) {
        android.app.Dialog d = new android.app.Dialog(a);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(px(a, 48), px(a, 40), px(a, 48), px(a, 36));
        panel.setBackground(rounded(0xFF1C1C1E, px(a, 28)));
        TextView t = text(a, title, 32, Color.WHITE, Typeface.DEFAULT_BOLD);
        t.setMaxLines(3);
        t.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        panel.addView(t);
        if (message != null && !message.isEmpty()) {
            TextView m = text(a, message, 24, TEXT_DIM, Typeface.DEFAULT);
            m.setPadding(0, px(a, 14), 0, 0);
            m.setLineSpacing(px(a, 6), 1f);
            panel.addView(m);
        }
        View first = null;
        for (int i = 0; i < options.length; i++) {
            final int idx = i;
            boolean red = danger != null && danger.contains(i);
            TextView b = text(a, options[i], 26, red ? 0xFFFF6B6B : Color.WHITE, Typeface.DEFAULT_BOLD);
            b.setGravity(Gravity.CENTER);
            b.setPadding(0, px(a, 18), 0, px(a, 18));
            GradientDrawable idle = rounded(0x1AFFFFFF, px(a, 18));
            GradientDrawable on = rounded(red ? 0xFFFF453A : Color.WHITE, px(a, 18));
            b.setBackground(idle);
            b.setFocusable(true);
            b.setFocusableInTouchMode(true);
            b.setClickable(true);
            noHighlight(b);
            b.setOnFocusChangeListener((v, has) -> {
                v.setBackground(has ? on : idle);
                ((TextView) v).setTextColor(has ? (red ? Color.WHITE : Color.BLACK) : (red ? 0xFFFF6B6B : Color.WHITE));
            });
            b.setOnClickListener(v -> { d.dismiss(); onPick.picked(idx); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = px(a, i == 0 ? 32 : 14);
            panel.addView(b, lp);
            if (first == null) first = b;
        }
        d.setContentView(panel, new android.view.ViewGroup.LayoutParams(px(a, 760), -2));
        android.view.Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0.7f);
        }
        final View focus = first;
        d.setOnShowListener(x -> { if (focus != null) focus.requestFocus(); });
        d.show();
        return d;
    }
}
