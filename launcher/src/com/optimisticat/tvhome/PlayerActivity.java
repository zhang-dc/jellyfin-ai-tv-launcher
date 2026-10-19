package com.optimisticat.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.exoplayer.ExoPlayer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 内置播放器（设计稿 docs/player-design.md，字幕优先）。
 *
 * 播放：Media3 ExoPlayer 直连 Jellyfin 原文件，放不了退到服务器转码 HLS。续播 / 进度 / 看完写回 Jellyfin 的 UserData。
 * 字幕两种来源，面板里分两组：
 *   - 文本字幕（外挂 + 能转文本的内嵌轨）：Jellyfin 转成 SRT，自己渲染（SubtitleEngine），能边看边调偏移
 *   - 视频自带字幕轨：交给 ExoPlayer 解码（含蓝光 PGS 图形字幕），onCues 拿到每帧该显示的内容，画在同一位置；不能调偏移
 * 字幕样式：描边无底（默认）/ 半透明底 / 黑底 × 白 / 黄 × 小中大，全局记住。
 *
 * 遥控器：确认 = 暂停 / 继续；左右 = 快退 / 快进 10 秒（按住 30 秒一跳）；上 = 字幕面板；下 = 进度条；返回 = 收起 / 退出。
 * 面板里：上下选行，在有 ‹ › 的行上按左右调值，确认选字幕轨。模拟器上点画面 = 显示进度条 / 暂停。
 */
public class PlayerActivity extends Activity {

    static final String EXTRA_ITEM = "itemId";

    private static final long SEEK_MS = 10_000, SEEK_FAST_MS = 30_000, OFFSET_STEP_MS = 100;
    private static final int[] SUB_SIZES = {44, 54, 66};
    private static final String[] SIZE_NAMES = {"小", "中", "大"};
    private static final String[] STYLE_NAMES = {"描边", "半透明底", "黑底"};
    private static final String[] COLOR_NAMES = {"白色", "黄色"};
    private static final int[] COLORS = {Color.WHITE, 0xFFFFE066};
    private static final int PANEL_W = 680;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Jellyfin jellyfin;
    private ExoPlayer player;
    /**
     * 用 TextureView 不用 SurfaceView：SurfaceView 是独立图层，被半透明浮层（进度条渐变）盖住的那块会换一种方式合成，
     * 色彩范围换算不同，浮层顶边出现一条亮度断层（MuMu 实测 67→79）。TextureView 和界面同一图层合成，没有这个问题。
     */
    private TextureView surface;
    private FrameLayout root;
    private OutlineTextView subPrimary, subSecondary;
    private LinearLayout subBox, bar, panel, panelList;
    private ImageView subImage;
    private View progressFill, progressThumb;
    private TextView barTitle, barSub, barPos, barLeft, barState, toast;
    private final SubtitleEngine subs = new SubtitleEngine();
    private SharedPreferences prefs;

    // 当前条目
    private String itemId, mediaSourceId, seriesId, offsetKey;
    private JSONObject item;
    private final List<JSONObject> textSubs = new ArrayList<>();
    /** 视频自带字幕轨（ExoPlayer 报上来的 TEXT 组）。 */
    private final List<Tracks.Group> embedded = new ArrayList<>();
    /** 当前选择：kind 0 = 关闭，1 = 文本字幕 textSubs[idx]，2 = 自带轨 embedded[idx]。 */
    private int subKind = 0, subIdx = -1;
    private long offsetMs = 0;
    private int sizeIdx, styleIdx, colorIdx;
    private boolean triedTranscode = false, finished = false;
    private long lastSaveMs = 0;
    private JSONObject nextPending;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.fullscreen(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        jellyfin = Services.jellyfin(this);
        prefs = getSharedPreferences("player", Context.MODE_PRIVATE);
        sizeIdx = prefs.getInt("subSize", 1);
        styleIdx = prefs.getInt("subStyle", 0);
        colorIdx = prefs.getInt("subColor", 0);
        setContentView(build());
        applyStyle();
        if (jellyfin == null) { showToast("没有配置 Jellyfin（assets/jellyfin.properties）"); return; }
        open(getIntent().getStringExtra(EXTRA_ITEM));
    }

    @Override
    protected void onStop() {
        super.onStop();
        saveProgress(true);
        if (player != null) player.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        if (player != null) player.release();
    }

    // ---------------------------------------------------------------- 打开 / 播放

    private void open(String id) {
        itemId = id;
        finished = false;
        triedTranscode = false;
        nextPending = null;
        showToast("正在打开…");
        new Thread(() -> {
            try {
                JSONObject it = jellyfin.item(id);
                ui.post(() -> start(it));
            } catch (Exception e) {
                ui.post(() -> showToast("打不开：" + e.getMessage()));
            }
        }, "player-open").start();
    }

