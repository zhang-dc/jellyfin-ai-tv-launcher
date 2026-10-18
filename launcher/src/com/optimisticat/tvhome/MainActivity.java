package com.optimisticat.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 客厅电视的极简桌面：没有推荐位、没有广告、没有后台服务。唯一联网的是家里的 Jellyfin。
 *
 * 布局（1920x1080 横屏，从上到下）：
 *   左上 问候语 / 大号时间 / 日期                  右上 信号源 · 设置
 *   最近播放  看到一半的集 + 各剧下一集（带进度条），确认键直接打开 Jellyfin 里那一集
 *   常用应用  Jellyfin 打头；焦点处显示名字 + 从图标取色的光晕
 *   ↓ 最近下载  新入库的剧和电影
 *   ↓ 全部应用
 * 返回键回到顶部。只用平台 View，不依赖 AndroidX —— 电视只有 2.7GB 内存、32 位系统，包越小越好。
 */
public class MainActivity extends Activity {

    /** 常用应用，按顺序摆。没装的自动跳过。 */
    private static final String[][] FAVORITES = {
            // 包名, 显示名（空 = 用应用自己的名字）, 指定 Activity（空 = 默认入口）
            {"org.jellyfin.androidtv", "Jellyfin", ""},
            // 原厂桌面换下来以后，家里看「解放」这类小米影视内容从这里进
            {"com.mitv.tvhome", "小米影视", "com.mitv.tvhome.MainActivityUserMode"},
            {"com.ktcp.video", "", ""},
            {"com.cibn.tv", "", ""},
            {"com.hunantv.license", "", ""},
            {"com.xiaodianshi.tv.yst", "", ""},
    };
    private static final String JELLYFIN_PKG = "org.jellyfin.androidtv";
    /** Jellyfin 电视版的启动入口认这个 extra，直接打开该条目的详情页（它自己的首页频道也是这么跳的）。 */
    private static final String JELLYFIN_STARTUP = "org.jellyfin.androidtv.ui.startup.StartupActivity";

    private static final int BG = 0xFF000000;
    private static final int TEXT_DIM = 0xFF8E8E93;
    private static final int CARD_W = 352, CARD_H = 198, TILE_W = 256, TILE_H = 144, GAP = 28;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final LruCache<String, Bitmap> images = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private PackageManager pm;
    private Jellyfin jellyfin;
    private Gateway gateway;

    private TextView greetingView, timeView, dateView, focusTitle, playedLabel, addedLabel, allLabel;
    private LinearLayout header, actions, dock, playedRow, addedRow, downloadingRow;
    private HorizontalScrollView playedScroll, addedScroll, downloadingScroll;
    private TextView downloadingLabel;
    private GridLayout grid;
    private ScrollView scroller;
    private View glow, firstDockTile;
    private String appsSignature = "", playedSignature = "", addedSignature = "";
    /** 回到桌面后用户还没按过方向键：数据到了就把焦点放到「最近播放」第一张。 */
    private boolean untouched = true;
    private int scrollTarget = 0;

    static final class App {
        String pkg, label;
        ComponentName component;
        Drawable banner, icon;
        int color;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pm = getPackageManager();
        jellyfin = Services.jellyfin(this);   // 没配置就是 null，只当应用桌面用
        gateway = Services.gateway(this);
        getWindow().getDecorView().setBackgroundColor(BG);
        // 电视本来没有状态栏；模拟器、个别机型会有，统一藏掉
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        setContentView(buildLayout());
        tickClock();
    }

