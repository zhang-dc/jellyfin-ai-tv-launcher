package com.optimisticat.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 剧集详情 / 选集页（设计稿 docs/ui-v2-design.md §2）：上半屏和首页同一套大广告位（剧照 + Logo + 简介），
 * 下面「继续播放 / 从第 1 集开始」、季标签、这一季的单集卡片（看完打勾，看一半有进度条），
 * 焦点单集的简介写在卡片行下面。
 *
 * 进来默认焦点在「继续播放」，季标签落在正在看的那一季，单集行滚到那一集；看完一集回来整页刷新进度。
 */
public class SeriesActivity extends Activity {

    static final String EXTRA_SERIES = "seriesId";

    private static final int CARD_W = 300, CARD_H = 169, GAP = 24;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final LruCache<String, Bitmap> images = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };

    private Jellyfin jellyfin;
    private String seriesId;
    private HeroView hero;
    private LinearLayout buttons, tabs, episodeRow;
    private HorizontalScrollView episodeScroll;
    private TextView epTitle, epOverview;

    private JSONArray seasons = new JSONArray();
    private JSONObject next;              // 接着看的那一集，null = 一集没看
    private String seasonId;              // 当前显示的季
    private boolean firstLoad = true;
    /** 焦点从别处进季标签行时先落到当前季（不然按下键会落到最近的那个标签、顺手切了季） */
    private boolean redirecting = false;
    /** 单集行里「接着看」那一集的位置；从上面按下键进单集行先落到它 */
    private int entryIdx = 0;

    static void open(Context ctx, String seriesId) {
        ctx.startActivity(new Intent(ctx, SeriesActivity.class).putExtra(EXTRA_SERIES, seriesId));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.fullscreen(this);
        jellyfin = Services.jellyfin(this);
        seriesId = getIntent().getStringExtra(EXTRA_SERIES);
        if (jellyfin == null || seriesId == null) { finish(); return; }
        setContentView(buildLayout());
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener((old, now) -> {
            if (now != null && now.getParent() == episodeRow && (old == null || old.getParent() != episodeRow)) {
                View entry = episodeRow.getChildAt(entryIdx);
                if (entry != null && entry != now) ui.post(entry::requestFocus);
                return;
            }
            if (now == null || now.getParent() != tabs || (old != null && old.getParent() == tabs)) return;
            if (now.getTag() == null || now.getTag().equals(seasonId)) return;
            View sel = tabs.findViewWithTag(seasonId);
            if (sel == null) return;
            redirecting = true;
            ui.post(() -> { sel.requestFocus(); redirecting = false; });
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        hero.showNow(seriesId);   // 评分行里的「N 集未看」要是新的
        load();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
        if (hero != null) hero.release();
    }

    // ---------------------------------------------------------------- 布局

    private View buildLayout() {
        FrameLayout root = new FrameLayout(this);
        hero = new HeroView(this, jellyfin);
        root.addView(hero, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setClipChildren(false);
        body.setClipToPadding(false);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        bp.topMargin = px(560);
        root.addView(body, bp);

        buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setPadding(px(96), 0, px(96), 0);
        buttons.setClipChildren(false);
        buttons.setClipToPadding(false);
        body.addView(buttons, new LinearLayout.LayoutParams(-1, px(72)));

        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabScroll.setClipChildren(false);
        tabScroll.setClipToPadding(false);
        tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(px(96), px(30), px(96), px(6));
        tabs.setClipChildren(false);
        tabs.setClipToPadding(false);
        tabScroll.addView(tabs);
        body.addView(tabScroll);

        episodeScroll = new HorizontalScrollView(this);
        episodeScroll.setHorizontalScrollBarEnabled(false);
        episodeScroll.setClipChildren(false);
        episodeScroll.setClipToPadding(false);
        episodeRow = new LinearLayout(this);
        episodeRow.setOrientation(LinearLayout.HORIZONTAL);
        episodeRow.setPadding(px(96), px(18), px(96), px(14));
        episodeRow.setClipChildren(false);
        episodeRow.setClipToPadding(false);
        episodeScroll.addView(episodeRow);
        body.addView(episodeScroll);

        epTitle = Ui.text(this, "", 24, Color.WHITE, Typeface.DEFAULT_BOLD);
        epTitle.setPadding(px(100), px(8), px(96), 0);
        body.addView(epTitle);
        epOverview = Ui.text(this, "", 22, 0xFFAEAEB2, Typeface.DEFAULT);
        epOverview.setPadding(px(100), px(6), px(96), 0);
        epOverview.setMaxLines(2);
        epOverview.setEllipsize(android.text.TextUtils.TruncateAt.END);
        body.addView(epOverview, new LinearLayout.LayoutParams(px(1500), -2));
        return root;
    }

    // ---------------------------------------------------------------- 数据

    private void load() {
        io.execute(() -> {
            try {
                JSONArray ss = jellyfin.seasons(seriesId);
                JSONObject n = jellyfin.nextUp(seriesId);
                ui.post(() -> {
                    seasons = ss;
                    next = n;
                    String want = seasonId;
                    if (firstLoad || want == null) want = n != null ? n.optString("SeasonId") : firstSeason(ss);
                    buildButtons();
                    buildTabs(want);
                    selectSeason(want, firstLoad);
                    firstLoad = false;
                });
            } catch (Exception e) {
                ui.post(() -> { if (firstLoad) { epTitle.setText("连不上 Jellyfin"); } });
            }
        });
    }

    /** 没看过的剧从第 1 季开始（跳过特别篇「第 0 季」，除非只有特别篇）。 */
    private static String firstSeason(JSONArray ss) {
        for (int i = 0; i < ss.length(); i++) {
            JSONObject s = ss.optJSONObject(i);
            if (s.optInt("IndexNumber") > 0) return s.optString("Id");
        }
        return ss.length() > 0 ? ss.optJSONObject(0).optString("Id") : null;
    }

    private void buildButtons() {
        buttons.removeAllViews();
        if (next != null) {
            boolean resume = next.optJSONObject("UserData") != null && next.optJSONObject("UserData").optLong("PlaybackPositionTicks") > 0;
            String label = (resume ? "▶  继续播放  " : "▶  播放  ") + seLabel(next);
            buttons.addView(button(label, v -> PlayerActivity.play(this, next.optString("Id"))));
            buttons.addView(button("⟲  从第 1 集开始", v -> playFirst()));
        } else {
            buttons.addView(button("▶  从第 1 集开始", v -> playFirst()));
        }
    }

    private void playFirst() {
        String sid = firstSeason(seasons);
        if (sid == null) return;
        io.execute(() -> {
            try {
                JSONArray eps = jellyfin.episodes(seriesId, sid);
                if (eps.length() > 0) {
                    String id = eps.getJSONObject(0).optString("Id");
                    ui.post(() -> PlayerActivity.play(this, id));
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void buildTabs(String selected) {
        tabs.removeAllViews();
        for (int i = 0; i < seasons.length(); i++) {
            JSONObject s = seasons.optJSONObject(i);
            String id = s.optString("Id");
            int idx = s.optInt("IndexNumber");
            tabs.addView(tab(idx == 0 ? "特别篇" : "第 " + idx + " 季", id, id.equals(selected)));
        }
    }

    private void selectSeason(String id, boolean focusButtons) {
        if (id == null) return;
        boolean changed = !id.equals(seasonId);
        seasonId = id;
        for (int i = 0; i < tabs.getChildCount(); i++) {
            View t = tabs.getChildAt(i);
            styleTab((TextView) t, id.equals(t.getTag()), t.hasFocus());
        }
        io.execute(() -> {
            try {
                JSONArray eps = jellyfin.episodes(seriesId, id);
                ui.post(() -> {
                    if (!id.equals(seasonId)) return;   // 已经切到别的季了
                    showEpisodes(eps, changed);
                    if (focusButtons && buttons.getChildCount() > 0) buttons.getChildAt(0).requestFocus();
                });
            } catch (Exception ignored) {
            }
        });
    }

    private void showEpisodes(JSONArray eps, boolean seasonChanged) {
        View focused = getCurrentFocus();
        int focusedIdx = focused != null && focused.getParent() == episodeRow ? episodeRow.indexOfChild(focused) : -1;
        episodeRow.removeAllViews();
        String nextId = next != null ? next.optString("Id") : "";
        int scrollTo = 0;
        for (int i = 0; i < eps.length(); i++) {
            JSONObject e = eps.optJSONObject(i);
            if (e.optString("Id").equals(nextId)) scrollTo = i;
            episodeRow.addView(episode(e));
        }
        entryIdx = scrollTo;
        if (focusedIdx >= 0 && focusedIdx < episodeRow.getChildCount()) episodeRow.getChildAt(focusedIdx).requestFocus();
        if (seasonChanged) {
            final int x = Math.max(0, scrollTo - 1) * px(CARD_W + GAP);
            episodeScroll.post(() -> episodeScroll.scrollTo(x, 0));
            describe(scrollTo < eps.length() ? eps.optJSONObject(scrollTo) : null);
        }
    }

    // ---------------------------------------------------------------- 组件

    private TextView button(String label, View.OnClickListener onClick) {
        TextView b = Ui.pill(this, label, onClick);
        b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px(26));
        b.setPadding(px(36), px(14), px(36), px(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = px(20);
        lp.gravity = Gravity.CENTER_VERTICAL;
        b.setLayoutParams(lp);
        View.OnFocusChangeListener base = b.getOnFocusChangeListener();
        b.setOnFocusChangeListener((v, has) -> {
            base.onFocusChange(v, has);
            v.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(140).start();
        });
        return b;
    }

    /** 季标签：选中的季白底黑字，焦点再放大一点。焦点落上去就切季。 */
    private TextView tab(String label, String id, boolean selected) {
        TextView t = Ui.text(this, label, 24, Color.WHITE, Typeface.DEFAULT_BOLD);
        t.setTag(id);
        t.setPadding(px(26), px(10), px(26), px(10));
        t.setFocusable(true);
        t.setFocusableInTouchMode(true);
        t.setClickable(true);
        Ui.noHighlight(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = px(12);
        t.setLayoutParams(lp);
        styleTab(t, selected, false);
        t.setOnFocusChangeListener((v, has) -> {
            if (has && !redirecting) selectSeason(id, false);
            styleTab((TextView) v, id.equals(seasonId), has);
        });
        return t;
    }

    private void styleTab(TextView t, boolean selected, boolean focused) {
        GradientDrawable bg = Ui.rounded(selected ? Color.WHITE : Color.TRANSPARENT, px(26));
        if (focused && !selected) bg.setStroke(px(3), Color.WHITE);
        t.setBackground(bg);
        t.setTextColor(selected ? Color.BLACK : 0xCCFFFFFF);
        t.animate().scaleX(focused ? 1.08f : 1f).scaleY(focused ? 1.08f : 1f).setDuration(140).start();
    }

    /** 单集卡：截图 + 「集号 集名」+ 时长；看完右上角打勾，看一半底部进度条。 */
    private View episode(JSONObject e) {
        FrameLayout c = new FrameLayout(this);
        c.setClipToOutline(true);
        c.setBackground(Ui.rounded(0xFF1C1C1E, px(12)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(CARD_W), px(CARD_H));
        lp.rightMargin = px(GAP);
        c.setLayoutParams(lp);

        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        c.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        JSONObject tags = e.optJSONObject("ImageTags");
        if (tags != null && tags.has("Primary")) loadImage("/Items/" + e.optString("Id") + "/Images/Primary?maxWidth=480&quality=80", iv);

        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x00000000, 0xE6000000}));
        c.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        JSONObject ud = e.optJSONObject("UserData");
        boolean played = ud != null && ud.optBoolean("Played");
        float progress = ud != null ? (float) ud.optDouble("PlayedPercentage", 0) / 100f : 0;

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(px(14), 0, px(14), px(progress > 0 ? 16 : 10));
        TextView t1 = Ui.text(this, e.optInt("IndexNumber") + ". " + e.optString("Name"), 22, Color.WHITE, Typeface.DEFAULT_BOLD);
        t1.setSingleLine(true);
        t1.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(t1);
        long min = e.optLong("RunTimeTicks") / 600_000_000L;
        if (min > 0) texts.addView(Ui.text(this, min + " 分钟", 18, 0xFFD0D0D5, Typeface.DEFAULT));
        c.addView(texts, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        if (played) {
            TextView check = Ui.text(this, "✓", 20, Color.BLACK, Typeface.DEFAULT_BOLD);
            check.setGravity(Gravity.CENTER);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(0xE6FFFFFF);
            check.setBackground(dot);
            FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(px(34), px(34), Gravity.TOP | Gravity.END);
            cp.setMargins(0, px(10), px(10), 0);
            c.addView(check, cp);
        } else if (progress > 0) {
            View track = new View(this);
            track.setBackgroundColor(0x55FFFFFF);
            c.addView(track, new FrameLayout.LayoutParams(-1, px(6), Gravity.BOTTOM));
            View bar = new View(this);
            bar.setBackgroundColor(Ui.ACCENT);
            c.addView(bar, new FrameLayout.LayoutParams(Math.max(px(6), Math.round(px(CARD_W) * progress)), px(6), Gravity.BOTTOM));
        }

        Ui.focusRing(this, c, 1.08f);
        View.OnFocusChangeListener base = c.getOnFocusChangeListener();
        c.setOnFocusChangeListener((v, has) -> {
            base.onFocusChange(v, has);
            v.setElevation(has ? px(16) : 0);
            if (has) describe(e);
        });
        c.setOnClickListener(v -> PlayerActivity.play(this, e.optString("Id")));
        return c;
    }

    /** 卡片行下面那两行：焦点单集的「第 X 季 第 Y 集 · 集名」和简介。 */
    private void describe(JSONObject e) {
        if (e == null) { epTitle.setText(""); epOverview.setText(""); return; }
        epTitle.setText(seLabel(e) + " · " + e.optString("Name"));
        epOverview.setText(e.optString("Overview").trim());
    }

    private static String seLabel(JSONObject e) {
        return "第 " + e.optInt("ParentIndexNumber") + " 季 第 " + e.optInt("IndexNumber") + " 集";
    }

    private void loadImage(String path, ImageView into) {
        Bitmap cached = images.get(path);
        if (cached != null) { into.setImageBitmap(cached); return; }
        into.setTag(path);
        io.execute(() -> {
            try {
                Bitmap b = jellyfin.image(path);
                if (b == null) return;
                images.put(path, b);
                ui.post(() -> { if (path.equals(into.getTag())) into.setImageBitmap(b); });
            } catch (Exception ignored) {
            }
        });
    }

    private int px(int v) {
        return Ui.px(this, v);
    }
}