    private void start(JSONObject it) {
        item = it;
        JSONObject ms = it.optJSONArray("MediaSources").optJSONObject(0);
        mediaSourceId = ms.optString("Id");
        seriesId = it.optString("SeriesId", null);
        offsetKey = seriesId != null ? "offset:" + seriesId + ":" + it.optInt("ParentIndexNumber") : "offset:" + itemId;
        offsetMs = prefs.getLong("offset:item:" + itemId, prefs.getLong(offsetKey, 0));
        fillBar();

        // 文本字幕：Jellyfin 能转 SRT 的（外挂 srt/ass + 内嵌文本轨）；图形字幕走「自带字幕轨」那一组
        textSubs.clear();
        embedded.clear();
        JSONArray streams = ms.optJSONArray("MediaStreams");
        for (int i = 0; streams != null && i < streams.length(); i++) {
            JSONObject s = streams.optJSONObject(i);
            if ("Subtitle".equals(s.optString("Type")) && s.optBoolean("IsTextSubtitleStream")) textSubs.add(s);
        }
        int def = pickDefaultSub();
        subKind = def >= 0 ? 1 : 0;
        subIdx = def;
        loadTextSubtitle();

        long resumeMs = it.optJSONObject("UserData") != null ? it.optJSONObject("UserData").optLong("PlaybackPositionTicks") / 10_000 : 0;
        long runMs = it.optLong("RunTimeTicks") / 10_000;
        if (resumeMs < 30_000 || (runMs > 0 && resumeMs > runMs * 0.95)) resumeMs = 0;   // 刚开头 / 快看完的从头放
        play(jellyfin.streamUrl(itemId, mediaSourceId), null, resumeMs);
        if (resumeMs > 0) { showToast("从 " + clock(resumeMs) + " 继续播放"); hideToastLater(); } else hideToastNow();
    }

    private void play(String url, String mime, long positionMs) {
        if (player == null) {
            player = new ExoPlayer.Builder(this).build();
            player.setVideoTextureView(surface);
            player.addListener(new Player.Listener() {
                @Override public void onVideoSizeChanged(VideoSize v) { fitVideo(v); }
                @Override public void onIsPlayingChanged(boolean playing) { updateBarState(); }
                @Override public void onPlaybackStateChanged(int state) { if (state == Player.STATE_ENDED) onEnded(); }
                @Override public void onPlayerError(PlaybackException e) { onError(e); }
                @Override public void onTracksChanged(Tracks t) { collectEmbedded(t); }
                @Override public void onCues(CueGroup g) { if (subKind == 2) showEmbeddedCues(g.cues); }
            });
        }
        applyTextTrackSelection();
        MediaItem.Builder mb = new MediaItem.Builder().setUri(url);
        if (mime != null) mb.setMimeType(mime);
        player.setMediaItem(mb.build(), positionMs);
        player.prepare();
        player.setPlayWhenReady(true);
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    /** 原文件放不了（编码 / 音轨电视解不了）：退到服务器转码一次。 */
    private void onError(PlaybackException e) {
        if (!triedTranscode) {
            triedTranscode = true;
            long pos = Math.max(0, player.getCurrentPosition());
            showToast("这个文件电视直接放不了，换成服务器转码…");
            play(jellyfin.transcodeUrl(itemId, mediaSourceId), MimeTypes.APPLICATION_M3U8, pos);
            return;
        }
        showToast("播放失败：" + e.getErrorCodeName());
    }

    private void onEnded() {
        if (finished) return;
        finished = true;
        saveProgress(true);
        if (seriesId == null) { finish(); return; }
        new Thread(() -> {
            try {
                JSONObject next = jellyfin.nextEpisode(seriesId, itemId);
                ui.post(() -> {
                    if (next == null) { showToast("这一季看完了"); ui.postDelayed(this::finish, 2500); return; }
                    nextPending = next;
                    countdownNext(next, 5);
                });
            } catch (Exception e) {
                ui.post(this::finish);
            }
        }, "player-next").start();
    }

    private void countdownNext(JSONObject next, int left) {
        if (isFinishing() || nextPending != next) return;
        if (left <= 0) { open(next.optString("Id")); return; }
        showToast("下一集  S" + next.optInt("ParentIndexNumber") + "E" + next.optInt("IndexNumber") + " · " + next.optString("Name")
                + "　" + left + " 秒后开始　确认立即播放 · 返回取消");
        ui.postDelayed(() -> countdownNext(next, left - 1), 1000);
    }

    private void saveProgress(boolean force) {
        if (player == null || itemId == null || jellyfin == null) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastSaveMs < 10_000) return;
        lastSaveMs = now;
        long pos = player.getCurrentPosition(), dur = player.getDuration();
        boolean played = finished || (dur > 0 && pos > dur * 0.95);
        final String id = itemId;
        new Thread(() -> {
            try {
                jellyfin.saveProgress(id, pos * 10_000, played);
            } catch (Exception ignored) {
            }
        }, "player-save").start();
    }

