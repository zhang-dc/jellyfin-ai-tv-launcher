package com.optimisticat.tvhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 首页的影院式大广告位（设计稿 docs/ui-v2-design.md §1）：焦点停在哪部剧 / 哪部电影，全屏就铺它的剧照，
 * 左侧压暗叠 Logo（没有就大标题）、第几季第几集、评分 · 年份 · 时长 · 进度、简介。
 *
 * - 背景两张 ImageView 交替交叉淡入，换图不闪黑
 * - 焦点停稳 250ms 才去取详情和大图（防抖），快速划过不会每张都请求
 * - 详情和图片都进内存缓存；剧集的剧照和 Logo 用所属剧的
 */
final class HeroView extends FrameLayout {

    private static final int DEBOUNCE_MS = 250, FADE_MS = 320;

    private final Activity a;
    private final Jellyfin jellyfin;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> images = new LruCache<String, Bitmap>(48 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private final LruCache<String, JSONObject> details = new LruCache<>(64);
    /** 取不到的图（没有 Logo / 没有剧照）也记下来，别每次都去撞 404 */
    private final java.util.Set<String> missing = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private final ImageView[] backs = new ImageView[2];
    private int front = 0;
    private final LinearLayout info;
    private final ImageView logo;
    private final TextView title, line1, line2, overview;
    private String shownId, wantedId;
    /** 当前的下翻程度，换图时按它定透明度，别一换图又亮回来 */
    private float fade = 0;

    HeroView(Activity a, Jellyfin jellyfin) {
        super(a);
        this.a = a;
        this.jellyfin = jellyfin;
        setBackgroundColor(Color.BLACK);
        for (int i = 0; i < 2; i++) {
            backs[i] = new ImageView(a);
            backs[i].setScaleType(ImageView.ScaleType.CENTER_CROP);
            backs[i].setAlpha(0f);
            addView(backs[i], new LayoutParams(-1, -1));
        }
        // 左→右压暗（放字）+ 下→上隐入黑底（下面是卡片行）+ 顶部一点压暗（时钟和按钮）
        View left = new View(a);
        left.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xF2000000, 0xCC000000, 0x66000000, 0x00000000}));
        addView(left, new LayoutParams(px(1300), -1));
        View bottom = new View(a);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xFF000000, 0xFF000000, 0xCC000000, 0x00000000}));
        addView(bottom, new LayoutParams(-1, px(640), Gravity.BOTTOM));
        View top = new View(a);
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x99000000, 0x00000000}));
        addView(top, new LayoutParams(-1, px(220), Gravity.TOP));

        info = new LinearLayout(a);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(px(96), 0, 0, 0);
        logo = new ImageView(a);
        logo.setAdjustViewBounds(true);
        logo.setMaxWidth(px(620));
        logo.setMaxHeight(px(160));
        logo.setScaleType(ImageView.ScaleType.FIT_START);
        info.addView(logo, new LinearLayout.LayoutParams(-2, -2));
        title = Ui.text(a, "", 76, Color.WHITE, Typeface.DEFAULT_BOLD);
        title.setMaxWidth(px(1100));
        title.setMaxLines(2);
        title.setShadowLayer(px(10), 0, px(2), 0x99000000);
        info.addView(title, new LinearLayout.LayoutParams(-2, -2));
        line1 = Ui.text(a, "", 30, Color.WHITE, Typeface.DEFAULT_BOLD);
        line1.setPadding(0, px(26), 0, 0);
        line1.setShadowLayer(px(8), 0, px(1), 0x99000000);
        info.addView(line1);
        line2 = Ui.text(a, "", 24, 0xFFD8D8DC, Typeface.DEFAULT);
        line2.setPadding(0, px(10), 0, 0);
        line2.setShadowLayer(px(8), 0, px(1), 0x99000000);
        info.addView(line2);
        overview = Ui.text(a, "", 24, 0xFFC7C7CC, Typeface.DEFAULT);
        overview.setPadding(0, px(18), 0, 0);
        overview.setMaxWidth(px(920));
        overview.setMaxLines(3);
        overview.setLineSpacing(px(8), 1f);
        overview.setEllipsize(android.text.TextUtils.TruncateAt.END);
        overview.setShadowLayer(px(8), 0, px(1), 0x99000000);
        info.addView(overview, new LinearLayout.LayoutParams(-2, -2));
        LayoutParams ip = new LayoutParams(-1, -2, Gravity.TOP);
        ip.topMargin = px(120);
        addView(info, ip);
        info.setAlpha(0f);
    }

    /** 焦点停到一个条目上：防抖后换图换字。null = 保持现状（焦点在应用 / 按钮上）。 */
    void show(Jellyfin.Item it) {
        if (it == null || jellyfin == null) return;
        wantedId = it.id;
        ui.removeCallbacks(pending);
        pending = () -> load(it);
        ui.postDelayed(pending, DEBOUNCE_MS);
    }

    private Runnable pending;

    /** 不防抖、不走缓存地立即刷新（剧集详情页进来 / 看完回来时用，进度要是新的）。 */
    void showNow(String id) {
        if (jellyfin == null) return;
        Jellyfin.Item it = new Jellyfin.Item();
        it.id = id;
        wantedId = id;
        details.remove(id);
        shownId = null;
        load(it);
    }

    /** 往下翻时：文字淡出、剧照压暗（f = 0 顶部 … 1 翻到底）。 */
    void setScrollFade(float f) {
        fade = f;
        info.setTranslationY(-px(80) * f);
        if (shownId == null) return;
        info.animate().cancel();
        backs[front].animate().cancel();
        info.setAlpha(textAlpha());
        if (backs[front].getDrawable() != null) backs[front].setAlpha(backAlpha());
    }

    private float textAlpha() {
        return Math.max(0, 1 - fade * 1.6f);
    }

    private float backAlpha() {
        return 1 - 0.7f * fade;
    }

    private void load(Jellyfin.Item it) {
        if (it.id.equals(shownId)) return;
        io.execute(() -> {
            try {
                JSONObject d = details.get(it.id);
                if (d == null) { d = jellyfin.item(it.id); details.put(it.id, d); }
                String imgOwner = d.has("SeriesId") ? d.optString("SeriesId") : it.id;
                Bitmap back = fetch("/Items/" + imgOwner + "/Images/Backdrop?maxWidth=1920&quality=85");
                if (back == null) back = fetch("/Items/" + it.id + "/Images/Primary?maxWidth=1920&quality=85");
                Bitmap lg = fetch("/Items/" + imgOwner + "/Images/Logo?maxWidth=640&quality=90");
                final JSONObject fd = d;
                final Bitmap fb = back, fl = lg;
                ui.post(() -> { if (it.id.equals(wantedId)) apply(it, fd, fb, fl); });
            } catch (Exception ignored) {
            }
        });
    }

    private Bitmap fetch(String path) {
        if (missing.contains(path)) return null;
        Bitmap c = images.get(path);
        if (c != null) return c;
        try {
            Bitmap b = jellyfin.image(path);
            if (b != null) images.put(path, b);
            else missing.add(path);
            return b;
        } catch (Exception e) {
            missing.add(path);
            return null;
        }
    }

    private void apply(Jellyfin.Item it, JSONObject d, Bitmap back, Bitmap lg) {
        shownId = it.id;
        // 背景交叉淡入
        int next = 1 - front;
        backs[next].setImageBitmap(back);
        backs[next].animate().alpha(back == null ? 0 : backAlpha()).setDuration(FADE_MS).start();
        backs[front].animate().alpha(0).setDuration(FADE_MS).start();
        front = next;

        boolean episode = d.has("SeriesId");
        String name = episode ? d.optString("SeriesName") : d.optString("Name");
        if (lg != null) {
            logo.setImageBitmap(lg);
            logo.setVisibility(VISIBLE);
            title.setVisibility(GONE);
        } else {
            logo.setVisibility(GONE);
            title.setText(name);
            title.setVisibility(VISIBLE);
        }
        line1.setText(episode ? "第 " + d.optInt("ParentIndexNumber") + " 季 第 " + d.optInt("IndexNumber") + " 集 · " + d.optString("Name")
                : genres(d, "Movie".equals(d.optString("Type")) ? "电影" : "剧集"));
        line1.setVisibility(line1.getText().length() == 0 ? GONE : VISIBLE);
        line2.setText(meta(d, it));
        String ov = d.optString("Overview").trim();
        overview.setText(ov);
        overview.setVisibility(ov.isEmpty() ? GONE : VISIBLE);
        info.setAlpha(0f);
        info.animate().alpha(textAlpha()).setDuration(FADE_MS).start();
    }

    /** 电影 / 剧集那一行写类型：「剧集 · 科幻 / 剧情」 */
    private static String genres(JSONObject d, String kind) {
        org.json.JSONArray g = d.optJSONArray("Genres");
        StringBuilder sb = new StringBuilder(kind);
        for (int i = 0; g != null && i < Math.min(3, g.length()); i++) sb.append(i == 0 ? " · " : " / ").append(g.optString(i));
        return sb.toString();
    }

    /** 评分 · 年份 · 时长 · 进度 */
    private static String meta(JSONObject d, Jellyfin.Item it) {
        StringBuilder sb = new StringBuilder();
        double r = d.optDouble("CommunityRating", 0);
        if (r > 0) sb.append(String.format(Locale.US, "★ %.1f", r));
        int y = d.optInt("ProductionYear");
        if (y > 0) sb.append(sb.length() > 0 ? "  ·  " : "").append(y);
        long runMin = d.optLong("RunTimeTicks") / 600_000_000L;
        if (runMin > 0) sb.append(sb.length() > 0 ? "  ·  " : "").append(runMin).append(" 分钟");
        JSONObject ud = d.optJSONObject("UserData");
        if (ud != null) {
            if (ud.optBoolean("Played")) sb.append("  ·  已看完");
            else if (ud.optLong("PlaybackPositionTicks") > 0 && runMin > 0) {
                long left = runMin - ud.optLong("PlaybackPositionTicks") / 600_000_000L;
                if (left > 0) sb.append("  ·  还剩 ").append(left).append(" 分钟");
            } else if ("Series".equals(d.optString("Type")) && ud.optInt("UnplayedItemCount") > 0) {
                sb.append("  ·  ").append(ud.optInt("UnplayedItemCount")).append(" 集未看");
            }
        }
        return sb.toString();
    }

    void release() {
        io.shutdownNow();
    }

    private int px(int v) {
        return Ui.px(a, v);
    }
}
