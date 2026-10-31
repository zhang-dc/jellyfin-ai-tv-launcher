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
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * 内置播放器（设计稿 docs/player-design.md 字幕优先、docs/player-v3-design.md 控制栏 / 下一集 / 对齐台词）。
 *
 * 播放：Media3 ExoPlayer 直连 Jellyfin 原文件，放不了退到服务器转码 HLS。续播 / 进度 / 看完写回 Jellyfin 的 UserData。
 * 字幕两种来源，面板里分两组：
 *   - 文本字幕（外挂 + 能转文本的内嵌轨）：Jellyfin 转成 SRT，自己渲染（SubtitleEngine），能边看边调时间
 *   - 视频自带字幕轨：交给 ExoPlayer 解码（含蓝光 PGS 图形字幕），onCues 拿到每帧该显示的内容，画在同一位置；不能调时间
 * 字幕时间：左右键微调，或「对齐台词」——选一句、听到它时按 OK，直接算出偏移。调好的时间自动用到这一季后面几集。
 *
 * 遥控器（没有浮层时）：确认 = 暂停 / 继续；左右 = 快退 / 快进 10 秒，按住 = 拖动、松手才跳；上 = 字幕与音轨面板；
 * 下 = 进度条，再按下 = 进控制按钮行（字幕与音轨 · 字幕时间 · 选集 · 下一集 · 倍速）；返回 = 收起 / 退出。
 * 片尾开始（片尾标记 / 名为 Ending 的章节；都没有就最后 15 秒）弹「下一集」卡片倒计时；连播三集没人按键就改成问「还在看吗」。
 */
public class PlayerActivity extends Activity {

    static final String EXTRA_ITEM = "itemId";
    /**
     * 测试用（adb am start --ez noSave true）：不往 Jellyfin 写进度 / 看完。
     * 模拟器和电视用的是同一个 Jellyfin 账号，测试时乱写进度会打乱「最近播放」（2026-10-31 踩过）。
     */
    static final String EXTRA_NO_SAVE = "noSave";
    /** 测试用（--ez transcode true）：不试原文件，直接走服务器转码那条路 */
    static final String EXTRA_FORCE_TRANSCODE = "transcode";
    private boolean noSave;

    private static final long SEEK_MS = 10_000, OFFSET_STEP_MS = 100, OFFSET_FAST_MS = 500;
    /** 听到台词到按下 OK 的反应时间，对齐时扣掉 */
    private static final long REACTION_MS = 300;
    /** 下一集倒计时；连着自动播几集没人碰遥控器就停下来问 */
    private static final long UP_NEXT_MS = 10_000, UP_NEXT_TAIL_MS = 15_000;
    private static final int STILL_WATCHING_AFTER = 3;
    private static final int[] SUB_SIZES = {44, 54, 66};
    private static final String[] SIZE_NAMES = {"小", "中", "大"};
    private static final String[] STYLE_NAMES = {"描边", "半透明底", "黑底"};
    private static final String[] COLOR_NAMES = {"白色", "黄色"};
    private static final int[] COLORS = {Color.WHITE, 0xFFFFE066};
    /** 字幕离底边的高度：低 = 贴底；高 = 宽银幕电影放到画面里面 */
    private static final int[] SUB_BOTTOM = {64, 150, 250};
    private static final String[] POS_NAMES = {"低", "中", "高"};
    private static final float[] SPEEDS = {0.75f, 1f, 1.25f, 1.5f, 2f};
    private static final int PANEL_W = 680;
    private static final Pattern INTRO_CHAPTER = Pattern.compile("(?i)^\\s*(op|opening|intro|片头|オープニング)\\b.*");
    private static final Pattern CREDITS_CHAPTER = Pattern.compile("(?i)^\\s*(ed|ending|end credits|credits|片尾|エンディング)\\b.*");
    /** 「Chapter 3」「章节 3」「第 3 章」「00:12:00」这类没信息量的章节名不显示 */
    private static final Pattern DULL_CHAPTER = Pattern.compile("(?i)^\\s*(chapter|章节|第)?\\s*[\\d:.]+\\s*(章)?\\s*$");

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private Jellyfin jellyfin;
    private ExoPlayer player;
    /**
     * 用 TextureView 不用 SurfaceView：SurfaceView 是独立图层，被半透明浮层（进度条渐变）盖住的那块会换一种方式合成，
     * 色彩范围换算不同，浮层顶边出现一条亮度断层（MuMu 实测 67→79）。TextureView 和界面同一图层合成，没有这个问题。
     */
    private TextureView surface;
    private FrameLayout root;
    private OutlineTextView subPrimary, subSecondary;
    private LinearLayout subBox, bar, panel, panelList, controls, barTags;
    private ImageView subImage;
    private View progressFill, progressThumb, scrubMark;
    private FrameLayout track;
    private final List<View> chapterTicks = new ArrayList<>();
    private TextView barTitle, barSub, barPos, barLeft, toast;
    private TextView ctlNext, ctlEpisodes, ctlSpeed, ctlSync;
    private final SubtitleEngine subs = new SubtitleEngine();
    private SharedPreferences prefs;

    // 当前条目
    private String itemId, mediaSourceId, seriesId, seasonId, offsetKey;
    /** 播放器里实际装着的是哪一集（open 到 start 之间 itemId 已经换了、播放器还是上一集，这段时间不能存进度） */
    private String loadedId;
    private JSONObject item;
    private final List<JSONObject> textSubs = new ArrayList<>();
    /** 视频自带字幕轨（ExoPlayer 报上来的 TEXT 组）。 */
    private final List<Tracks.Group> embedded = new ArrayList<>();
    /** 当前选择：kind 0 = 关闭，1 = 文本字幕 textSubs[idx]，2 = 自带轨 embedded[idx]。 */
    private int subKind = 0, subIdx = -1;
    private long offsetMs = 0;
    private int sizeIdx, styleIdx, colorIdx, posIdx, speedIdx = 1;
    private boolean triedTranscode = false, finished = false;
    /** 当前在放的地址（网络出错时原样重连用）和连着重连了几次 */
    private String lastUrl, lastMime;
    private int ioRetries = 0;
    private long lastSaveMs = 0;
    /** 音轨（ExoPlayer 报上来的 AUDIO 组），面板「音轨」那一组用 */
    private final List<Tracks.Group> audios = new ArrayList<>();
    /** 服务器转码时 ExoPlayer 只看得到转出来的那一条音轨，换音轨得让 Jellyfin 按 AudioStreamIndex 重转 */
    private final List<JSONObject> serverAudios = new ArrayList<>();
    private int serverAudioIdx = -1;   // MediaStreams 里的 Index，打开时取 DefaultAudioStreamIndex
    /** 片头 / 片尾（毫秒；没有为 -1）。片头检测插件的 MediaSegments 优先，没有就看章节名 */
    private long introStart = -1, introEnd = -1, creditsStart = -1;
    private boolean segmentsFromServer = false;
    /** 章节（毫秒 + 名字），进度条上画刻度、拖动时显示章节名 */
    private long[] chapterStarts = new long[0];
    private String[] chapterNames = new String[0];
    private TextView skipButton;
    private ImageView barThumb, centerIcon;
    private View dim, spinner;

    /** 按住左右键拖动：松手才真正跳过去（每次 seek 都要重新缓冲，按住时一路跳会卡） */
    private boolean scrubbing = false;
    private long scrubTarget = 0;

    // 下一集
    private JSONObject nextEp;
    private LinearLayout upNext;
    private ImageView upNextThumb;
    private TextView upNextLabel, upNextTitle, upNextName, upNextPlayText, upNextCancel;
    private FrameLayout upNextPlay;
    private View upNextFill;
    /** 倒计时截止（elapsedRealtime）；UP_NEXT_AT_END = 跟着片子放完；Long.MAX_VALUE = 不倒计时（问还在看吗） */
    private static final long UP_NEXT_AT_END = -1;
    private long upNextDeadline, upNextTotal;
    /** 用户在片尾点了「取消」：这一集不再自动弹，真放完再问一次 */
    private boolean upNextDismissed = false;
    /** 连着自动播放了几集（期间没人按键） */
    private int autoPlays = 0;

    // 选集
    private LinearLayout epBox, epRow;
    private HorizontalScrollView epScroll;
    private TextView epHeader;

    // 对齐台词
    private LinearLayout syncBox, syncLines;
    private TextView syncValue;
    private int syncSel = -1;
    private boolean syncPinned = false;
    private String syncSig = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.fullscreen(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        jellyfin = Services.jellyfin(this);
        noSave = getIntent().getBooleanExtra(EXTRA_NO_SAVE, false);
        prefs = getSharedPreferences("player", Context.MODE_PRIVATE);
        sizeIdx = prefs.getInt("subSize", 1);
        styleIdx = prefs.getInt("subStyle", 0);
        colorIdx = prefs.getInt("subColor", 0);
        posIdx = Math.min(SUB_BOTTOM.length - 1, prefs.getInt("subPos", 0));
        setContentView(build());
        applyStyle();
        root.requestFocus();
        if (jellyfin == null) { showToast("没有配置 Jellyfin（assets/jellyfin.properties）"); return; }
        open(getIntent().getStringExtra(EXTRA_ITEM));
    }