    // ---------------------------------------------------------------- 字幕

    /** 默认字幕：中文优先（语言字段 / 标题里带中文），外挂优先于内嵌；没有中文就先关着。 */
    private int pickDefaultSub() {
        int best = -1, bestScore = 0;
        for (int i = 0; i < textSubs.size(); i++) {
            JSONObject s = textSubs.get(i);
            String lang = s.optString("Language").toLowerCase(Locale.ROOT);
            String title = s.optString("DisplayTitle") + s.optString("Title");
            int score = 0;
            if (lang.startsWith("zh") || lang.equals("chi") || lang.equals("zho") || title.matches(".*(中文|简|繁|chs|cht).*")) score += 10;
            if (s.optBoolean("IsExternal")) score += 2;
            if (s.optBoolean("IsDefault")) score += 1;
            if (score > bestScore) { bestScore = score; best = i; }
        }
        return bestScore >= 10 ? best : -1;
    }

    private void loadTextSubtitle() {
        subs.load("");
        shown = null;
        showCue(null);
        if (subKind != 1) return;
        int index = textSubs.get(subIdx).optInt("Index");
        final String id = itemId, ms = mediaSourceId;
        new Thread(() -> {
            try {
                String srt = jellyfin.subtitleSrt(id, ms, index);
                ui.post(() -> { if (id.equals(itemId) && subKind == 1) subs.load(srt); });
            } catch (Exception e) {
                ui.post(() -> showToast("字幕加载失败：" + e.getMessage()));
            }
        }, "player-subs").start();
    }