    @Override
    protected void onResume() {
        super.onResume();
        untouched = true;
        HomeKeyService.leftStockHome();
        XiaoaiBridge.detachAll();   // 回到首页：语音键还给小爱
        loadApps();
        loadJellyfin();   // 看完一集回来，进度和下一集都要刷新
        handler.removeCallbacks(clockTick);
        handler.post(clockTick);
        handler.removeCallbacks(downloadsTick);
        handler.post(downloadsTick);
        if (getCurrentFocus() == null) focusDefault();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(clockTick);
        handler.removeCallbacks(downloadsTick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }

    /** 已经在桌面时再按主页键：回到顶部、焦点回默认位置。 */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        scroller.smoothScrollTo(0, 0);
        focusDefault();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) untouched = false;
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // 桌面上返回键不退出；翻下去了就回顶部
            if (scroller.getScrollY() > 0) {
                scroller.smoothScrollTo(0, 0);
                focusDefault();
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---------------------------------------------------------------- 布局

    private View buildLayout() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        // 光晕：一块径向渐变，跟着焦点移动、变色
        glow = new View(this);
        root.addView(glow, new FrameLayout.LayoutParams(dp(760), dp(520)));
        glow.setAlpha(0f);

        // 关掉 ScrollView 自带的「滚到焦点」：它只滚到刚好露出，放大 1.1 倍的格子会被底边切掉。
        // 纵向滚动全由 scrollFor() 按焦点所在的区块决定。
        scroller = new ScrollView(this) {
            @Override
            protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect rect) {
                return 0;
            }
        };
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, 0, 0, dp(72));
        body.setClipChildren(false);
        body.setClipToPadding(false);
        scroller.addView(body);
        root.addView(scroller, new FrameLayout.LayoutParams(-1, -1));

        // 顶部留出时钟的位置
        body.addView(new View(this), new LinearLayout.LayoutParams(-1, dp(318)));

        playedLabel = sectionLabel("最近播放", 0);
        body.addView(playedLabel);
        playedScroll = row();
        playedRow = (LinearLayout) playedScroll.getChildAt(0);
        body.addView(playedScroll);

        // 下载中：有任务在下才出现（进度 5 秒刷新），确认键进下载进度页
        downloadingLabel = sectionLabel("下载中", dp(30));
        body.addView(downloadingLabel);
        downloadingScroll = row();
        downloadingRow = (LinearLayout) downloadingScroll.getChildAt(0);
        body.addView(downloadingScroll);
        downloadingLabel.setVisibility(View.GONE);
        downloadingScroll.setVisibility(View.GONE);

        focusTitle = text("", 28, Color.WHITE, Typeface.DEFAULT_BOLD);
        focusTitle.setPadding(dp(102), dp(30), 0, dp(6));
        body.addView(focusTitle);
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setPadding(dp(96), dp(12), dp(96), dp(12));
        dock.setClipChildren(false);
        dock.setClipToPadding(false);
        body.addView(dock);

        addedLabel = sectionLabel("最近下载", dp(48));
        body.addView(addedLabel);
        addedScroll = row();
        addedRow = (LinearLayout) addedScroll.getChildAt(0);
        body.addView(addedScroll);

        allLabel = sectionLabel("全部应用", dp(48));
        body.addView(allLabel);
        grid = new GridLayout(this);
        grid.setColumnCount(6);
        grid.setPadding(dp(96), dp(12), dp(96), 0);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        body.addView(grid);

        setJellyfinVisible(false);

        // 左上：问候 / 时间 / 日期
        header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(96), dp(56), 0, 0);
        greetingView = text("", 30, TEXT_DIM, Typeface.DEFAULT);
        timeView = text("", 120, Color.WHITE, Typeface.create("sans-serif-thin", Typeface.NORMAL));
        timeView.setIncludeFontPadding(false);
        dateView = text("", 28, TEXT_DIM, Typeface.DEFAULT);
        header.addView(greetingView);
        header.addView(timeView);
        header.addView(dateView);
        root.addView(header, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));

        // 右上：圆形图标按钮，文字只在选中时出现（问问 · 找剧 · 下载 · 信号源 · 设置）
        actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(64), dp(80), 0);
        actions.setClipChildren(false);
        actions.setClipToPadding(false);
        if (gateway != null) {
            actions.addView(topButton(Icons.Kind.CHAT, "问问", v -> startActivity(new Intent(this, AgentActivity.class))));
            actions.addView(topButton(Icons.Kind.SEARCH, "找剧", v -> startActivity(new Intent(this, SearchActivity.class))));
            actions.addView(topButton(Icons.Kind.DOWNLOAD, "下载", v -> startActivity(new Intent(this, DownloadsActivity.class))));
        }
        actions.addView(topButton(Icons.Kind.INPUT, "信号源", v -> openInputs()));
        actions.addView(topButton(Icons.Kind.SETTINGS, "设置", v -> openSettings()));
        root.addView(actions, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END));

        // 往下翻时左上角时钟淡出，免得压在内容上
        scroller.getViewTreeObserver().addOnScrollChangedListener(() -> {
            float a = 1f - Math.min(1f, scroller.getScrollY() / (float) dp(220));
            header.setAlpha(a);
            actions.setAlpha(a);
        });
        return root;
    }

    private TextView sectionLabel(String s, int topPad) {
        TextView v = text(s, 26, TEXT_DIM, Typeface.DEFAULT);
        v.setPadding(dp(102), topPad, 0, dp(14));
        return v;
    }

    /** 一条横向滚动的卡片行；左右留白，放大的卡片不会被裁。 */
    private HorizontalScrollView row() {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(dp(96), dp(14), dp(96), dp(14));
        r.setClipChildren(false);
        r.setClipToPadding(false);
        hs.addView(r);
        return hs;
    }

    private void setJellyfinVisible(boolean played, boolean added) {
        int p = played ? View.VISIBLE : View.GONE, a = added ? View.VISIBLE : View.GONE;
        playedLabel.setVisibility(p);
        playedScroll.setVisibility(p);
        addedLabel.setVisibility(a);
        addedScroll.setVisibility(a);
    }

    private void setJellyfinVisible(boolean v) {
        setJellyfinVisible(v, v);
    }

    private View topButton(Icons.Kind kind, String label, View.OnClickListener onClick) {
        LinearLayout b = Ui.iconButton(this, kind, label, onClick);
        View circle = b.getChildAt(0);
        View.OnFocusChangeListener base = circle.getOnFocusChangeListener();
        circle.setOnFocusChangeListener((v, has) -> {
            base.onFocusChange(v, has);
            if (has) {
                glow.animate().alpha(0f).setDuration(160).start();
                focusTitle.setText("");
                scrollTo(0);
            }
        });
        return b;
    }

    private TextView pill(String label, View.OnClickListener onClick) {
        TextView v = text(label, 24, Color.WHITE, Typeface.DEFAULT);
        v.setPadding(dp(28), dp(12), dp(28), dp(12));
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(16);
        v.setLayoutParams(lp);
        GradientDrawable idle = rounded(0x33FFFFFF, dp(28));
        GradientDrawable focused = rounded(0xFFFFFFFF, dp(28));
        v.setBackground(idle);
        v.setOnFocusChangeListener((view, has) -> {
            view.setBackground(has ? focused : idle);
            ((TextView) view).setTextColor(has ? Color.BLACK : Color.WHITE);
            if (has) {
                glow.animate().alpha(0f).setDuration(200).start();
                focusTitle.setText("");
                scrollTo(0);
            }
        });
        return v;
    }

    // ---------------------------------------------------------------- Jellyfin

    private void loadJellyfin() {
        if (jellyfin == null) return;
        io.execute(() -> {
            List<Jellyfin.Item> played, added;
            try {
                played = jellyfin.recentPlayed(12);
                added = jellyfin.recentAdded(16);
            } catch (Exception e) {
                return;   // 服务器没开 / 连不上：保持上次的内容，下回再刷
            }
            handler.post(() -> showJellyfin(played, added));
        });
    }

    private void showJellyfin(List<Jellyfin.Item> played, List<Jellyfin.Item> added) {
        String ps = signature(played), as = signature(added);
        boolean wasEmpty = playedRow.getChildCount() == 0;
        if (!ps.equals(playedSignature)) {
            playedSignature = ps;
            fillRow(playedRow, played, true);
        }
        if (!as.equals(addedSignature)) {
            addedSignature = as;
            fillRow(addedRow, added, false);
        }
        setJellyfinVisible(!played.isEmpty(), !added.isEmpty());
        if (untouched || (wasEmpty && getCurrentFocus() == firstDockTile)) focusDefault();
    }

    private static String signature(List<Jellyfin.Item> items) {
        StringBuilder sb = new StringBuilder();
        for (Jellyfin.Item it : items) sb.append(it.id).append(':').append(Math.round(it.progress * 100)).append(',');
        return sb.toString();
    }

    private void fillRow(LinearLayout row, List<Jellyfin.Item> items, boolean top) {
        View focused = getCurrentFocus();
        boolean hadFocus = focused != null && focused.getParent() == row;
        row.removeAllViews();
        for (Jellyfin.Item it : items) row.addView(card(it, top));
        if (hadFocus && row.getChildCount() > 0) row.getChildAt(0).requestFocus();
    }

    /** 一张剧集卡：截图铺底，下半部压暗写剧名和集数，看到一半的底部有进度条。 */
    private View card(Jellyfin.Item it, boolean top) {
        FrameLayout c = new FrameLayout(this);
        c.setFocusable(true);
        c.setFocusableInTouchMode(true);
        Ui.noHighlight(c);   // 模拟器是触摸模式，不加的话代码设的默认焦点不生效
        c.setClickable(true);
        c.setClipToOutline(true);
        c.setBackground(rounded(0xFF1C1C1E, dp(14)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(CARD_W), dp(CARD_H));
        lp.rightMargin = dp(GAP);
        c.setLayoutParams(lp);

        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        c.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        final int[] tint = {0xFF3A6EA5};   // 图片没到之前的默认光晕色
        loadImage(it.imageUrl, iv, b -> {
            tint[0] = dominantColor(new android.graphics.drawable.BitmapDrawable(getResources(), b));
            if (c.hasFocus()) moveGlow(c, tint[0]);
        });

        View shade = new View(this);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x00000000, 0xE6000000});
        shade.setBackground(g);
        c.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(16), 0, dp(16), dp(it.progress > 0 ? 18 : 12));
        TextView t1 = text(it.title, 24, Color.WHITE, Typeface.DEFAULT_BOLD);
        t1.setSingleLine(true);
        t1.setEllipsize(android.text.TextUtils.TruncateAt.END);
        TextView t2 = text(it.subtitle, 18, 0xFFD0D0D5, Typeface.DEFAULT);
        t2.setSingleLine(true);
        t2.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(t1);
        texts.addView(t2);
        c.addView(texts, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        if (it.progress > 0) {
            View track = new View(this);
            track.setBackgroundColor(0x55FFFFFF);
            c.addView(track, new FrameLayout.LayoutParams(-1, dp(6), Gravity.BOTTOM));
            View bar = new View(this);
            bar.setBackgroundColor(0xFF00A4DC);   // Jellyfin 蓝
            c.addView(bar, new FrameLayout.LayoutParams(Math.max(dp(6), Math.round(dp(CARD_W) * it.progress)), dp(6), Gravity.BOTTOM));
        }

        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(14));
        ring.setStroke(dp(4), Color.WHITE);
        c.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(160).start();
            v.setElevation(has ? dp(16) : 0);
            v.setForeground(has ? ring : null);
            if (has) {
                focusTitle.setText("");
                scrollFor(top ? null : addedLabel, v);
                moveGlow(v, tint[0]);
            }
        });
        c.setOnClickListener(v -> openInJellyfin(it.id));
        return c;
    }

    /** minSdk 21 没有 java.util.function，自己定义一个。 */
    private interface ImageLoaded {
        void on(Bitmap b);
    }

    private void loadImage(String path, ImageView into, ImageLoaded loaded) {
        if (path == null || jellyfin == null) return;
        Bitmap cached = images.get(path);
        if (cached != null) { into.setImageBitmap(cached); loaded.on(cached); return; }
        into.setTag(path);
        io.execute(() -> {
            try {
                Bitmap b = jellyfin.image(path);
                if (b == null) return;
                images.put(path, b);
                handler.post(() -> { if (path.equals(into.getTag())) { into.setImageBitmap(b); loaded.on(b); } });
            } catch (Exception ignored) {
            }
        });
    }

    private void openInJellyfin(String itemId) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .setComponent(new ComponentName(JELLYFIN_PKG, JELLYFIN_STARTUP))
                .putExtra("ItemId", itemId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        tryStart(i, "打不开 Jellyfin");
    }

    // ---------------------------------------------------------------- 下载中

    private final Runnable downloadsTick = new Runnable() {
        @Override public void run() {
            loadDownloads();
            handler.postDelayed(this, 5000);
        }
    };

    private void loadDownloads() {
        if (gateway == null) return;
        io.execute(() -> {
            org.json.JSONArray all;
            try {
                all = gateway.hubGet("downloads", null).optJSONArray("downloads");
            } catch (Exception e) {
                return;
            }
            List<org.json.JSONObject> running = new ArrayList<>();
            for (int i = 0; all != null && i < all.length(); i++) {
                org.json.JSONObject t = all.optJSONObject(i);
                if (DownloadsActivity.active(t)) running.add(t);
            }
            handler.post(() -> showDownloads(running));
        });
    }

    private void showDownloads(List<org.json.JSONObject> running) {
        boolean show = !running.isEmpty();
        downloadingLabel.setVisibility(show ? View.VISIBLE : View.GONE);
        downloadingScroll.setVisibility(show ? View.VISIBLE : View.GONE);
        downloadingLabel.setText("下载中 · " + running.size());
        // 张数不变就原地更新进度，免得焦点在这一行时被重建打断
        if (downloadingRow.getChildCount() != running.size()) {
            View focused = getCurrentFocus();
            boolean hadFocus = focused != null && focused.getParent() == downloadingRow;
            downloadingRow.removeAllViews();
            for (int i = 0; i < running.size(); i++) downloadingRow.addView(downloadCard());
            if (hadFocus) (downloadingRow.getChildCount() > 0 ? downloadingRow.getChildAt(0) : firstDockTile).requestFocus();
        }
        for (int i = 0; i < running.size(); i++) bindDownload((FrameLayout) downloadingRow.getChildAt(i), running.get(i));
    }

    private FrameLayout downloadCard() {
        FrameLayout c = new FrameLayout(this);
        c.setFocusable(true);
        c.setFocusableInTouchMode(true);
        Ui.noHighlight(c);
        c.setClickable(true);
        c.setClipToOutline(true);
        c.setBackground(rounded(0xFF1C1C1E, dp(14)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(CARD_W), dp(110));
        lp.rightMargin = dp(GAP);
        c.setLayoutParams(lp);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);
        texts.setPadding(dp(18), 0, dp(18), dp(8));
        TextView name = text("", 22, Color.WHITE, Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        texts.addView(name);
        TextView meta = text("", 18, 0xFFD0D0D5, Typeface.DEFAULT);
        meta.setPadding(0, dp(6), 0, 0);
        texts.addView(meta);
        c.addView(texts, new FrameLayout.LayoutParams(-1, -1));
        View bar = new View(this);
        bar.setBackgroundColor(0xFF00A4DC);
        c.addView(bar, new FrameLayout.LayoutParams(0, dp(6), Gravity.BOTTOM));
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(14));
        ring.setStroke(dp(4), Color.WHITE);
        c.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(160).start();
            v.setForeground(has ? ring : null);
            if (has) {
                focusTitle.setText("");
                scrollTo(0);
                moveGlow(v, 0xFF00A4DC);
            }
        });
        c.setOnClickListener(v -> startActivity(new Intent(this, DownloadsActivity.class)));
        return c;
    }

    private void bindDownload(FrameLayout c, org.json.JSONObject t) {
        LinearLayout texts = (LinearLayout) c.getChildAt(0);
        ((TextView) texts.getChildAt(0)).setText(t.optString("name"));
        ((TextView) texts.getChildAt(1)).setText(t.optInt("progress") + "%  ·  " + Ui.size(t.optLong("dlspeed")) + "/s  " + Ui.eta(t.optLong("eta")));
        View bar = c.getChildAt(1);
        bar.getLayoutParams().width = Math.max(dp(6), Math.round(dp(CARD_W) * t.optInt("progress") / 100f));
        bar.requestLayout();
    }

    // ---------------------------------------------------------------- 应用

    private void loadApps() {
        Map<String, App> all = new LinkedHashMap<>();
        collect(all, Intent.CATEGORY_LEANBACK_LAUNCHER);
        collect(all, Intent.CATEGORY_LAUNCHER);
        all.remove(getPackageName());

        List<App> favorites = new ArrayList<>();
        Set<String> favPkgs = new HashSet<>();
        for (String[] f : FAVORITES) {
            App a = all.get(f[0]);
            if (a == null) a = appFor(f[0], null);   // 原厂桌面这类只有 HOME 入口的，launcher 查询里没有
            if (a == null) continue;
            if (!f[1].isEmpty()) a.label = f[1];
            if (!f[2].isEmpty()) a.component = new ComponentName(f[0], f[2]);
            favorites.add(a);
            favPkgs.add(f[0]);
        }
        List<App> rest = new ArrayList<>();
        for (App a : all.values()) if (!favPkgs.contains(a.pkg)) rest.add(a);   // 常用的不在全部应用里重复
        final Collator collator = Collator.getInstance(Locale.CHINA);
        Collections.sort(rest, (x, y) -> collator.compare(x.label, y.label));

        StringBuilder sig = new StringBuilder();
        for (App a : favorites) sig.append(a.pkg).append(',');
        sig.append('|');
        for (App a : rest) sig.append(a.pkg).append(',');
        if (sig.toString().equals(appsSignature)) return;   // 没变就不重建，免得焦点乱跳
        appsSignature = sig.toString();

        dock.removeAllViews();
        firstDockTile = null;
        for (App a : favorites) {
            View t = tile(a, true);
            if (firstDockTile == null) firstDockTile = t;
            dock.addView(t);
        }
        grid.removeAllViews();
        for (App a : rest) grid.addView(tile(a, false));
        focusDefault();
    }

    private void collect(Map<String, App> out, String category) {
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (out.containsKey(pkg)) continue;
            App a = appFor(pkg, new ComponentName(pkg, ri.activityInfo.name));
            if (a != null) out.put(pkg, a);
        }
    }

    private App appFor(String pkg, ComponentName component) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            if (!ai.enabled) return null;
            App a = new App();
            a.pkg = pkg;
            a.label = String.valueOf(pm.getApplicationLabel(ai));
            a.component = component;
            a.banner = pm.getApplicationBanner(ai);
            a.icon = pm.getApplicationIcon(ai);
            a.color = dominantColor(a.banner != null ? a.banner : a.icon);
            return a;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** 一个应用格子：有电视横幅就整张铺满，没有就深色底 + 图标 + 名字。 */
    private View tile(App a, boolean inDock) {
        FrameLayout t = new FrameLayout(this);
        t.setFocusable(true);
        t.setFocusableInTouchMode(true);
        Ui.noHighlight(t);   // 模拟器是触摸模式，不加的话代码设的默认焦点不生效
        t.setClickable(true);
        t.setClipToOutline(true);
        t.setBackground(rounded(darken(a.color, 0.45f), dp(14)));
        if (inDock) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(TILE_W), dp(TILE_H));
            lp.rightMargin = dp(GAP);
            t.setLayoutParams(lp);
        } else {
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = dp(TILE_W);
            lp.height = dp(TILE_H);
            lp.setMargins(0, 0, dp(GAP), dp(GAP));
            t.setLayoutParams(lp);
        }

        if (a.banner != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(a.banner);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            t.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        } else {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER);
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(a.icon);
            col.addView(iv, new LinearLayout.LayoutParams(dp(64), dp(64)));
            TextView name = text(a.label, 20, Color.WHITE, Typeface.DEFAULT);
            name.setPadding(dp(8), dp(10), dp(8), 0);
            name.setSingleLine(true);
            name.setGravity(Gravity.CENTER);
            col.addView(name);
            t.addView(col, new FrameLayout.LayoutParams(-1, -1));
        }

        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(14));
        ring.setStroke(dp(4), Color.WHITE);
        ring.setColor(Color.TRANSPARENT);

        t.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(160).start();
            v.setElevation(has ? dp(16) : 0);
            v.setForeground(has ? ring : null);
            if (has) {
                // 网格格子自己带名字（横幅上也有），标题只跟常用栏
                focusTitle.setText(inDock ? a.label : "");
                scrollFor(inDock ? null : allLabel, v);
                moveGlow(v, a.color);   // 按滚动终点算位置，不用等滚动停下
            }
        });
        t.setOnClickListener(v -> launch(a));
        return t;
    }

    /**
     * 纵向滚动：section 为空（最近播放 / 常用应用）就回顶部，和时钟同屏；
     * 否则把这个区块的标题顶到上方，区块比一屏长时再保证焦点所在那行完整露出（含放大余量）。
     */
    private void scrollFor(View section, View focused) {
        if (section == null) { scrollTo(0); return; }
        int top = section.getTop() - dp(40);
        int[] a = new int[2], b = new int[2];
        focused.getLocationInWindow(a);
        scroller.getLocationInWindow(b);
        int focusedBottom = a[1] - b[1] + scroller.getScrollY() + focused.getHeight() + dp(60);
        scrollTo(Math.max(top, focusedBottom - scroller.getHeight()));
    }

    private void scrollTo(int y) {
        scrollTarget = y;
        scroller.smoothScrollTo(0, y);
    }

    /**
     * 光晕跟焦点：位置按纵向滚动的终点算（scrollFor 已经把 scrollTarget 定好），所以不用等滚动停下；
     * 140ms 减速移动 + 颜色从上一个应用平滑过渡。2026-10-19 用户反馈「不跟手」：原来先等 300ms 再 220ms 匀速、颜色硬切。
     */
    private final GradientDrawable glowShape = new GradientDrawable();
    private int glowColor = 0;
    private android.animation.ValueAnimator glowTint;

    private void moveGlow(View target, int color) {
        int[] loc = new int[2];
        target.getLocationInWindow(loc);
        float cx = loc[0] + target.getWidth() / 2f;
        float cy = loc[1] + target.getHeight() / 2f - (scrollTarget - scroller.getScrollY());
        if (glow.getBackground() != glowShape) {
            glowShape.setGradientType(GradientDrawable.RADIAL_GRADIENT);
            glowShape.setGradientRadius(dp(360));
            glow.setBackground(glowShape);
        }
        boolean wasHidden = glow.getAlpha() < 0.05f;
        if (glowTint != null) glowTint.cancel();
        int from = wasHidden ? color : glowColor;
        glowTint = android.animation.ValueAnimator.ofObject(new android.animation.ArgbEvaluator(), from, color);
        glowTint.setDuration(wasHidden ? 0 : 160);
        glowTint.addUpdateListener(an -> {
            glowColor = (int) an.getAnimatedValue();
            glowShape.setColors(new int[]{(glowColor & 0x00FFFFFF) | 0x88000000, glowColor & 0x00FFFFFF});
        });
        glowTint.start();
        glow.animate().cancel();
        if (wasHidden) {   // 从隐藏状态出现：直接就位再淡入，不要从上次的位置飞过来
            glow.setX(cx - glow.getWidth() / 2f);
            glow.setY(cy - glow.getHeight() / 2f);
        }
        glow.animate()
                .x(cx - glow.getWidth() / 2f)
                .y(cy - glow.getHeight() / 2f)
                .alpha(1f)
                .setDuration(140)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                .start();
    }

    /** 默认焦点：有「最近播放」就第一张（最常见的操作是接着看），否则 Jellyfin。 */
    private void focusDefault() {
        View target = playedScroll.getVisibility() == View.VISIBLE && playedRow.getChildCount() > 0
                ? playedRow.getChildAt(0) : firstDockTile;
        if (target != null) target.post(target::requestFocus);
    }

    // ---------------------------------------------------------------- 跳转

    private void launch(App a) {
        Intent i;
        if (a.component != null) {
            i = new Intent(Intent.ACTION_MAIN).setComponent(a.component);
        } else {
            i = pm.getLeanbackLaunchIntentForPackage(a.pkg);
            if (i == null) i = pm.getLaunchIntentForPackage(a.pkg);
        }
        if (i == null) { toast("打不开 " + a.label); return; }
        if (HomeKeyService.STOCK_HOME.equals(a.pkg)) HomeKeyService.allowStockHome();   // 「小米影视」是主动打开的，别拦
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        tryStart(i, "打不开 " + a.label);
    }

    /** 小米电视的外接信号源（HDMI）由 tvplayer 接管；别的电视退回系统设置。 */
    private void openInputs() {
        Intent mi = new Intent("com.xiaomi.mitv.tvplayer.EXTSRC_PLAY").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (mi.resolveActivity(pm) != null && tryStart(mi, null)) return;
        tryStart(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "找不到信号源入口");
    }

    private void openSettings() {
        Intent mi = pm.getLaunchIntentForPackage("com.xiaomi.mitv.settings");
        if (mi == null) mi = pm.getLeanbackLaunchIntentForPackage("com.xiaomi.mitv.settings");
        if (mi != null && tryStart(mi.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), null)) return;
        tryStart(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "打不开设置");
    }

    private boolean tryStart(Intent i, String failMsg) {
        try {
            startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            if (failMsg != null) toast(failMsg);
            return false;
        }
    }

    // ---------------------------------------------------------------- 时钟

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            tickClock();
            handler.postDelayed(this, 1000 - (System.currentTimeMillis() % 1000));
        }
    };

    private void tickClock() {
        Date now = new Date();
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String greet = h < 5 ? "夜深了" : h < 11 ? "早上好" : h < 13 ? "中午好" : h < 18 ? "下午好" : "晚上好";
        greetingView.setText(greet);
        timeView.setText(new SimpleDateFormat("HH:mm", Locale.CHINA).format(now));
        dateView.setText(new SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(now));
    }

    // ---------------------------------------------------------------- 工具

    private TextView text(String s, int px, int color, Typeface tf) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(px));
        v.setTextColor(color);
        v.setTypeface(tf);
        return v;
    }

    private static GradientDrawable rounded(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    /** 图标的主色：缩成 24x24，挑饱和度够、不太暗不太亮的像素取平均。 */
    private static int dominantColor(Drawable d) {
        if (d == null) return 0xFF3A3A3C;
        try {
            Bitmap b = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            d.setBounds(0, 0, 24, 24);
            d.draw(c);
            long r = 0, g = 0, bl = 0, n = 0;
            float[] hsv = new float[3];
            for (int y = 0; y < 24; y++) for (int x = 0; x < 24; x++) {
                int p = b.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                if (hsv[1] < 0.25f || hsv[2] < 0.2f || hsv[2] > 0.97f) continue;
                r += Color.red(p); g += Color.green(p); bl += Color.blue(p); n++;
            }
            b.recycle();
            if (n == 0) return 0xFF3A3A3C;
            return Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n));
        } catch (RuntimeException e) {
            return 0xFF3A3A3C;
        }
    }

    private static int darken(int color, float f) {
        return Color.rgb((int) (Color.red(color) * f), (int) (Color.green(color) * f), (int) (Color.blue(color) * f));
    }

    /** 设计稿按 1920x1080 像素出，按屏宽等比换算 —— 与屏幕密度无关，模拟器和电视看到的一样。 */
    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().widthPixels / 1920f);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
