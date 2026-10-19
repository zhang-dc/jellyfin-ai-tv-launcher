package com.optimisticat.tvhome;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「找剧」：搜索（打字 / 按住说 / 遥控器语音键）→ 资源列表 → 确认键下载。
 * 走后端的 /tv/hub/search 和 /tv/hub/grab（见 docs/protocol.md），结果由后端按做种数排好序；文件夹名默认用搜索词。
 *
 * 每条资源一张卡：左边清晰度大标签，中间标题 + 小标签（片源、中字、大小、做种热度、站点），焦点白描边。
 */
public class SearchActivity extends Activity implements XiaoaiBridge.Target {

    private static final String[] EXAMPLES = {"摩登家庭 第六季", "Breaking Bad", "庆余年", "沙丘2"};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Gateway gateway;
    private EditText input;
    private TextView status;
    private ProgressBar spinner;
    private FrameLayout micButton;
    private Icons micIcon;
    private LinearLayout list, empty;
    private volatile Recorder recorder;
    private String lastQuery = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.fullscreen(this);
        gateway = Services.gateway(this);
        setContentView(build());
        if (gateway == null) status.setText("没有配置 gateway（assets/gateway.properties），用不了");
    }

    @Override
    protected void onResume() {
        super.onResume();
        XiaoaiBridge.attach(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Recorder r = recorder;
        if (r != null) r.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        XiaoaiBridge.detach(this);
    }

    /** 遥控器语音键说的剧名（小爱听写）。 */
    @Override
    public void onVoiceText(String text) {
        search(text.replaceAll("[。，！？.,!?]+$", "").replaceAll("^(我想看|帮我找|搜一下|搜索|找一下)", ""));
    }

    /** 麦克风选中时按住确认键说话、松开搜索。 */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (micButton.hasFocus() && (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER)) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0 && recorder == null) startListening();
            else if (e.getAction() == KeyEvent.ACTION_UP && recorder != null) recorder.stop();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // ---------------------------------------------------------------- 布局

    private View build() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Ui.BG);
        col.setPadding(px(96), px(56), px(96), px(24));

        LinearLayout head = Ui.pageHeader(this, Icons.Kind.SEARCH, "找剧", "搜到的资源按做种人数排，越多下得越快；确认键下载");
        head.addView(Ui.iconButton(this, Icons.Kind.DOWNLOAD, "下载进度", v -> startActivity(new Intent(this, DownloadsActivity.class))));
        col.addView(head);

        // 搜索条：放大镜 | 输入框 | 麦克风 | 搜索
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(px(28), 0, px(14), 0);
        bar.setClipToPadding(false);   // 右边的圆按钮选中放大会伸进内边距
        bar.setClipChildren(false);
        bar.setBackground(Ui.rounded(0xFF1C1C1E, px(48)));
        ImageView glass = new ImageView(this);
        glass.setImageDrawable(new Icons(Icons.Kind.SEARCH, 0xFF8E8E93));
        bar.addView(glass, new LinearLayout.LayoutParams(px(34), px(34)));
        input = new EditText(this);
        input.setHint("剧名，中文英文都行；也可以按住右边的麦克风说");
        input.setHintTextColor(0xFF6E6E73);
        input.setTextColor(Color.WHITE);
        input.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(30));
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setBackground(null);
        input.setPadding(px(20), 0, px(16), 0);
        input.setOnEditorActionListener((v, id, e) -> { search(input.getText().toString()); return true; });
        bar.addView(input, new LinearLayout.LayoutParams(0, -1, 1));

        micButton = new FrameLayout(this);
        micButton.setFocusable(true);
        micButton.setFocusableInTouchMode(true);
        Ui.noHighlight(micButton);
        micButton.setClickable(true);
        GradientDrawable micIdle = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF00A4DC, 0xFF7B5CFF});
        micIdle.setShape(GradientDrawable.OVAL);
        GradientDrawable micOn = new GradientDrawable();
        micOn.setShape(GradientDrawable.OVAL);
        micOn.setColor(Color.WHITE);
        micButton.setBackground(micIdle);
        micIcon = new Icons(Icons.Kind.MIC, Color.WHITE);
        ImageView mi = new ImageView(this);
        mi.setImageDrawable(micIcon);
        micButton.addView(mi, new FrameLayout.LayoutParams(px(34), px(34), Gravity.CENTER));
        micButton.setOnFocusChangeListener((v, has) -> {
            v.setBackground(has ? micOn : micIdle);
            micIcon.setColor(has ? Color.BLACK : Color.WHITE);
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(140).start();
        });
        micButton.setOnClickListener(v -> { Recorder r = recorder; if (r != null) r.stop(); else startListening(); });
        bar.addView(micButton, new LinearLayout.LayoutParams(px(72), px(72)));
        LinearLayout go = Ui.iconButton(this, Icons.Kind.SEARCH, "", v -> search(input.getText().toString()));
        go.removeViewAt(1);
        go.getChildAt(0).setLayoutParams(new LinearLayout.LayoutParams(px(72), px(72)));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(px(72), px(72));
        gp.leftMargin = px(12);
        bar.addView(go, gp);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, px(96));
        bp.topMargin = px(36);
        col.addView(bar, bp);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(px(8), px(24), 0, px(12));
        spinner = Ui.spinner(this);
        spinner.setVisibility(View.GONE);
        statusRow.addView(spinner);
        status = Ui.text(this, "", 24, Ui.TEXT_DIM, Typeface.DEFAULT);
        status.setPadding(px(16), 0, 0, 0);
        statusRow.addView(status);
        col.addView(statusRow);

        FrameLayout body = new FrameLayout(this);
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setFocusable(false);
        Ui.noHighlight(sv);
        sv.setClipChildren(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(px(12), px(8), px(12), px(40));
        list.setClipChildren(false);
        list.setClipToPadding(false);
        sv.addView(list);
        body.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        empty = buildEmpty();
        body.addView(empty, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        col.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        micButton.post(micButton::requestFocus);
        return col;
    }

    private LinearLayout buildEmpty() {
        LinearLayout e = new LinearLayout(this);
        e.setOrientation(LinearLayout.VERTICAL);
        e.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView tip = Ui.text(this, "试试这些", 26, Ui.TEXT_DIM, Typeface.DEFAULT);
        tip.setGravity(Gravity.CENTER);   // 竖向 LinearLayout 的子 View 默认撑满宽度，文字要自己居中
        e.addView(tip);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_HORIZONTAL);
        row.setPadding(0, px(20), 0, 0);
        for (String s : EXAMPLES) {
            TextView chip = Ui.text(this, s, 26, Color.WHITE, Typeface.DEFAULT);
            chip.setPadding(px(28), px(14), px(28), px(14));
            GradientDrawable idle = Ui.rounded(0x1FFFFFFF, px(30));
            GradientDrawable on = Ui.rounded(Color.WHITE, px(30));
            chip.setBackground(idle);
            chip.setFocusable(true);
            chip.setFocusableInTouchMode(true);
            Ui.noHighlight(chip);
            chip.setClickable(true);
            chip.setOnFocusChangeListener((v, has) -> {
                v.setBackground(has ? on : idle);
                ((TextView) v).setTextColor(has ? Color.BLACK : Color.WHITE);
            });
            chip.setOnClickListener(v -> search(s));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = lp.rightMargin = px(8);
            row.addView(chip, lp);
        }
        e.addView(row);
        return e;
    }

    // ---------------------------------------------------------------- 搜索

    private void search(String q) {
        q = q.trim();
        if (q.isEmpty() || gateway == null) return;
        lastQuery = q;
        input.setText(q);
        list.removeAllViews();
        empty.setVisibility(View.GONE);
        spinner.setVisibility(View.VISIBLE);
        status.setText("正在各站搜「" + q + "」，十几秒…");
        final String query = q;
        new Thread(() -> {
            try {
                JSONObject r = gateway.hubGet("search", "q=" + Gateway.enc(query));
                JSONArray rs = r.optJSONArray("results");
                ui.post(() -> showResults(query, rs));
            } catch (Exception e) {
                ui.post(() -> { spinner.setVisibility(View.GONE); status.setText("搜索失败：" + e.getMessage()); });
            }
        }, "search").start();
    }

    private void showResults(String q, JSONArray rs) {
        if (!q.equals(lastQuery)) return;
        spinner.setVisibility(View.GONE);
        int n = rs == null ? 0 : Math.min(rs.length(), 60);
        status.setText(n == 0 ? "没搜到「" + q + "」，换个说法试试（英文名、去掉季号）"
                : "「" + q + "」找到 " + rs.length() + " 个资源" + (rs.length() > n ? "，显示做种最多的 " + n + " 个" : ""));
        if (n == 0) empty.setVisibility(View.VISIBLE);
        for (int i = 0; i < n; i++) {
            View row = row(rs.optJSONObject(i));
            list.addView(row);
            row.setAlpha(0f);
            row.animate().alpha(1f).setStartDelay(Math.min(i, 8) * 30L).setDuration(160).start();
        }
        if (n > 0) list.getChildAt(0).requestFocus();
    }

    private View row(JSONObject r) {
        String title = r.optString("title");
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(24), px(20), px(32), px(20));
        row.setBackground(Ui.rounded(0xFF1C1C1E, px(20)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = px(14);
        row.setLayoutParams(lp);

        // 左：清晰度大标签（颜色分档）
        String res = resolution(title);
        int resColor = res.startsWith("4K") ? 0xFFFFB340 : res.equals("1080P") ? 0xFF30D158 : res.equals("720P") ? 0xFF5AC8FA : 0xFF8E8E93;
        TextView badge = Ui.text(this, res.isEmpty() ? "—" : res, 24, resColor, Typeface.DEFAULT_BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(Ui.rounded((resColor & 0x00FFFFFF) | 0x26000000, px(14)));
        row.addView(badge, new LinearLayout.LayoutParams(px(120), px(76)));

        // 中：标题 + 小标签
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(px(24), 0, px(16), 0);
        TextView t = Ui.text(this, title, 26, Color.WHITE, Typeface.DEFAULT);
        t.setMaxLines(2);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setLineSpacing(px(4), 1f);
        mid.addView(t);
        LinearLayout chips = new LinearLayout(this);
        chips.setPadding(0, px(12), 0, 0);
        String src = source(title);
        if (!src.isEmpty()) chips.addView(Ui.chip(this, src, 0xFFD0D0D5, 0x26FFFFFF));
        if (hasChineseSubs(title)) chips.addView(Ui.chip(this, "中字", 0xFF30D158, 0x2630D158));
        if ("movie".equals(r.optString("kind"))) chips.addView(Ui.chip(this, "电影", 0xFFFFB340, 0x26FFB340));
        chips.addView(Ui.chip(this, Ui.size(r.optLong("size")), 0xFFD0D0D5, 0x26FFFFFF));
        chips.addView(Ui.chip(this, r.optString("indexer"), 0xFF8E8E93, 0x14FFFFFF));
        mid.addView(chips);
        row.addView(mid, new LinearLayout.LayoutParams(0, -2, 1));

        // 右：做种热度（圆点颜色 + 人数）
        int seeders = r.isNull("seeders") ? -1 : r.optInt("seeders");
        int heat = seeders >= 50 ? 0xFF30D158 : seeders >= 5 ? 0xFFFFD60A : seeders > 0 ? 0xFFFF9F0A : 0xFFFF453A;
        LinearLayout seed = new LinearLayout(this);
        seed.setOrientation(LinearLayout.VERTICAL);
        seed.setGravity(Gravity.CENTER);
        seed.addView(Ui.text(this, seeders < 0 ? "?" : String.valueOf(seeders), 34, heat, Typeface.DEFAULT_BOLD));
        seed.addView(Ui.text(this, "做种", 18, Ui.TEXT_DIM, Typeface.DEFAULT));
        row.addView(seed, new LinearLayout.LayoutParams(px(110), -2));

        Ui.focusRing(this, row, 1.015f);
        GradientDrawable idle = Ui.rounded(0xFF1C1C1E, px(20));
        GradientDrawable on = Ui.rounded(0xFF2C2C2E, px(20));
        View.OnFocusChangeListener ring = row.getOnFocusChangeListener();
        row.setOnFocusChangeListener((v, has) -> { ring.onFocusChange(v, has); v.setBackground(has ? on : idle); });
        row.setOnClickListener(v -> confirmGrab(r));
        return row;
    }

    private static String resolution(String title) {
        Matcher m = Pattern.compile("(?i)(2160p|4k|1080p|720p|480p)").matcher(title);
        if (!m.find()) return "";
        String s = m.group(1).toUpperCase();
        return s.equals("2160P") ? "4K" : s;
    }

    private static String source(String title) {
        Matcher m = Pattern.compile("(?i)(blu-?ray|web-?dl|webrip|hdtv|remux)").matcher(title);
        if (!m.find()) return "";
        String s = m.group(1).toLowerCase().replace("-", "");
        switch (s) {
            case "bluray": return "蓝光";
            case "webdl": return "WEB-DL";
            case "webrip": return "WEBRip";
            case "remux": return "原盘";
            default: return "电视录制";
        }
    }

    private static boolean hasChineseSubs(String title) {
        return title.matches("(?s).*(中字|中英|简繁|字幕|CHS|CHT|简中|繁中|双语).*");
    }

    private void confirmGrab(JSONObject r) {
        String folder = lastQuery.replaceAll("[\\\\/:*?\"<>|]", " ").trim();
        Ui.choose(this, "下载这个资源？", r.optString("title") + "\n\n" + Ui.size(r.optLong("size")) + " · 存到「" + folder + "」，下完的集会自动出现在首页",
                new String[]{"下载", "取消"}, Collections.emptySet(), which -> { if (which == 0) grab(r, folder); });
    }

    private void grab(JSONObject r, String folder) {
        spinner.setVisibility(View.VISIBLE);
        status.setText("正在添加下载…");
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject().put("grab", r.optString("grab")).put("folder", folder)
                        .put("kind", "movie".equals(r.optString("kind")) ? "movie" : "tv");
                JSONObject res = gateway.hubPost("grab", body);
                ui.post(() -> {
                    spinner.setVisibility(View.GONE);
                    status.setText(res.optBoolean("ok")
                            ? "✓ 已开始下载到「" + res.optString("folder") + "」，右上角「下载进度」能看"
                            : "添加失败：" + res.optString("error"));
                });
            } catch (Exception e) {
                ui.post(() -> { spinner.setVisibility(View.GONE); status.setText("添加失败：" + e.getMessage()); });
            }
        }, "grab").start();
    }

    // ---------------------------------------------------------------- 语音

    private void startListening() {
        if (gateway == null) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            return;
        }
        Recorder rec = new Recorder(true);
        recorder = rec;
        input.setText("");
        input.setHint("正在听…说完松开");
        micButton.animate().scaleX(1.2f).scaleY(1.2f).setDuration(120).start();
        new Thread(() -> {
            String text = null, err = null;
            try {
                byte[] wav = rec.record(null);
                if (wav != null) {
                    ui.post(() -> input.setHint("识别中…"));
                    text = gateway.asr(wav);
                }
            } catch (Exception e) {
                err = e.getMessage();
            }
            final String ft = text == null ? null : text.replaceAll("[。，！？.,!?]+$", ""), fe = err;
            ui.post(() -> {
                recorder = null;
                micButton.animate().scaleX(micButton.hasFocus() ? 1.1f : 1f).scaleY(micButton.hasFocus() ? 1.1f : 1f).setDuration(120).start();
                input.setHint("剧名，中文英文都行；也可以按住右边的麦克风说");
                if (fe != null) status.setText("语音出错：" + fe);
                else if (ft == null || ft.isEmpty()) status.setText("没听清，按住麦克风再说一次");
                else search(ft);
            });
        }, "search-listen").start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED) status.setText("没有录音权限");
    }

    private int px(int v) {
        return Ui.px(this, v);
    }
}