    /** 选了「自带字幕轨」就让 ExoPlayer 解那一轨，否则 ExoPlayer 的字幕全关（自己渲染文本字幕，别叠两层）。 */
    private void applyTextTrackSelection() {
        if (player == null) return;
        androidx.media3.common.TrackSelectionParameters.Builder b = player.getTrackSelectionParameters().buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT);
        if (subKind == 2 && subIdx >= 0 && subIdx < embedded.size()) {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(new TrackSelectionOverride(embedded.get(subIdx).getMediaTrackGroup(), 0));
        } else {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
        }
        player.setTrackSelectionParameters(b.build());
    }

    private void collectEmbedded(Tracks t) {
        List<Tracks.Group> now = new ArrayList<>();
        for (Tracks.Group g : t.getGroups()) if (g.getType() == C.TRACK_TYPE_TEXT && g.isSupported()) now.add(g);
        if (now.size() == embedded.size()) return;   // 选轨导致的回调，列表没变
        embedded.clear();
        embedded.addAll(now);
        if (panel.getVisibility() == View.VISIBLE) rebuildPanel(false);
    }

    private static String embeddedName(Tracks.Group g, int i) {
        Format f = g.getTrackFormat(0);
        String lang = f.language == null ? "" : new Locale(f.language).getDisplayLanguage(Locale.CHINA);
        String label = f.label != null ? f.label : "";
        String kind = MimeTypes.APPLICATION_PGS.equals(f.sampleMimeType) ? "图形" : "文本";
        String name = (label.isEmpty() ? (lang.isEmpty() ? "字幕轨 " + (i + 1) : lang) : label);
        return name + "（" + kind + "）";
    }

    private void showEmbeddedCues(List<Cue> cues) {
        Bitmap bmp = null;
        StringBuilder text = new StringBuilder();
        for (Cue c : cues) {
            if (c.bitmap != null) bmp = c.bitmap;
            else if (c.text != null) { if (text.length() > 0) text.append('\n'); text.append(c.text); }
        }
        if (bmp != null) {
            subImage.setImageBitmap(bmp);
            subImage.setVisibility(View.VISIBLE);
            subBox.setVisibility(View.INVISIBLE);
            return;
        }
        subImage.setVisibility(View.GONE);
        if (text.length() == 0) { subBox.setVisibility(View.INVISIBLE); return; }
        subPrimary.setText(text);
        subSecondary.setVisibility(View.GONE);
        subBox.setVisibility(View.VISIBLE);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (player != null) {
                if (subKind == 1) showCue(subs.size() == 0 ? null : subs.at(player.getCurrentPosition(), offsetMs));
                else if (subKind == 0) showCue(null);
                if (bar.getVisibility() == View.VISIBLE) updateBar();
                if (player.isPlaying()) saveProgress(false);
            }
            ui.postDelayed(this, 80);
        }
    };

    private SubtitleEngine.Cue shown;

    private void showCue(SubtitleEngine.Cue c) {
        if (c == shown && (c != null || subBox.getVisibility() != View.VISIBLE)) return;
        shown = c;
        subImage.setVisibility(View.GONE);
        if (c == null) { subBox.setVisibility(View.INVISIBLE); return; }
        subPrimary.setText(c.primary);
        subSecondary.setText(c.secondary);
        subSecondary.setVisibility(c.secondary.isEmpty() ? View.GONE : View.VISIBLE);
        subBox.setVisibility(View.VISIBLE);
    }

    /** 三种样式：描边无底 / 半透明底 / 黑底；颜色白 / 黄；三档字号。 */
    private void applyStyle() {
        float px = Ui.px(this, SUB_SIZES[sizeIdx]);
        subPrimary.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        subSecondary.setTextSize(TypedValue.COMPLEX_UNIT_PX, px * 0.62f);
        int color = COLORS[colorIdx];
        subPrimary.setTextColor(color);
        subSecondary.setTextColor(colorIdx == 0 ? 0xFFE5E5EA : 0xFFFFF0A8);
        boolean outline = styleIdx == 0;
        float stroke = outline ? Math.max(3f, px * 0.11f) : 0;
        subPrimary.setOutline(stroke, 0xFF000000);
        subSecondary.setOutline(stroke * 0.8f, 0xFF000000);
        float shadow = outline ? px * 0.06f : 0;
        subPrimary.setShadowLayer(shadow, 0, shadow * 0.5f, 0xCC000000);
        subSecondary.setShadowLayer(shadow, 0, shadow * 0.5f, 0xCC000000);
        subBox.setBackground(styleIdx == 0 ? null : Ui.rounded(styleIdx == 1 ? 0x80000000 : 0xFF000000, px(12)));
        shown = null;
    }

    private void adjustOffset(int dir) {
        offsetMs += dir * OFFSET_STEP_MS;
        prefs.edit().putLong("offset:item:" + itemId, offsetMs).apply();
        shown = null;
        showToast("字幕时间 " + fmtOffset(offsetMs) + (offsetMs == 0 ? "" : offsetMs > 0 ? "　字幕晚一点出" : "　字幕早一点出"));
        hideToastLater();
    }

    // ---------------------------------------------------------------- 按键 / 点击

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (panel.getVisibility() == View.VISIBLE) {
            View f = getCurrentFocus();
            if (down && (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) && f != null && f.getTag() instanceof Runnable[]) {
                ((Runnable[]) f.getTag())[k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0].run();
                return true;
            }
            if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) {
                if (!down) closePanel();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        if (!down) return (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) ? onBack() : true;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_SPACE:
                if (nextPending != null) { JSONObject n = nextPending; nextPending = null; open(n.optString("Id")); return true; }
                togglePause();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                seek(-(e.getRepeatCount() > 0 ? SEEK_FAST_MS : SEEK_MS));
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                seek(e.getRepeatCount() > 0 ? SEEK_FAST_MS : SEEK_MS);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_MENU:
                openPanel();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                showBar();
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                return true;   // 抬起时处理
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private void togglePause() {
        if (player == null) return;
        player.setPlayWhenReady(!player.getPlayWhenReady());
        showBar();
    }

    private boolean onBack() {
        if (nextPending != null) { nextPending = null; finish(); return true; }
        if (bar.getVisibility() == View.VISIBLE && player != null && player.isPlaying()) { hideBarNow(); return true; }
        finish();
        return true;
    }

    private void seek(long delta) {
        if (player == null) return;
        long dur = player.getDuration();
        long to = Math.max(0, player.getCurrentPosition() + delta);
        if (dur > 0) to = Math.min(to, dur - 1000);
        player.seekTo(to);
        showBar();
    }

    // ---------------------------------------------------------------- 布局

    private View build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surface = new TextureView(this);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        // 触屏 / 鼠标（模拟器）：点画面 = 进度条没出来就显示，出来了就暂停 / 继续
        root.setOnClickListener(v -> {
            if (panel.getVisibility() == View.VISIBLE) closePanel();
            else if (bar.getVisibility() != View.VISIBLE) showBar();
            else togglePause();
        });

        subImage = new ImageView(this);
        subImage.setAdjustViewBounds(true);
        subImage.setVisibility(View.GONE);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        ip.bottomMargin = px(60);
        root.addView(subImage, ip);

        subBox = new LinearLayout(this);
        subBox.setOrientation(LinearLayout.VERTICAL);
        subBox.setGravity(Gravity.CENTER_HORIZONTAL);
        subBox.setPadding(px(24), px(8), px(24), px(10));
        subBox.setVisibility(View.INVISIBLE);
        subPrimary = subText(Typeface.DEFAULT_BOLD);
        subSecondary = subText(Typeface.DEFAULT);
        subBox.addView(subPrimary);
        subBox.addView(subSecondary);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        sp.bottomMargin = px(64);
        root.addView(subBox, sp);

        toast = Ui.text(this, "", 26, Color.WHITE, Typeface.DEFAULT);
        toast.setPadding(px(32), px(16), px(32), px(16));
        toast.setBackground(Ui.rounded(0xE61C1C1E, px(32)));
        toast.setVisibility(View.GONE);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tp.topMargin = px(48);
        root.addView(toast, tp);

        root.addView(buildBar(), new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        root.addView(buildPanel(), new FrameLayout.LayoutParams(px(PANEL_W), -1, Gravity.END));
        return root;
    }

    private OutlineTextView subText(Typeface tf) {
        OutlineTextView t = new OutlineTextView(this);
        t.setTypeface(tf);
        t.setGravity(Gravity.CENTER);
        t.setMaxWidth(px(1560));
        t.setLineSpacing(px(4), 1f);
        t.setIncludeFontPadding(false);
        int pad = px(6);   // 给描边留出位置，不然最外一圈被裁
        t.setPadding(pad, pad, pad, pad);
        return t;
    }

    /** 底部进度条：剧名 / 集数，圆角进度条 + 圆点，两端时间，一排操作提示小标签。 */
    private View buildBar() {
        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(px(96), px(240), px(96), px(52));
        // 多几个色标、拉高渐变区：三段式渐变在顶部会看出一条分界线
        bar.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xF2000000, 0xD9000000, 0x99000000, 0x40000000, 0x00000000}));
        bar.setVisibility(View.GONE);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.BOTTOM);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        barTitle = Ui.text(this, "", 44, Color.WHITE, Typeface.DEFAULT_BOLD);
        barSub = Ui.text(this, "", 24, 0xFFD0D0D5, Typeface.DEFAULT);
        barSub.setPadding(0, px(6), 0, 0);
        titles.addView(barTitle);
        titles.addView(barSub);
        head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        barState = Ui.text(this, "", 24, Color.WHITE, Typeface.DEFAULT_BOLD);
        barState.setPadding(px(22), px(8), px(22), px(9));
        barState.setBackground(Ui.rounded(0x33FFFFFF, px(20)));
        head.addView(barState);
        bar.addView(head);

        FrameLayout track = new FrameLayout(this);
        track.setClipChildren(false);
        View rail = new View(this);
        rail.setBackground(Ui.rounded(0x4DFFFFFF, px(5)));
        track.addView(rail, new FrameLayout.LayoutParams(-1, px(10), Gravity.CENTER_VERTICAL));
        progressFill = new View(this);
        progressFill.setBackground(Ui.rounded(Color.WHITE, px(5)));
        track.addView(progressFill, new FrameLayout.LayoutParams(0, px(10), Gravity.CENTER_VERTICAL));
        progressThumb = new View(this);
        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(Color.WHITE);
        progressThumb.setBackground(thumb);
        progressThumb.setElevation(px(6));
        track.addView(progressThumb, new FrameLayout.LayoutParams(px(26), px(26), Gravity.CENTER_VERTICAL));
        LinearLayout.LayoutParams trp = new LinearLayout.LayoutParams(-1, px(30));
        trp.topMargin = px(28);
        bar.addView(track, trp);

        LinearLayout times = new LinearLayout(this);
        times.setPadding(0, px(10), 0, 0);
        barPos = Ui.text(this, "", 24, Color.WHITE, Typeface.DEFAULT);
        times.addView(barPos, new LinearLayout.LayoutParams(0, -2, 1));
        barLeft = Ui.text(this, "", 24, 0xFFD0D0D5, Typeface.DEFAULT);
        times.addView(barLeft);
        bar.addView(times);

        LinearLayout hints = new LinearLayout(this);
        hints.setPadding(0, px(24), 0, 0);
        for (String[] h : new String[][]{{"‹ ›", "快退 / 快进"}, {"↑", "字幕"}, {"OK", "暂停"}, {"返回", "退出"}}) {
            hints.addView(Ui.chip(this, h[0], Color.WHITE, 0x33FFFFFF));
            TextView l = Ui.text(this, h[1], 22, Ui.TEXT_DIM, Typeface.DEFAULT);
            l.setPadding(0, px(2), px(34), 0);
            hints.addView(l);
        }
        bar.addView(hints);
        return bar;
    }

    /** 右侧字幕面板：字幕轨（文本 / 自带）→ 时间（偏移、记到整季、永久修正）→ 样式（样式 / 颜色 / 字号）。 */
    private View buildPanel() {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(px(48), px(56), px(48), px(40));
        panel.setBackground(new GradientDrawable(GradientDrawable.Orientation.RIGHT_LEFT, new int[]{0xFA141416, 0xF0141416}));
        panel.setVisibility(View.GONE);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView ic = new ImageView(this);
        ic.setImageDrawable(new Icons(Icons.Kind.CHAT, Color.WHITE));
        head.addView(ic, new LinearLayout.LayoutParams(px(36), px(36)));
        TextView t = Ui.text(this, "字幕", 40, Color.WHITE, Typeface.DEFAULT_BOLD);
        t.setPadding(px(16), 0, 0, 0);
        head.addView(t);
        panel.addView(head);
        ScrollView sv = new ScrollView(this);
        sv.setFocusable(false);
        sv.setVerticalScrollBarEnabled(false);
        Ui.noHighlight(sv);
        panelList = new LinearLayout(this);
        panelList.setOrientation(LinearLayout.VERTICAL);
        panelList.setPadding(0, px(12), 0, px(24));
        sv.addView(panelList);
        panel.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        return panel;
    }

    /** 重建面板内容；focusFirst 为 true 时焦点落到「字幕时间」那一行（最常用，也不会一按确认就把字幕关了）。 */
    private void rebuildPanel(boolean focusFirst) {
        View focused = getCurrentFocus();
        int focusIdx = focused != null && focused.getParent() == panelList ? panelList.indexOfChild(focused) : -1;
        panelList.removeAllViews();

        section("字幕轨");
        choiceRow("关闭字幕", subKind == 0, () -> selectSub(0, -1));
        for (int i = 0; i < textSubs.size(); i++) {
            final int idx = i;
            choiceRow(trackName(textSubs.get(i)), subKind == 1 && subIdx == i, () -> selectSub(1, idx));
        }
        if (!embedded.isEmpty()) {
            note("视频自带字幕轨 · 由播放器解码，不能调时间");
            for (int i = 0; i < embedded.size(); i++) {
                final int idx = i;
                choiceRow(embeddedName(embedded.get(i), i), subKind == 2 && subIdx == i, () -> selectSub(2, idx));
            }
        }

        section("时间");
        offsetRow = valueRow("字幕时间", fmtOffset(offsetMs), () -> { adjustOffset(-1); rebuildPanel(false); }, () -> { adjustOffset(1); rebuildPanel(false); });
        note("字幕比声音早就往右调，晚就往左调");
        actionRow("把这个时间记到整季", () -> {
            prefs.edit().putLong(offsetKey, offsetMs).apply();
            showToast("已记住：这一季都按 " + fmtOffset(offsetMs) + " 显示");
            hideToastLater();
        });
        actionRow("让 Agent 把字幕文件永久修正", this::askAgentToFix);

        section("样式");
        valueRow("样式", STYLE_NAMES[styleIdx], () -> cycleStyle(-1), () -> cycleStyle(1));
        valueRow("颜色", COLOR_NAMES[colorIdx], () -> cycleColor(-1), () -> cycleColor(1));
        valueRow("字号", SIZE_NAMES[sizeIdx], () -> cycleSize(-1), () -> cycleSize(1));

        View target = focusFirst ? offsetRow : (focusIdx >= 0 && focusIdx < panelList.getChildCount() ? panelList.getChildAt(focusIdx) : firstFocusable());
        if (target != null) target.requestFocus();
    }

    private View firstFocusable() {
        for (int i = 0; i < panelList.getChildCount(); i++) if (panelList.getChildAt(i).isFocusable()) return panelList.getChildAt(i);
        return null;
    }

    private void cycleStyle(int d) { styleIdx = (styleIdx + d + STYLE_NAMES.length) % STYLE_NAMES.length; prefs.edit().putInt("subStyle", styleIdx).apply(); applyStyle(); rebuildPanel(false); }
    private void cycleColor(int d) { colorIdx = (colorIdx + d + COLOR_NAMES.length) % COLOR_NAMES.length; prefs.edit().putInt("subColor", colorIdx).apply(); applyStyle(); rebuildPanel(false); }
    private void cycleSize(int d) { sizeIdx = Math.max(0, Math.min(SIZE_NAMES.length - 1, sizeIdx + d)); prefs.edit().putInt("subSize", sizeIdx).apply(); applyStyle(); rebuildPanel(false); }

    private void selectSub(int kind, int idx) {
        subKind = kind;
        subIdx = idx;
        applyTextTrackSelection();
        subImage.setVisibility(View.GONE);
        loadTextSubtitle();
        rebuildPanel(false);
    }

    private void section(String s) {
        TextView t = Ui.text(this, s, 22, 0xFF8E8E93, Typeface.DEFAULT_BOLD);
        t.setPadding(px(12), px(34), 0, px(12));
        panelList.addView(t);
    }

    private void note(String s) {
        TextView t = Ui.text(this, s, 20, 0xFF6E6E73, Typeface.DEFAULT);
        t.setPadding(px(12), px(10), 0, px(10));
        panelList.addView(t);
    }

    /** 面板通用行：左标题、右值；选中白底黑字。 */
    private LinearLayout row(String label, String value) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(px(26), px(18), px(22), px(18));
        GradientDrawable idle = Ui.rounded(0x0FFFFFFF, px(18));
        GradientDrawable on = Ui.rounded(Color.WHITE, px(18));
        r.setBackground(idle);
        r.setFocusable(true);
        r.setFocusableInTouchMode(true);
        r.setClickable(true);
        Ui.noHighlight(r);
        TextView l = Ui.text(this, label, 27, Color.WHITE, Typeface.DEFAULT);
        l.setSingleLine(true);
        l.setEllipsize(android.text.TextUtils.TruncateAt.END);
        r.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
        TextView v = Ui.text(this, value, 26, 0xFFAEAEB2, Typeface.DEFAULT);
        r.addView(v);
        r.setOnFocusChangeListener((view, has) -> {
            view.setBackground(has ? on : idle);
            l.setTextColor(has ? Color.BLACK : Color.WHITE);
            v.setTextColor(has ? 0xFF3A3A3C : 0xFFAEAEB2);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = px(8);
        panelList.addView(r, lp);
        return r;
    }

    private void choiceRow(String label, boolean selected, Runnable pick) {
        LinearLayout r = row(label, selected ? "✓" : "");
        if (selected) ((TextView) r.getChildAt(1)).setTextColor(0xFF30D158);
        r.setOnClickListener(v -> pick.run());
    }

    private View offsetRow;

    private View valueRow(String label, String value, Runnable left, Runnable right) {
        LinearLayout r = row(label, "‹  " + value + "  ›");
        r.setTag(new Runnable[]{left, right});   // dispatchKeyEvent 按左右时取出来调
        r.setOnClickListener(v -> right.run());
        return r;
    }

    private void actionRow(String label, Runnable act) {
        LinearLayout r = row(label, "›");
        r.setOnClickListener(v -> act.run());
    }

    private static String trackName(JSONObject s) {
        String t = s.optString("DisplayTitle");
        if (t.isEmpty()) t = s.optString("Language", "未知");
        return t.replace(" - SUBRIP", "").replace(" - ASS", "").replace(" - SSA", "").replace(" - 外部", "（外挂）");
    }

    private void openPanel() {
        hideBarNow();
        rebuildPanel(true);
        panel.setVisibility(View.VISIBLE);
        panel.setTranslationX(px(PANEL_W));
        panel.animate().translationX(0).setDuration(200).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        // 字幕往左让一让，调的时候能看见
        subBox.animate().translationX(-px(PANEL_W) / 2f).setDuration(200).start();
        subImage.animate().translationX(-px(PANEL_W) / 2f).setDuration(200).start();
    }

    private void closePanel() {
        panel.animate().translationX(px(PANEL_W)).setDuration(160).withEndAction(() -> panel.setVisibility(View.GONE)).start();
        subBox.animate().translationX(0).setDuration(160).start();
        subImage.animate().translationX(0).setDuration(160).start();
        root.requestFocus();
    }

    /** 永久修正：交给后端 Agent（docs/protocol.md 的 /tv/chat），不新增接口。 */
    private void askAgentToFix() {
        Gateway g = Services.gateway(this);
        if (g == null || subKind != 1 || offsetMs == 0) {
            showToast(offsetMs == 0 ? "时间是 0，不用修" : subKind != 1 ? "自带字幕轨改不了文件" : "没连后端，修不了");
            hideToastLater();
            return;
        }
        JSONObject s = textSubs.get(subIdx);
        String what = (seriesId != null ? item.optString("SeriesName") + " S" + item.optInt("ParentIndexNumber") + "E" + item.optInt("IndexNumber") : item.optString("Name"));
        String path = s.optString("Path", "");
        String msg = "把「" + what + "」的字幕整体平移 " + String.format(Locale.US, "%+.1f", offsetMs / 1000.0) + " 秒（正数 = 字幕往后挪）。"
                + (path.isEmpty() ? "是视频里的内嵌字幕轨「" + trackName(s) + "」，请导出成外挂字幕再平移。" : "字幕文件：" + path)
                + " 视频：" + item.optString("Path") + "。改完告诉我一声。";
        new Thread(() -> {
            try {
                g.chat(msg);
                ui.post(() -> {
                    prefs.edit().remove("offset:item:" + itemId).apply();   // 文件改好后下次打开就不该再叠偏移
                    showToast("已交给 Agent，改好后重新打开这一集就是准的");
                    hideToastLater();
                });
            } catch (Exception e) {
                ui.post(() -> showToast("没发出去：" + e.getMessage()));
            }
        }, "player-fix").start();
    }

    // ---------------------------------------------------------------- 进度条 / 提示

    private void fillBar() {
        boolean episode = item.optString("SeriesId", null) != null;
        barTitle.setText(episode ? item.optString("SeriesName") : item.optString("Name"));
        barSub.setText(episode ? "第 " + item.optInt("ParentIndexNumber") + " 季 第 " + item.optInt("IndexNumber") + " 集 · " + item.optString("Name")
                : (item.optInt("ProductionYear") > 0 ? item.optInt("ProductionYear") + " · 电影" : "电影"));
    }

    private void showBar() {
        if (bar.getVisibility() != View.VISIBLE) {
            bar.setVisibility(View.VISIBLE);
            bar.setAlpha(0f);
            bar.animate().alpha(1f).setDuration(160).start();
            liftSubtitles(true);
        }
        updateBar();
        ui.removeCallbacks(hideBar);
        ui.postDelayed(hideBar, 4000);
    }

    private final Runnable hideBar = () -> { if (player != null && player.isPlaying()) hideBarNow(); };

    private void hideBarNow() {
        ui.removeCallbacks(hideBar);
        if (bar.getVisibility() != View.VISIBLE) return;
        bar.animate().alpha(0f).setDuration(160).withEndAction(() -> bar.setVisibility(View.GONE)).start();
        liftSubtitles(false);
    }

    /** 进度条出来时字幕整体上移到它上方，别被盖住。 */
    private void liftSubtitles(boolean up) {
        float dy = up ? -px(300) : 0;
        subBox.animate().translationY(dy).setDuration(160).start();
        subImage.animate().translationY(dy).setDuration(160).start();
    }

    private void updateBar() {
        if (player == null) return;
        long pos = player.getCurrentPosition(), dur = Math.max(1, player.getDuration());
        View track = (View) progressFill.getParent();
        int w = track.getWidth();
        float f = (float) Math.min(1, pos / (double) dur);
        progressFill.getLayoutParams().width = (int) (w * f);
        progressFill.requestLayout();
        progressThumb.setTranslationX(w * f - px(13));
        barPos.setText(clock(pos) + " / " + clock(dur));
        barLeft.setText("还剩 " + clock(Math.max(0, dur - pos)));
        updateBarState();
    }

    private void updateBarState() {
        if (barState == null || player == null) return;
        boolean playing = player.getPlayWhenReady();
        barState.setText(playing ? "▶  播放中" : "❚❚  已暂停");
        if (!playing && bar.getVisibility() != View.VISIBLE) showBar();
        if (!playing) ui.removeCallbacks(hideBar);
    }

    private void showToast(String s) {
        toast.setText(s);
        if (toast.getVisibility() != View.VISIBLE) {
            toast.setVisibility(View.VISIBLE);
            toast.setAlpha(0f);
            toast.animate().alpha(1f).setDuration(140).start();
        }
        ui.removeCallbacks(hideToast);
    }

    private final Runnable hideToast = () -> toast.animate().alpha(0f).setDuration(200).withEndAction(() -> toast.setVisibility(View.GONE)).start();

    private void hideToastLater() {
        ui.removeCallbacks(hideToast);
        ui.postDelayed(hideToast, 2500);
    }

    private void hideToastNow() {
        ui.removeCallbacks(hideToast);
        toast.setVisibility(View.GONE);
    }

    /** 等比缩放进黑底：宽高比按视频实际像素和像素宽高比算。 */
    private void fitVideo(VideoSize v) {
        if (v.width == 0 || v.height == 0) return;
        float ratio = v.width * v.pixelWidthHeightRatio / v.height;
        int w = root.getWidth(), h = root.getHeight();
        if (w == 0 || h == 0) { root.post(() -> fitVideo(v)); return; }
        int vw = w, vh = Math.round(w / ratio);
        if (vh > h) { vh = h; vw = Math.round(h * ratio); }
        surface.setLayoutParams(new FrameLayout.LayoutParams(vw, vh, Gravity.CENTER));
    }

    private static String clock(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private static String fmtOffset(long ms) {
        return String.format(Locale.US, "%+.1f 秒", ms / 1000.0);
    }

    private int px(int v) {
        return Ui.px(this, v);
    }

    static void play(Context ctx, String itemId) {
        ctx.startActivity(new Intent(ctx, PlayerActivity.class).putExtra(EXTRA_ITEM, itemId));
    }
}