    /** 播放器已经在前台时又让它放别的（PlayerActivity.play 再次调用）：直接换过去。 */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String id = intent.getStringExtra(EXTRA_ITEM);
        if (jellyfin == null || id == null || id.equals(itemId)) return;
        saveProgress(true);   // 按原来的开关存完这一集，再换新开关
        noSave = intent.getBooleanExtra(EXTRA_NO_SAVE, false);
        autoPlays = 0;
        open(id);
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
        io.shutdownNow();
        if (player != null) player.release();
    }

    // ---------------------------------------------------------------- 打开 / 播放

    private void open(String id) {
        itemId = id;
        loadedId = null;
        if (player != null) player.stop();   // 上一集别在加载新一集时接着放（也别被当成新一集存进度）
        finished = false;
        triedTranscode = false;
        ioRetries = 0;
        scrubbing = false;
        introStart = introEnd = creditsStart = -1;
        segmentsFromServer = false;
        nextEp = null;
        upNextDismissed = false;
        hideUpNext();
        closeEpisodesNow();
        closeSyncNow();
        if (skipButton != null) skipButton.setVisibility(View.GONE);
        showToast("正在打开…");
        io.execute(() -> {
            try {
                JSONObject it = jellyfin.item(id);
                ui.post(() -> start(it));
            } catch (Exception e) {
                ui.post(() -> showToast("打不开：" + e.getMessage()));
            }
        });
    }

    /** 换一集：先把这一集的进度存下来。 */
    private void switchTo(String id) {
        if (id == null || id.equals(itemId)) return;
        saveProgress(true);
        open(id);
    }

    private void start(JSONObject it) {
        if (!it.optString("Id").equals(itemId)) return;   // 加载途中又换了一集
        item = it;
        loadedId = itemId;
        JSONObject ms = it.optJSONArray("MediaSources").optJSONObject(0);
        mediaSourceId = ms.optString("Id");
        seriesId = it.optString("SeriesId", null);
        seasonId = it.optString("SeasonId", null);
        offsetKey = seriesId != null ? "offset:" + seriesId + ":" + it.optInt("ParentIndexNumber") : "offset:" + itemId;
        boolean ownOffset = prefs.contains("offset:item:" + itemId);
        offsetMs = prefs.getLong("offset:item:" + itemId, prefs.getLong(offsetKey, 0));
        loadChapters(it);
        fillBar(ms);
        loadSegments(itemId);
        loadNext();

        // 文本字幕：Jellyfin 能转 SRT 的（外挂 srt/ass + 内嵌文本轨）；图形字幕走「自带字幕轨」那一组
        textSubs.clear();
        embedded.clear();
        serverAudios.clear();
        serverAudioIdx = ms.optInt("DefaultAudioStreamIndex", -1);
        JSONArray streams = ms.optJSONArray("MediaStreams");
        for (int i = 0; streams != null && i < streams.length(); i++) {
            JSONObject s = streams.optJSONObject(i);
            if ("Subtitle".equals(s.optString("Type")) && s.optBoolean("IsTextSubtitleStream")) textSubs.add(s);
            if ("Audio".equals(s.optString("Type"))) serverAudios.add(s);
        }
        int def = pickDefaultSub();
        subKind = def >= 0 ? 1 : 0;
        subIdx = def;
        loadTextSubtitle();

        long resumeMs = it.optJSONObject("UserData") != null ? it.optJSONObject("UserData").optLong("PlaybackPositionTicks") / 10_000 : 0;
        long runMs = it.optLong("RunTimeTicks") / 10_000;
        if (resumeMs < 30_000 || (runMs > 0 && resumeMs > runMs * 0.95)) resumeMs = 0;   // 刚开头 / 快看完的从头放
        if (getIntent().getBooleanExtra(EXTRA_FORCE_TRANSCODE, false)) {
            triedTranscode = true;
            play(jellyfin.transcodeUrl(itemId, mediaSourceId, serverAudioIdx), MimeTypes.APPLICATION_M3U8, resumeMs);
            fillTags(ms);
        } else {
            play(jellyfin.streamUrl(itemId, mediaSourceId), null, resumeMs);
        }
        String note = !ownOffset && offsetMs != 0 && subKind == 1 ? "字幕时间 " + fmtOffset(offsetMs) + "（沿用这一季）" : "";
        if (resumeMs > 0) note = "从 " + clock(resumeMs) + " 继续播放" + (note.isEmpty() ? "" : "　·　" + note);
        if (!note.isEmpty()) { showToast(note); hideToastLater(); } else hideToastNow();
    }

    private void play(String url, String mime, long positionMs) {
        lastUrl = url;
        lastMime = mime;
        if (player == null) {
            player = new ExoPlayer.Builder(this).build();
            player.setVideoTextureView(surface);
            player.addListener(new Player.Listener() {
                @Override public void onVideoSizeChanged(VideoSize v) { fitVideo(v); }
                @Override public void onIsPlayingChanged(boolean playing) { updateBarState(); }
                @Override public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_ENDED) onEnded();
                    showBuffering(state == Player.STATE_BUFFERING);
                    if (state == Player.STATE_READY) ioRetries = 0;
                }
                @Override public void onPlayerError(PlaybackException e) { onError(e); }
                @Override public void onTracksChanged(Tracks t) { collectAudio(t); collectEmbedded(t); }
                @Override public void onCues(CueGroup g) { if (subKind == 2) showEmbeddedCues(g.cues); }
            });
        }
        applyTextTrackSelection();
        MediaItem.Builder mb = new MediaItem.Builder().setUri(url);
        if (mime != null) mb.setMimeType(mime);
        player.setMediaItem(mb.build(), positionMs);
        player.setPlaybackSpeed(SPEEDS[speedIdx]);
        player.prepare();
        player.setPlayWhenReady(true);
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    /** 原文件放不了（编码 / 音轨电视解不了）：退到服务器转码一次。 */
    private void onError(PlaybackException e) {
        android.util.Log.w("TvPlayer", "playback error " + e.getErrorCodeName() + (triedTranscode ? " (transcoding)" : " (direct)") + " item=" + itemId, e);
        long pos = Math.max(0, player.getCurrentPosition());
        // 网络类错误（读超时、连接断开）多半是一时的：原样重连两次，别一抖就改转码
        boolean network = e.errorCode >= PlaybackException.ERROR_CODE_IO_UNSPECIFIED && e.errorCode < PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED;
        if (network && ioRetries < 2) {
            ioRetries++;
            showToast("网络卡了一下，重新连接…");
            hideToastLater();
            play(lastUrl, lastMime, pos);
            return;
        }
        if (!triedTranscode) {
            triedTranscode = true;
            showToast("这个文件电视直接放不了（" + e.getErrorCodeName() + "），换成服务器转码…");
            hideToastLater();
            play(jellyfin.transcodeUrl(itemId, mediaSourceId, serverAudioIdx), MimeTypes.APPLICATION_M3U8, pos);
            if (item != null) fillTags(item.optJSONArray("MediaSources").optJSONObject(0));
            return;
        }
        showToast("播放失败：" + e.getErrorCodeName());
    }

    /** 放完：下一集卡片已经在倒计时就立即播；被取消过 / 没弹过就再弹一次；没有下一集退出。 */
    private void onEnded() {
        if (finished) return;
        finished = true;
        saveProgress(true);
        if (seriesId == null) { finish(); return; }
        if (nextEp == null) {
            showToast("全剧看完了");
            ui.postDelayed(this::finish, 2500);
            return;
        }
        if (upNext.getVisibility() == View.VISIBLE && upNextDeadline != Long.MAX_VALUE) { playNext(true); return; }
        showUpNext(SystemClock.elapsedRealtime() + UP_NEXT_MS);
    }

    private void saveProgress(boolean force) {
        if (player == null || itemId == null || jellyfin == null || !itemId.equals(loadedId) || noSave) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastSaveMs < 10_000) return;
        lastSaveMs = now;
        long pos = player.getCurrentPosition(), dur = player.getDuration();
        // 进了片尾就算看完（从片尾直接跳下一集时，这一集别留在「看到一半」里）
        boolean played = finished || (dur > 0 && pos > dur * 0.95) || (creditsStart > 0 && pos >= creditsStart);
        final String id = itemId;
        io.execute(() -> {
            try {
                jellyfin.saveProgress(id, pos * 10_000, played);
            } catch (Exception ignored) {
            }
        });
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
        io.execute(() -> {
            try {
                String srt = jellyfin.subtitleSrt(id, ms, index);
                ui.post(() -> { if (id.equals(itemId) && subKind == 1) subs.load(srt); });
            } catch (Exception e) {
                ui.post(() -> showToast("字幕加载失败：" + e.getMessage()));
            }
        });
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

    private void collectAudio(Tracks t) {
        audios.clear();
        for (Tracks.Group g : t.getGroups()) if (g.getType() == C.TRACK_TYPE_AUDIO && g.isSupported()) audios.add(g);
    }

    /** 转码播放时换音轨：带着当前位置让服务器按新音轨重转。 */
    private void selectServerAudio(JSONObject stream) {
        if (player == null) return;
        serverAudioIdx = stream.optInt("Index");
        long pos = Math.max(0, player.getCurrentPosition());
        play(jellyfin.transcodeUrl(itemId, mediaSourceId, serverAudioIdx), MimeTypes.APPLICATION_M3U8, pos);
        showToast("音轨换成 " + stream.optString("DisplayTitle") + "…");
        hideToastLater();
        rebuildPanel(false);
    }

    private boolean serverAudioSelected(JSONObject stream) {
        return stream.optInt("Index") == serverAudioIdx;
    }

    private void selectAudio(int idx) {
        if (player == null || idx < 0 || idx >= audios.size()) return;
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                .setOverrideForType(new TrackSelectionOverride(audios.get(idx).getMediaTrackGroup(), 0)).build());
        ui.postDelayed(() -> rebuildPanel(false), 300);   // 等 onTracksChanged 回来再刷勾
    }

    /** 「英语 · 5.1」「国语 · 立体声」 */
    private static String audioName(Tracks.Group g, int i) {
        Format f = g.getTrackFormat(0);
        String lang = f.language == null || "und".equals(f.language) ? "" : new Locale(f.language).getDisplayLanguage(Locale.CHINA);
        String name = f.label != null && !f.label.isEmpty() ? f.label : lang.isEmpty() ? "音轨 " + (i + 1) : lang;
        int ch = f.channelCount;
        String layout = ch == 1 ? "单声道" : ch == 2 ? "立体声" : ch == 6 ? "5.1" : ch == 8 ? "7.1" : ch > 0 ? ch + " 声道" : "";
        return layout.isEmpty() ? name : name + " · " + layout;
    }

    // ---------------------------------------------------------------- 片头 / 片尾 / 章节

    /**
     * 章节：进度条刻度 + 拖动时显示章节名；章节名是 Opening / Ending 这类时顺便当片头 / 片尾用
     * （本机 Jellyfin 没装片头检测插件，动画的 mkv 章节名却常常就是这些）。
     */
    private void loadChapters(JSONObject it) {
        JSONArray ch = it.optJSONArray("Chapters");
        int n = ch == null ? 0 : ch.length();
        chapterStarts = new long[n];
        chapterNames = new String[n];
        for (int i = 0; i < n; i++) {
            chapterStarts[i] = ch.optJSONObject(i).optLong("StartPositionTicks") / 10_000;
            chapterNames[i] = ch.optJSONObject(i).optString("Name");
        }
        long run = it.optLong("RunTimeTicks") / 10_000;
        for (int i = 0; i < n; i++) {
            long end = i + 1 < n ? chapterStarts[i + 1] : run;
            if (introEnd < 0 && INTRO_CHAPTER.matcher(chapterNames[i]).matches() && end - chapterStarts[i] <= 240_000 && end > chapterStarts[i]) {
                introStart = chapterStarts[i];
                introEnd = end;
            }
            if (creditsStart < 0 && CREDITS_CHAPTER.matcher(chapterNames[i]).matches() && run > 0 && chapterStarts[i] > run / 2) {
                creditsStart = chapterStarts[i];
            }
        }
    }

    private String chapterAt(long pos) {
        String name = null;
        for (int i = 0; i < chapterStarts.length; i++) if (chapterStarts[i] <= pos) name = chapterNames[i];
        return name == null || name.isEmpty() || DULL_CHAPTER.matcher(name).matches() ? null : name;
    }

    /** Jellyfin 10.10+ 的 MediaSegments：要装片头检测插件（Intro Skipper 等）才有数据；有就盖过章节推断的。 */
    private void loadSegments(String id) {
        io.execute(() -> {
            try {
                JSONArray segs = jellyfin.mediaSegments(id);
                ui.post(() -> {
                    if (!id.equals(itemId)) return;
                    for (int i = 0; i < segs.length(); i++) {
                        JSONObject g = segs.optJSONObject(i);
                        long a = g.optLong("StartTicks") / 10_000, b = g.optLong("EndTicks") / 10_000;
                        if ("Intro".equals(g.optString("Type")) && b > a) { introStart = a; introEnd = b; segmentsFromServer = true; }
                        if ("Outro".equals(g.optString("Type")) && a > 0) { creditsStart = a; segmentsFromServer = true; }
                    }
                });
            } catch (Exception ignored) {
            }
        });
    }

    private void checkSegments(long pos) {
        boolean inIntro = introEnd > 0 && pos >= introStart && pos < introEnd - 1500
                && upNext.getVisibility() != View.VISIBLE && syncBox.getVisibility() != View.VISIBLE
                && bar.getVisibility() != View.VISIBLE && epBox.getVisibility() != View.VISIBLE;   // 进度条出来时确认键是暂停，按钮别叠在它上面
        if (inIntro != (skipButton.getVisibility() == View.VISIBLE)) {
            if (inIntro) { skipButton.setVisibility(View.VISIBLE); skipButton.setAlpha(0f); skipButton.animate().alpha(1f).setDuration(200).start(); }
            else skipButton.setVisibility(View.GONE);
        }
        checkUpNext(pos);
    }

    private void skipIntro() {
        if (player == null || introEnd <= 0) return;
        player.seekTo(introEnd);
        skipButton.setVisibility(View.GONE);
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
                checkSegments(player.getCurrentPosition());
                if (syncBox.getVisibility() == View.VISIBLE) refreshSync();
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

    /** 三种样式：描边无底 / 半透明底 / 黑底；颜色白 / 黄；三档字号；三档高度。 */
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
        ((FrameLayout.LayoutParams) subBox.getLayoutParams()).bottomMargin = px(SUB_BOTTOM[posIdx]);
        ((FrameLayout.LayoutParams) subImage.getLayoutParams()).bottomMargin = px(SUB_BOTTOM[posIdx] - 4);
        subBox.requestLayout();
        subImage.requestLayout();
        shown = null;
    }

    /** 调字幕时间：这一集记一份，同时作为这一季的默认值（同一季的片源 / 字幕通常差得一样）。 */
    private void setOffset(long ms) {
        offsetMs = Math.round(ms / 100.0) * 100;
        prefs.edit().putLong("offset:item:" + itemId, offsetMs).putLong(offsetKey, offsetMs).apply();
        shown = null;
    }

    private void adjustOffset(int dir, boolean fast) {
        setOffset(offsetMs + dir * (fast ? OFFSET_FAST_MS : OFFSET_STEP_MS));
        if (syncBox.getVisibility() == View.VISIBLE) { refreshSync(); return; }
        showToast("字幕时间 " + fmtOffset(offsetMs) + (offsetMs == 0 ? "" : offsetMs > 0 ? "　字幕晚一点出" : "　字幕早一点出"));
        hideToastLater();
    }

    // ---------------------------------------------------------------- 对齐台词

    /**
     * 对齐台词：屏幕上方列出前后几句字幕，选中一句，听到演员说这句时按 OK，偏移 = 当前播放位置 − 这句的时间。
     * 字幕差好几秒时比按 0.1 秒一档调快得多。不动上下键时选中项自动跟着「下一句要出的」走（刚出的那句多留 3 秒给人反应）。
     */
    private void openSync() {
        if (subKind != 1 || subs.size() == 0) {
            showToast(subKind == 2 ? "视频自带的字幕轨不能调时间，换一条文本字幕试试" : "先在「字幕与音轨」里选一条字幕");
            hideToastLater();
            return;
        }
        if (panel.getVisibility() == View.VISIBLE) closePanel();
        hideBarNow();
        hideToastNow();
        syncPinned = false;
        syncSig = "";
        refreshSync();
        syncBox.setVisibility(View.VISIBLE);
        syncBox.setAlpha(0f);
        syncBox.setTranslationY(-px(40));
        syncBox.animate().alpha(1f).translationY(0).setDuration(180).start();
        root.requestFocus();
    }

    private void closeSync() {
        if (syncBox.getVisibility() != View.VISIBLE) return;
        syncBox.animate().alpha(0f).setDuration(150).withEndAction(() -> syncBox.setVisibility(View.GONE)).start();
        showToast("字幕时间 " + fmtOffset(offsetMs) + "，这一季后面几集也按这个来");
        hideToastLater();
    }

    private void closeSyncNow() {
        if (syncBox != null) syncBox.setVisibility(View.GONE);
    }

    private boolean syncKey(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) adjustOffset(k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1, e.getRepeatCount() > 2);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down && subs.size() > 0) {
                    syncPinned = true;
                    syncSel = Math.max(0, Math.min(subs.size() - 1, syncSel + (k == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1)));
                    refreshSync();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (down && e.getRepeatCount() == 0 && player != null && syncSel >= 0 && syncSel < subs.size()) {
                    setOffset(player.getCurrentPosition() - REACTION_MS - subs.get(syncSel).start);
                    syncPinned = false;
                    refreshSync();
                    flash(syncValue);
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_SPACE:
                if (down) togglePause();
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_MENU:
                if (!down) closeSync();
                return true;
            default:
                return true;
        }
    }

    private void refreshSync() {
        if (player == null || subs.size() == 0) return;
        long t = player.getCurrentPosition() - offsetMs;
        int n = subs.size();
        if (!syncPinned || syncSel < 0) {
            int next = subs.nextIndex(t), prev = next - 1;
            syncSel = prev >= 0 && t - subs.get(prev).start < 3000 ? prev : Math.min(next, n - 1);
        }
        int active = -1;
        for (int i = Math.max(0, subs.nextIndex(t) - 1); i >= 0 && i >= subs.nextIndex(t) - 3; i--) {
            if (t >= subs.get(i).start && t < subs.get(i).end) { active = i; break; }
        }
        int rows = syncLines.getChildCount();
        int first = Math.max(0, Math.min(syncSel - rows / 2, n - rows));
        String sig = first + ":" + syncSel + ":" + active + ":" + offsetMs + ":" + syncPinned;
        if (sig.equals(syncSig)) return;
        syncSig = sig;
        syncValue.setText(fmtOffset(offsetMs));
        for (int r = 0; r < rows; r++) {
            LinearLayout row = (LinearLayout) syncLines.getChildAt(r);
            int i = first + r;
            if (i >= n) { row.setVisibility(View.INVISIBLE); continue; }
            row.setVisibility(View.VISIBLE);
            SubtitleEngine.Cue c = subs.get(i);
            TextView time = (TextView) row.getChildAt(0), line = (TextView) row.getChildAt(1);
            time.setText(clock(Math.max(0, c.start + offsetMs)));
            String text = (c.primary + (c.secondary.isEmpty() ? "" : "　" + c.secondary)).replace('\n', ' ');
            line.setText(text);
            boolean sel = i == syncSel;
            row.setBackground(sel ? Ui.rounded(Color.WHITE, px(14)) : null);
            time.setTextColor(sel ? 0xFF3A3A3C : i == active ? Ui.ACCENT : 0xFF8E8E93);
            line.setTextColor(sel ? Color.BLACK : i == active ? Color.WHITE : 0xFFAEAEB2);
        }
    }

    /** 数值刚被改过：放大一下再回去，让人知道生效了。 */
    private static void flash(View v) {
        v.animate().cancel();
        v.setScaleX(1.25f);
        v.setScaleY(1.25f);
        v.animate().scaleX(1f).scaleY(1f).setDuration(260).start();
    }

    // ---------------------------------------------------------------- 下一集

    /** 一打开就把下一集查好：控制栏「下一集」按钮、片尾卡片都要用。 */
    private void loadNext() {
        if (seriesId == null) { updateControls(); return; }
        final String id = itemId, sid = seriesId;
        io.execute(() -> {
            try {
                JSONObject n = jellyfin.nextEpisode(sid, id);
                ui.post(() -> { if (id.equals(itemId)) { nextEp = n; updateControls(); } });
            } catch (Exception ignored) {
            }
        });
        updateControls();
    }

    /**
     * 片尾到了就弹下一集：有片尾标记 / Ending 章节 → 那一刻起倒计时 10 秒；都没有 → 最后 15 秒弹，跟着片子放完再播。
     * 弹出后又往回拖到片尾之前，卡片收起，下次进片尾再弹。
     */
    private void checkUpNext(long pos) {
        if (nextEp == null || seriesId == null || player == null) return;
        boolean visible = upNext.getVisibility() == View.VISIBLE;
        if (finished) { if (visible) updateUpNextCountdown(); return; }   // 放完后卡片继续倒计时
        long dur = player.getDuration();
        boolean inCredits = creditsStart > 0 ? pos >= creditsStart : dur > 60_000 && dur - pos <= UP_NEXT_TAIL_MS;
        if (visible && !inCredits && !scrubbing) { hideUpNext(); return; }
        if (!visible && inCredits && !upNextDismissed && !scrubbing && player.isPlaying()) {
            showUpNext(creditsStart > 0 ? SystemClock.elapsedRealtime() + UP_NEXT_MS : UP_NEXT_AT_END);
        }
        if (visible) updateUpNextCountdown();
    }

    private void showUpNext(long deadline) {
        if (nextEp == null) return;
        hideBarNow();
        closeEpisodesNow();
        if (panel.getVisibility() == View.VISIBLE) closePanel();
        skipButton.setVisibility(View.GONE);
        boolean ask = autoPlays >= STILL_WATCHING_AFTER;
        upNextDeadline = ask ? Long.MAX_VALUE : deadline;
        upNextTotal = deadline == UP_NEXT_AT_END && player != null ? Math.max(1, player.getDuration() - player.getCurrentPosition()) : UP_NEXT_MS;
        upNextLabel.setText(ask ? "还在看吗？" : "下一集");
        upNextTitle.setText(seLabel(nextEp));
        upNextName.setText(nextEp.optString("Name"));
        upNextCancel.setText(finished ? "退出" : "看片尾");
        loadInto(nextEp.optJSONObject("ImageTags") != null && nextEp.optJSONObject("ImageTags").has("Primary")
                ? "/Items/" + nextEp.optString("Id") + "/Images/Primary?maxWidth=400&quality=85"
                : "/Items/" + seriesId + "/Images/Backdrop?maxWidth=400&quality=85", upNextThumb);
        upNextFill.setScaleX(0f);
        updateUpNextCountdown();
        if (upNext.getVisibility() != View.VISIBLE) {
            upNext.setVisibility(View.VISIBLE);
            upNext.setAlpha(0f);
            upNext.setTranslationX(px(80));
            upNext.animate().alpha(1f).translationX(0).setDuration(220).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        }
        upNextPlay.requestFocus();
    }

    private void updateUpNextCountdown() {
        if (upNextDeadline == Long.MAX_VALUE) { upNextPlayText.setText("播放下一集"); upNextFill.setScaleX(0f); return; }
        long left = upNextDeadline == UP_NEXT_AT_END
                ? (player == null ? 0 : Math.max(0, player.getDuration() - player.getCurrentPosition()))
                : Math.max(0, upNextDeadline - SystemClock.elapsedRealtime());
        upNextFill.setScaleX(Math.min(1f, 1f - left / (float) upNextTotal));
        upNextPlayText.setText("播放下一集  " + (left + 999) / 1000);
        if (upNextDeadline != UP_NEXT_AT_END && left == 0) playNext(true);
    }

    private void hideUpNext() {
        if (upNext == null || upNext.getVisibility() != View.VISIBLE) return;
        upNext.animate().alpha(0f).setDuration(150).withEndAction(() -> upNext.setVisibility(View.GONE)).start();
        root.requestFocus();
    }

    /** 卡片上点「看片尾」/ 返回键：这一集不再弹（真放完再问一次）；已经放完了就退出。 */
    private void cancelUpNext() {
        if (finished) { finish(); return; }
        upNextDismissed = true;
        hideUpNext();
    }

    private void playNext(boolean auto) {
        if (nextEp == null) return;
        autoPlays = auto ? autoPlays + 1 : 0;
        if (auto && creditsStart <= 0 && player != null && player.getPlaybackState() != Player.STATE_ENDED) finished = true;
        switchTo(nextEp.optString("Id"));
    }

    // ---------------------------------------------------------------- 选集

    /** 底部一排本季单集卡片，焦点落在正在放的那集；确认换集，返回 / 上键收起。 */
    private void openEpisodes() {
        if (seriesId == null || seasonId == null) return;
        hideBarNow();
        epHeader.setText(item.optString("SeasonName", "第 " + item.optInt("ParentIndexNumber") + " 季") + "　·　选集");
        epRow.removeAllViews();
        epBox.setVisibility(View.VISIBLE);
        epBox.setAlpha(0f);
        epBox.animate().alpha(1f).setDuration(180).start();
        liftSubtitles(true);
        final String sid = seriesId, season = seasonId, current = itemId;
        io.execute(() -> {
            try {
                JSONArray eps = jellyfin.episodes(sid, season);
                ui.post(() -> {
                    if (!current.equals(itemId) || epBox.getVisibility() != View.VISIBLE) return;
                    View focus = null;
                    for (int i = 0; i < eps.length(); i++) {
                        View c = episodeCard(eps.optJSONObject(i));
                        epRow.addView(c);
                        if (current.equals(eps.optJSONObject(i).optString("Id"))) focus = c;
                    }
                    View f = focus != null ? focus : epRow.getChildCount() > 0 ? epRow.getChildAt(0) : null;
                    if (f != null) f.post(f::requestFocus);
                });
            } catch (Exception e) {
                ui.post(() -> { showToast("选集加载失败：" + e.getMessage()); hideToastLater(); closeEpisodes(); });
            }
        });
    }

    private void closeEpisodes() {
        if (epBox.getVisibility() != View.VISIBLE) return;
        epBox.animate().alpha(0f).setDuration(150).withEndAction(() -> epBox.setVisibility(View.GONE)).start();
        liftSubtitles(false);
        root.requestFocus();
    }

    private void closeEpisodesNow() {
        if (epBox == null || epBox.getVisibility() != View.VISIBLE) return;
        epBox.setVisibility(View.GONE);
        liftSubtitles(false);
    }

    private static final int EP_W = 320, EP_H = 180;

    private View episodeCard(JSONObject e) {
        String id = e.optString("Id");
        boolean current = id.equals(itemId);
        FrameLayout c = new FrameLayout(this);
        c.setFocusable(true);
        c.setFocusableInTouchMode(true);
        c.setClickable(true);
        Ui.noHighlight(c);
        c.setClipToOutline(true);
        c.setBackground(Ui.rounded(0xFF1C1C1E, px(14)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(EP_W), px(EP_H));
        lp.rightMargin = px(24);
        c.setLayoutParams(lp);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        c.addView(iv, new FrameLayout.LayoutParams(-1, -1));
        JSONObject tags = e.optJSONObject("ImageTags");
        if (tags != null && tags.has("Primary")) loadInto("/Items/" + id + "/Images/Primary?maxWidth=400&quality=80", iv);
        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0x22000000, 0xE6000000}));
        c.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        TextView t = Ui.text(this, e.optInt("IndexNumber") + ". " + e.optString("Name"), 22, Color.WHITE, Typeface.DEFAULT_BOLD);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setPadding(px(14), 0, px(14), px(14));
        c.addView(t, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        JSONObject ud = e.optJSONObject("UserData");
        if (current) {
            TextView now = Ui.chip(this, "正在播放", Color.BLACK, 0xE6FFFFFF);
            FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
            np.setMargins(px(10), px(10), 0, 0);
            c.addView(now, np);
        } else if (ud != null && ud.optBoolean("Played")) {
            TextView check = Ui.text(this, "✓", 20, Color.BLACK, Typeface.DEFAULT_BOLD);
            check.setGravity(Gravity.CENTER);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(0xE6FFFFFF);
            check.setBackground(dot);
            FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(px(32), px(32), Gravity.TOP | Gravity.END);
            cp.setMargins(0, px(10), px(10), 0);
            c.addView(check, cp);
        } else if (ud != null && ud.optDouble("PlayedPercentage", 0) > 0) {
            View fill = new View(this);
            fill.setBackgroundColor(Ui.ACCENT);
            c.addView(fill, new FrameLayout.LayoutParams(Math.max(px(6), (int) (px(EP_W) * ud.optDouble("PlayedPercentage") / 100)), px(6), Gravity.BOTTOM));
        }
        Ui.focusRing(this, c, 1.08f);
        View.OnFocusChangeListener base = c.getOnFocusChangeListener();
        c.setOnFocusChangeListener((v, has) -> {
            base.onFocusChange(v, has);
            v.setElevation(has ? px(16) : 0);
            if (has) epScroll.smoothScrollTo(v.getLeft() + v.getWidth() / 2 - epScroll.getWidth() / 2, 0);
        });
        c.setOnClickListener(v -> { if (current) closeEpisodes(); else { autoPlays = 0; switchTo(id); } });
        return c;
    }

    private static String seLabel(JSONObject e) {
        return "第 " + e.optInt("ParentIndexNumber") + " 季 第 " + e.optInt("IndexNumber") + " 集";
    }

    // ---------------------------------------------------------------- 按键 / 点击

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        boolean back = k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE;
        if (down) autoPlays = 0;   // 有人在按遥控器，就不问「还在看吗」
        if (panel.getVisibility() == View.VISIBLE) {
            View f = getCurrentFocus();
            if (down && (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) && f != null && f.getTag() instanceof Runnable[]) {
                ((Runnable[]) f.getTag())[k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0].run();
                return true;
            }
            if (back) {
                if (!down) closePanel();
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        if (syncBox.getVisibility() == View.VISIBLE) return syncKey(e);
        if (upNext.getVisibility() == View.VISIBLE) {
            if (back) { if (!down) cancelUpNext(); return true; }
            if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            if (k == KeyEvent.KEYCODE_MEDIA_NEXT) { if (down) playNext(false); return true; }
            if (!upNext.hasFocus()) upNextPlay.requestFocus();
            return super.dispatchKeyEvent(e);   // 左右在两个按钮间切换，确认点按钮
        }
        if (epBox.getVisibility() == View.VISIBLE) {
            if (back || k == KeyEvent.KEYCODE_DPAD_UP) { if (!down) closeEpisodes(); return true; }
            if (k == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            return super.dispatchKeyEvent(e);
        }
        if (controls.hasFocus()) {
            if (down) showBar();   // 在按钮行里操作就别自动收起
            if (back) { if (!down) { root.requestFocus(); hideBarNow(); } return true; }
            if (k == KeyEvent.KEYCODE_DPAD_UP) { if (down) root.requestFocus(); return true; }
            if (k == KeyEvent.KEYCODE_DPAD_DOWN) return true;
            return super.dispatchKeyEvent(e);
        }
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: {
                int dir = k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ? 1 : -1;
                if (down) { if (e.getRepeatCount() == 0) seek(dir * SEEK_MS); else scrub(dir, e.getRepeatCount()); }
                else if (scrubbing) commitScrub();
                return true;
            }
        }
        if (!down) return back ? onBack() : true;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_SPACE:
                if (e.getRepeatCount() > 0) return true;
                if (skipButton.getVisibility() == View.VISIBLE && bar.getVisibility() != View.VISIBLE) { skipIntro(); return true; }
                togglePause();
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_MENU:
                openPanel();
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (bar.getVisibility() == View.VISIBLE) focusControls();
                else showBar();
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                playNext(false);
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
        boolean play = !player.getPlayWhenReady();
        player.setPlayWhenReady(play);
        showCenterIcon(play);
        showBar();
    }

    private boolean onBack() {
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

    /** 按住左右键：只挪进度条上的目标点，越按越快（5 秒一档起，最快 60 秒一档）；松手 commitScrub 才跳。 */
    private void scrub(int dir, int repeat) {
        if (player == null) return;
        if (!scrubbing) { scrubbing = true; scrubTarget = player.getCurrentPosition(); }
        long step = Math.min(60_000, 5_000 + repeat * 500L);
        long dur = player.getDuration();
        scrubTarget = Math.max(0, scrubTarget + dir * step);
        if (dur > 0) scrubTarget = Math.min(scrubTarget, dur - 1000);
        showBar();
    }

    private void commitScrub() {
        scrubbing = false;
        if (player != null) player.seekTo(scrubTarget);
        showBar();
    }

    private void focusControls() {
        for (int i = 0; i < controls.getChildCount(); i++) {
            View v = controls.getChildAt(i);
            if (v.getVisibility() == View.VISIBLE && v.isFocusable()) { v.requestFocus(); break; }
        }
        showBar();   // 焦点进了按钮行再算自动收起的时间（按钮行里停留时间长一些）
    }

    private void cycleSpeed() {
        speedIdx = (speedIdx + 1) % SPEEDS.length;
        if (player != null) player.setPlaybackSpeed(SPEEDS[speedIdx]);
        updateControls();
        if (item != null) fillTags(item.optJSONArray("MediaSources").optJSONObject(0));
    }

    // ---------------------------------------------------------------- 布局

    private View build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        // 根布局自己拿焦点 = 「没有浮层、遥控器直接控制播放」的状态；按钮行 / 面板 / 卡片拿焦点时按键交给它们
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        Ui.noHighlight(root);
        surface = new TextureView(this);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        dim = new View(this);
        dim.setBackgroundColor(Color.BLACK);
        dim.setAlpha(0f);
        root.addView(dim, new FrameLayout.LayoutParams(-1, -1));
        // 触屏 / 鼠标（模拟器）：点画面 = 进度条没出来就显示，出来了就暂停 / 继续
        root.setOnClickListener(v -> {
            if (panel.getVisibility() == View.VISIBLE) closePanel();
            else if (bar.getVisibility() != View.VISIBLE) showBar();
            else togglePause();
        });

        subImage = new ImageView(this);
        subImage.setAdjustViewBounds(true);
        subImage.setVisibility(View.GONE);
        root.addView(subImage, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

        subBox = new LinearLayout(this);
        subBox.setOrientation(LinearLayout.VERTICAL);
        subBox.setGravity(Gravity.CENTER_HORIZONTAL);
        subBox.setPadding(px(24), px(8), px(24), px(10));
        subBox.setVisibility(View.INVISIBLE);
        subPrimary = subText(Typeface.DEFAULT_BOLD);
        subSecondary = subText(Typeface.DEFAULT);
        subBox.addView(subPrimary);
        subBox.addView(subSecondary);
        root.addView(subBox, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));

        // 缓冲转圈：卡超过半秒才出来，正常 seek 一闪而过的缓冲不打扰
        FrameLayout spin = new FrameLayout(this);
        GradientDrawable spinBg = new GradientDrawable();
        spinBg.setShape(GradientDrawable.OVAL);
        spinBg.setColor(0x99000000);
        spin.setBackground(spinBg);
        View p = Ui.spinner(this);
        spin.addView(p, new FrameLayout.LayoutParams(px(64), px(64), Gravity.CENTER));
        spin.setVisibility(View.GONE);
        spinner = spin;
        root.addView(spin, new FrameLayout.LayoutParams(px(120), px(120), Gravity.CENTER));

        // 暂停 / 继续时画面中间闪一下的大图标
        centerIcon = new ImageView(this);
        GradientDrawable iconBg = new GradientDrawable();
        iconBg.setShape(GradientDrawable.OVAL);
        iconBg.setColor(0x80000000);
        centerIcon.setBackground(iconBg);
        centerIcon.setPadding(px(40), px(40), px(40), px(40));
        centerIcon.setAlpha(0f);
        root.addView(centerIcon, new FrameLayout.LayoutParams(px(160), px(160), Gravity.CENTER));

        toast = Ui.text(this, "", 26, Color.WHITE, Typeface.DEFAULT);
        toast.setPadding(px(32), px(16), px(32), px(16));
        toast.setBackground(Ui.rounded(0xE61C1C1E, px(32)));
        toast.setVisibility(View.GONE);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tp.topMargin = px(48);
        root.addView(toast, tp);

        skipButton = Ui.text(this, "跳过片头  ›", 26, Color.BLACK, Typeface.DEFAULT_BOLD);
        skipButton.setPadding(px(34), px(16), px(34), px(16));
        skipButton.setBackground(Ui.rounded(0xF2FFFFFF, px(32)));
        skipButton.setVisibility(View.GONE);
        skipButton.setOnClickListener(v -> skipIntro());
        FrameLayout.LayoutParams kp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        kp.setMargins(0, 0, px(96), px(96));
        root.addView(skipButton, kp);

        root.addView(buildBar(), new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        root.addView(buildEpisodes(), new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        FrameLayout.LayoutParams up = new FrameLayout.LayoutParams(px(620), -2, Gravity.BOTTOM | Gravity.END);
        up.setMargins(0, 0, px(96), px(96));
        root.addView(buildUpNext(), up);
        FrameLayout.LayoutParams sy = new FrameLayout.LayoutParams(px(1240), -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        sy.topMargin = px(56);
        root.addView(buildSync(), sy);
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

    /** 底部进度条：缩略图 + 剧名 / 集数 / 画质标签，圆角进度条（章节刻度 + 拖动目标点），两端时间，一排控制按钮。 */
    private View buildBar() {
        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(px(96), px(240), px(96), px(48));
        // 多几个色标、拉高渐变区：三段式渐变在顶部会看出一条分界线
        bar.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xF2000000, 0xD9000000, 0x99000000, 0x40000000, 0x00000000}));
        bar.setVisibility(View.GONE);
        bar.setClipChildren(false);
        bar.setClipToPadding(false);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.BOTTOM);
        barThumb = new ImageView(this);
        barThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        barThumb.setClipToOutline(true);
        barThumb.setBackground(Ui.rounded(0xFF1C1C1E, px(10)));
        barThumb.setVisibility(View.GONE);
        LinearLayout.LayoutParams thp = new LinearLayout.LayoutParams(px(176), px(99));
        thp.rightMargin = px(28);
        head.addView(barThumb, thp);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        barTitle = Ui.text(this, "", 44, Color.WHITE, Typeface.DEFAULT_BOLD);
        barTitle.setSingleLine(true);
        barTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        barSub = Ui.text(this, "", 24, 0xFFD0D0D5, Typeface.DEFAULT);
        barSub.setPadding(0, px(6), 0, 0);
        barSub.setSingleLine(true);
        barSub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titles.addView(barTitle);
        titles.addView(barSub);
        head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        barTags = new LinearLayout(this);
        barTags.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        head.addView(barTags);
        bar.addView(head);

        track = new FrameLayout(this);
        track.setClipChildren(false);
        View rail = new View(this);
        rail.setBackground(Ui.rounded(0x4DFFFFFF, px(5)));
        track.addView(rail, new FrameLayout.LayoutParams(-1, px(10), Gravity.CENTER_VERTICAL));
        progressFill = new View(this);
        progressFill.setBackground(Ui.rounded(Color.WHITE, px(5)));
        track.addView(progressFill, new FrameLayout.LayoutParams(0, px(10), Gravity.CENTER_VERTICAL));
        // 拖动时的目标点：竖条，和当前播放位置的圆点区分开
        scrubMark = new View(this);
        scrubMark.setBackground(Ui.rounded(Ui.ACCENT, px(3)));
        scrubMark.setVisibility(View.GONE);
        track.addView(scrubMark, new FrameLayout.LayoutParams(px(6), px(34), Gravity.CENTER_VERTICAL));
        progressThumb = new View(this);
        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(Color.WHITE);
        progressThumb.setBackground(thumb);
        progressThumb.setElevation(px(6));
        track.addView(progressThumb, new FrameLayout.LayoutParams(px(26), px(26), Gravity.CENTER_VERTICAL));
        LinearLayout.LayoutParams trp = new LinearLayout.LayoutParams(-1, px(34));
        trp.topMargin = px(26);
        bar.addView(track, trp);

        LinearLayout times = new LinearLayout(this);
        times.setPadding(0, px(8), 0, 0);
        barPos = Ui.text(this, "", 24, Color.WHITE, Typeface.DEFAULT);
        times.addView(barPos, new LinearLayout.LayoutParams(0, -2, 1));
        barLeft = Ui.text(this, "", 24, 0xFFD0D0D5, Typeface.DEFAULT);
        times.addView(barLeft);
        bar.addView(times);

        // 控制按钮行：进度条出来后按下键进来，左右选、确认点、上键回到进度条
        LinearLayout lower = new LinearLayout(this);
        lower.setGravity(Gravity.CENTER_VERTICAL);
        lower.setPadding(0, px(22), 0, 0);
        lower.setClipChildren(false);
        lower.setClipToPadding(false);
        controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setClipChildren(false);
        controls.setClipToPadding(false);
        controls.addView(ctlButton("字幕与音轨", v -> openPanel()));
        ctlSync = ctlButton("字幕时间", v -> openSync());
        controls.addView(ctlSync);
        ctlEpisodes = ctlButton("选集", v -> openEpisodes());
        controls.addView(ctlEpisodes);
        ctlNext = ctlButton("下一集  ›", v -> playNext(false));
        controls.addView(ctlNext);
        ctlSpeed = ctlButton("倍速 1.0×", v -> cycleSpeed());
        controls.addView(ctlSpeed);
        lower.addView(controls, new LinearLayout.LayoutParams(0, -2, 1));
        TextView hint = Ui.text(this, "‹ › 快退快进 · 按住拖动　OK 暂停", 20, 0xFF8E8E93, Typeface.DEFAULT);
        lower.addView(hint);
        bar.addView(lower);
        return bar;
    }

    private TextView ctlButton(String label, View.OnClickListener onClick) {
        TextView b = Ui.text(this, label, 24, Color.WHITE, Typeface.DEFAULT);
        b.setPadding(px(28), px(12), px(28), px(13));
        b.setFocusable(true);
        b.setFocusableInTouchMode(true);
        b.setClickable(true);
        Ui.noHighlight(b);
        GradientDrawable idle = Ui.rounded(0x26FFFFFF, px(28));
        GradientDrawable on = Ui.rounded(Color.WHITE, px(28));
        b.setBackground(idle);
        b.setOnFocusChangeListener((v, has) -> {
            v.setBackground(has ? on : idle);
            ((TextView) v).setTextColor(has ? Color.BLACK : Color.WHITE);
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(140).start();
        });
        b.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.rightMargin = px(16);
        b.setLayoutParams(lp);
        return b;
    }

    /** 按钮行跟着当前条目：电影没有选集 / 下一集，没有文本字幕时「字幕时间」灰掉。 */
    private void updateControls() {
        if (controls == null) return;
        boolean episode = seriesId != null;
        ctlEpisodes.setVisibility(episode && seasonId != null ? View.VISIBLE : View.GONE);
        ctlNext.setVisibility(episode && nextEp != null ? View.VISIBLE : View.GONE);
        ctlSpeed.setText("倍速 " + (SPEEDS[speedIdx] == 1f ? "1.0" : String.valueOf(SPEEDS[speedIdx])) + "×");
    }

    /** 选集：底部渐变上一排单集卡片。 */
    private View buildEpisodes() {
        epBox = new LinearLayout(this);
        epBox.setOrientation(LinearLayout.VERTICAL);
        epBox.setPadding(0, px(200), 0, px(64));
        epBox.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xF2000000, 0xD9000000, 0x99000000, 0x40000000, 0x00000000}));
        epBox.setVisibility(View.GONE);
        epBox.setClipChildren(false);
        epBox.setClipToPadding(false);
        epHeader = Ui.text(this, "", 30, Color.WHITE, Typeface.DEFAULT_BOLD);
        epHeader.setPadding(px(96), 0, 0, px(18));
        epBox.addView(epHeader);
        // 关掉自带的「滚到刚好露出」，焦点卡片由 episodeCard 滚到屏幕中间
        HorizontalScrollView hs = new HorizontalScrollView(this) {
            @Override
            protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect rect) {
                return 0;
            }
        };
        epScroll = hs;
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        epRow = new LinearLayout(this);
        epRow.setPadding(px(96), px(16), px(96), px(16));
        epRow.setClipChildren(false);
        epRow.setClipToPadding(false);
        hs.addView(epRow);
        epBox.addView(hs);
        return epBox;
    }

    /** 下一集卡片（右下角）：截图 + 第几集 / 集名，「播放下一集」按钮底色随倒计时填满，旁边「看片尾」。 */
    private View buildUpNext() {
        upNext = new LinearLayout(this);
        upNext.setOrientation(LinearLayout.VERTICAL);
        upNext.setPadding(px(24), px(24), px(24), px(24));
        upNext.setBackground(Ui.rounded(0xF01C1C1E, px(24)));
        upNext.setElevation(px(12));
        upNext.setVisibility(View.GONE);
        upNext.setClipChildren(false);
        upNext.setClipToPadding(false);   // 选中的按钮放大 1.08 会超出内边距

        LinearLayout info = new LinearLayout(this);
        info.setGravity(Gravity.CENTER_VERTICAL);
        upNextThumb = new ImageView(this);
        upNextThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        upNextThumb.setClipToOutline(true);
        upNextThumb.setBackground(Ui.rounded(0xFF2C2C2E, px(12)));
        info.addView(upNextThumb, new LinearLayout.LayoutParams(px(224), px(126)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(px(22), 0, 0, 0);
        upNextLabel = Ui.text(this, "下一集", 20, 0xFF8E8E93, Typeface.DEFAULT_BOLD);
        upNextTitle = Ui.text(this, "", 26, Color.WHITE, Typeface.DEFAULT_BOLD);
        upNextTitle.setPadding(0, px(6), 0, 0);
        upNextName = Ui.text(this, "", 24, 0xFFD0D0D5, Typeface.DEFAULT);
        upNextName.setPadding(0, px(4), 0, 0);
        upNextName.setMaxLines(2);
        upNextName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(upNextLabel);
        texts.addView(upNextTitle);
        texts.addView(upNextName);
        info.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        upNext.addView(info);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setPadding(0, px(22), 0, 0);
        buttons.setClipChildren(false);
        upNextPlay = new FrameLayout(this);
        upNextPlay.setFocusable(true);
        upNextPlay.setFocusableInTouchMode(true);
        upNextPlay.setClickable(true);
        Ui.noHighlight(upNextPlay);
        upNextPlay.setClipToOutline(true);
        upNextFill = new View(this);
        upNextFill.setPivotX(0);
        upNextPlay.addView(upNextFill, new FrameLayout.LayoutParams(-1, -1));
        upNextPlayText = Ui.text(this, "播放下一集", 26, Color.BLACK, Typeface.DEFAULT_BOLD);
        upNextPlayText.setGravity(Gravity.CENTER);
        upNextPlay.addView(upNextPlayText, new FrameLayout.LayoutParams(-1, -1));
        upNextPlay.setOnClickListener(v -> playNext(false));
        GradientDrawable playOn = Ui.rounded(Color.WHITE, px(30)), playIdle = Ui.rounded(0x33FFFFFF, px(30));
        upNextPlay.setBackground(playOn);
        upNextFill.setBackgroundColor(0x2E000000);
        upNextPlay.setOnFocusChangeListener((v, has) -> {
            v.setBackground(has ? playOn : playIdle);
            upNextPlayText.setTextColor(has ? Color.BLACK : Color.WHITE);
            upNextFill.setBackgroundColor(has ? 0x2E000000 : 0x33FFFFFF);
        });
        buttons.addView(upNextPlay, new LinearLayout.LayoutParams(0, px(66), 1));   // 定高：里面的倒计时底色是 match_parent，包内容会被撑满
        upNextCancel = ctlButton("看片尾", v -> cancelUpNext());
        ((LinearLayout.LayoutParams) upNextCancel.getLayoutParams()).rightMargin = 0;
        ((LinearLayout.LayoutParams) upNextCancel.getLayoutParams()).leftMargin = px(16);
        upNextCancel.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(26));
        upNextCancel.setPadding(px(30), 0, px(30), 0);
        upNextCancel.setGravity(Gravity.CENTER);
        upNextCancel.getLayoutParams().height = px(66);
        buttons.addView(upNextCancel);
        upNext.addView(buttons);
        return upNext;
    }

    /** 对齐台词（屏幕上方）：当前偏移 + 前后五句字幕，选中的一句白底。 */
    private View buildSync() {
        syncBox = new LinearLayout(this);
        syncBox.setOrientation(LinearLayout.VERTICAL);
        syncBox.setPadding(px(36), px(28), px(36), px(24));
        syncBox.setBackground(Ui.rounded(0xEB141416, px(28)));
        syncBox.setVisibility(View.GONE);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(Ui.text(this, "字幕时间", 30, Color.WHITE, Typeface.DEFAULT_BOLD));
        syncValue = Ui.text(this, "", 34, Ui.ACCENT, Typeface.DEFAULT_BOLD);
        syncValue.setPadding(px(22), 0, 0, 0);
        head.addView(syncValue, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(Ui.text(this, "返回 完成", 22, 0xFF8E8E93, Typeface.DEFAULT));
        syncBox.addView(head);
        TextView how = Ui.text(this, "↑↓ 选一句台词，听到演员说这句时按 OK 自动对齐　·　‹ › 微调 0.1 秒（按住更快）", 22, 0xFFAEAEB2, Typeface.DEFAULT);
        how.setPadding(0, px(10), 0, px(14));
        syncBox.addView(how);

        syncLines = new LinearLayout(this);
        syncLines.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < 5; i++) {
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(px(18), px(10), px(18), px(10));
            TextView time = Ui.text(this, "", 22, 0xFF8E8E93, Typeface.MONOSPACE);
            r.addView(time, new LinearLayout.LayoutParams(px(130), -2));
            TextView line = Ui.text(this, "", 26, 0xFFAEAEB2, Typeface.DEFAULT);
            line.setSingleLine(true);
            line.setEllipsize(android.text.TextUtils.TruncateAt.END);
            r.addView(line, new LinearLayout.LayoutParams(0, -2, 1));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = px(4);
            syncLines.addView(r, lp);
        }
        syncBox.addView(syncLines);
        return syncBox;
    }

    /** 右侧面板：音轨（多于一条才有）→ 字幕轨（文本 / 自带）→ 时间（偏移、对齐台词、永久修正）→ 样式（样式 / 颜色 / 字号 / 位置）。 */
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
        TextView t = Ui.text(this, "字幕与音轨", 40, Color.WHITE, Typeface.DEFAULT_BOLD);
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

        // 音轨放最上面：有的片子字幕轨二三十条，放后面就翻不到了
        if (triedTranscode && serverAudios.size() > 1) {
            section("音轨");
            for (JSONObject a : serverAudios) choiceRow(a.optString("DisplayTitle").replace(" - 默认", ""), serverAudioSelected(a), () -> selectServerAudio(a));
        } else if (audios.size() > 1) {
            section("音轨");
            for (int i = 0; i < audios.size(); i++) {
                final int idx = i;
                choiceRow(audioName(audios.get(i), i), audios.get(i).isSelected(), () -> selectAudio(idx));
            }
        }

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
        offsetRow = valueRow("字幕时间", fmtOffset(offsetMs), () -> { adjustOffset(-1, false); rebuildPanel(false); }, () -> { adjustOffset(1, false); rebuildPanel(false); });
        offsetRow.setOnClickListener(v -> openSync());
        note("字幕比声音早就往右调，晚就往左调；按 OK 进「对齐台词」。调好的时间这一季后面几集也会用");
        actionRow("对齐台词（听到哪句选哪句）", this::openSync);
        actionRow("让 Agent 把字幕文件永久修正", this::askAgentToFix);

        section("样式");
        valueRow("样式", STYLE_NAMES[styleIdx], () -> cycleStyle(-1), () -> cycleStyle(1));
        valueRow("颜色", COLOR_NAMES[colorIdx], () -> cycleColor(-1), () -> cycleColor(1));
        valueRow("字号", SIZE_NAMES[sizeIdx], () -> cycleSize(-1), () -> cycleSize(1));
        valueRow("位置", POS_NAMES[posIdx], () -> cyclePos(-1), () -> cyclePos(1));

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
    private void cyclePos(int d) { posIdx = Math.max(0, Math.min(POS_NAMES.length - 1, posIdx + d)); prefs.edit().putInt("subPos", posIdx).apply(); applyStyle(); rebuildPanel(false); }

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
        root.requestFocus();
        panel.animate().translationX(px(PANEL_W)).setDuration(160).withEndAction(() -> panel.setVisibility(View.GONE)).start();
        subBox.animate().translationX(0).setDuration(160).start();
        subImage.animate().translationX(0).setDuration(160).start();
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
        final String id = itemId;
        io.execute(() -> {
            try {
                g.chat(msg);
                ui.post(() -> {
                    // 文件改好后这一集不该再叠偏移；记成 0 而不是删掉，不然会退回用这一季的默认值
                    prefs.edit().putLong("offset:item:" + id, 0).apply();
                    showToast("已交给 Agent，改好后重新打开这一集就是准的");
                    hideToastLater();
                });
            } catch (Exception e) {
                ui.post(() -> showToast("没发出去：" + e.getMessage()));
            }
        });
    }

    // ---------------------------------------------------------------- 进度条 / 提示

    private void fillBar(JSONObject ms) {
        boolean episode = item.optString("SeriesId", null) != null;
        loadThumb(itemId, item);
        barTitle.setText(episode ? item.optString("SeriesName") : item.optString("Name"));
        barSub.setText(episode ? seLabel(item) + " · " + item.optString("Name")
                : (item.optInt("ProductionYear") > 0 ? item.optInt("ProductionYear") + " · 电影" : "电影"));
        fillTags(ms);
        // 章节刻度（第一章从 0 开始，不画）
        for (View v : chapterTicks) track.removeView(v);
        chapterTicks.clear();
        for (int i = 0; i < chapterStarts.length; i++) {
            if (chapterStarts[i] <= 0) continue;
            View tick = new View(this);
            tick.setBackgroundColor(0xB3000000);
            track.addView(tick, 2, new FrameLayout.LayoutParams(px(4), px(10), Gravity.CENTER_VERTICAL));   // 压在已播放的白条上面
            chapterTicks.add(tick);
        }
        updateControls();
    }

    /** 右上角的小标签：清晰度 · 编码 · HDR · 直接播放 / 服务器转码 · 倍速。 */
    private void fillTags(JSONObject ms) {
        barTags.removeAllViews();
        JSONArray streams = ms.optJSONArray("MediaStreams");
        for (int i = 0; streams != null && i < streams.length(); i++) {
            JSONObject s = streams.optJSONObject(i);
            if (!"Video".equals(s.optString("Type"))) continue;
            int w = s.optInt("Width"), h = s.optInt("Height");
            String res = w >= 3800 || h >= 2100 ? "4K" : w >= 1900 || h >= 1000 ? "1080p" : w >= 1200 || h >= 700 ? "720p" : h > 0 ? h + "p" : "";
            if (!res.isEmpty()) tag(res);
            String codec = s.optString("Codec").toLowerCase(Locale.ROOT);
            tag(codec.equals("hevc") || codec.equals("h265") ? "HEVC" : codec.equals("h264") ? "H.264" : codec.toUpperCase(Locale.ROOT));
            String range = s.optString("VideoRangeType");
            if (range.startsWith("DOVI")) tag("杜比视界");
            else if (!range.isEmpty() && !"SDR".equals(range)) tag("HDR");
            break;
        }
        tag(triedTranscode ? "服务器转码" : "直接播放");
        if (SPEEDS[speedIdx] != 1f) tag(SPEEDS[speedIdx] + "×");
    }

    private void tag(String s) {
        if (s.isEmpty()) return;
        TextView c = Ui.chip(this, s, 0xFFE5E5EA, 0x33FFFFFF);
        ((LinearLayout.LayoutParams) c.getLayoutParams()).rightMargin = 0;
        ((LinearLayout.LayoutParams) c.getLayoutParams()).leftMargin = px(10);
        barTags.addView(c);
    }

    /** 单集截图（没有就用剧的横幅 / 电影的剧照）。 */
    private void loadThumb(String id, JSONObject it) {
        barThumb.setImageDrawable(null);
        barThumb.setVisibility(View.GONE);
        JSONObject tags = it.optJSONObject("ImageTags");
        String path = tags != null && tags.has("Primary") && it.has("SeriesId") ? "/Items/" + id + "/Images/Primary?maxWidth=360&quality=85"
                : it.has("SeriesId") ? "/Items/" + it.optString("SeriesId") + "/Images/Backdrop?maxWidth=360&quality=85"
                : "/Items/" + id + "/Images/Backdrop?maxWidth=360&quality=85";
        io.execute(() -> {
            try {
                Bitmap b = jellyfin.image(path);
                if (b != null) ui.post(() -> { if (id.equals(itemId)) { barThumb.setImageBitmap(b); barThumb.setVisibility(View.VISIBLE); } });
            } catch (Exception ignored) {
            }
        });
    }

    private void loadInto(String path, ImageView into) {
        into.setImageDrawable(null);
        into.setTag(path);
        io.execute(() -> {
            try {
                Bitmap b = jellyfin.image(path);
                if (b != null) ui.post(() -> { if (path.equals(into.getTag())) into.setImageBitmap(b); });
            } catch (Exception ignored) {
            }
        });
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
        ui.postDelayed(hideBar, controls.hasFocus() ? 8000 : 4000);
    }

    private final Runnable hideBar = () -> { if (player != null && player.isPlaying() && !scrubbing) hideBarNow(); };

    private void hideBarNow() {
        ui.removeCallbacks(hideBar);
        if (controls.hasFocus()) root.requestFocus();
        if (bar.getVisibility() != View.VISIBLE) return;
        bar.animate().alpha(0f).setDuration(160).withEndAction(() -> bar.setVisibility(View.GONE)).start();
        liftSubtitles(false);
    }

    /** 进度条 / 选集出来时字幕整体上移到它上方，别被盖住。 */
    private void liftSubtitles(boolean up) {
        float dy = up ? -px(380 - SUB_BOTTOM[posIdx]) : 0;
        if (up && dy > 0) dy = 0;
        subBox.animate().translationY(dy).setDuration(160).start();
        subImage.animate().translationY(dy).setDuration(160).start();
    }

    private void updateBar() {
        if (player == null) return;
        long now = player.getCurrentPosition(), dur = Math.max(1, player.getDuration());
        long pos = scrubbing ? scrubTarget : now;
        int w = track.getWidth();
        float f = (float) Math.min(1, now / (double) dur);
        progressFill.getLayoutParams().width = (int) (w * f);
        progressFill.requestLayout();
        progressThumb.setTranslationX(w * f - px(13));
        scrubMark.setVisibility(scrubbing ? View.VISIBLE : View.GONE);
        if (scrubbing) scrubMark.setTranslationX(w * (float) Math.min(1, pos / (double) dur) - px(3));
        for (int i = 0, t = 0; i < chapterStarts.length; i++) {
            if (chapterStarts[i] <= 0) continue;
            if (t < chapterTicks.size()) chapterTicks.get(t++).setTranslationX(w * (float) Math.min(1, chapterStarts[i] / (double) dur) - px(2));
        }
        String chapter = chapterAt(pos);
        barPos.setText((scrubbing ? "→ " : "") + clock(pos) + " / " + clock(dur) + (chapter != null ? "　·　" + chapter : ""));
        barPos.setTextColor(scrubbing ? Ui.ACCENT : Color.WHITE);
        long left = Math.max(0, (long) ((dur - pos) / SPEEDS[speedIdx]));
        java.util.Calendar end = java.util.Calendar.getInstance();
        end.add(java.util.Calendar.MILLISECOND, (int) left);
        barLeft.setText("还剩 " + clock(left) + "　·　" + String.format(Locale.US, "%d:%02d", end.get(java.util.Calendar.HOUR_OF_DAY), end.get(java.util.Calendar.MINUTE)) + " 结束");
    }

    private void updateBarState() {
        if (player == null) return;
        boolean playing = player.getPlayWhenReady();
        dim.animate().alpha(playing ? 0f : 0.3f).setDuration(200).start();
        if (!playing && bar.getVisibility() != View.VISIBLE && upNext.getVisibility() != View.VISIBLE
                && syncBox.getVisibility() != View.VISIBLE && epBox.getVisibility() != View.VISIBLE) showBar();
        if (!playing) ui.removeCallbacks(hideBar);
    }

    /** 暂停 / 继续：画面中间的大图标放大淡出。 */
    private void showCenterIcon(boolean playing) {
        centerIcon.setImageDrawable(new Icons(playing ? Icons.Kind.PLAY : Icons.Kind.PAUSE, Color.WHITE));
        centerIcon.animate().cancel();
        centerIcon.setAlpha(1f);
        centerIcon.setScaleX(0.85f);
        centerIcon.setScaleY(0.85f);
        centerIcon.animate().alpha(0f).scaleX(1.15f).scaleY(1.15f).setStartDelay(250).setDuration(400).start();
    }

    private final Runnable showSpinner = () -> {
        spinner.setVisibility(View.VISIBLE);
        spinner.setAlpha(0f);
        spinner.animate().alpha(1f).setDuration(150).start();
    };

    private void showBuffering(boolean buffering) {
        ui.removeCallbacks(showSpinner);
        if (buffering) ui.postDelayed(showSpinner, 500);
        else spinner.setVisibility(View.GONE);
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
