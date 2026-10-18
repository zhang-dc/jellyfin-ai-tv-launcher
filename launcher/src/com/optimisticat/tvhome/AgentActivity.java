package com.optimisticat.tvhome;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
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
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 「问问」：和家里的 Agent 对话（后端 /tv/chat + /tv/events，见 docs/protocol.md）。
 *
 * 输入三种：按住确认键对着电视说话（录音 → 后端语音识别转文字 → 发出去）；按遥控器语音键说话（小爱听写，
 * 见 XiaoaiBridge）；遥控器打字。回复靠长轮询 /tv/events 取，Agent 干活期间显示「思考中」。
 * 2026-10-19：「点一下开始、说完自动结束」在客厅底噪下结束不了，改成按住说话、松手发送。
 *
 * 布局：左上标题栏；中间一栏对话（Agent 左侧带头像，自己右侧蓝色气泡），没消息时显示几条可点的示例；
 * 底部一条输入坞：麦克风圆钮 + 输入框 + 发送。录音时输入框换成随音量跳动的声纹条。
 */
public class AgentActivity extends Activity implements XiaoaiBridge.Target {

    private static final String[] SUGGESTIONS = {"最近有什么新剧", "帮我下载摩登家庭第六季", "下载进度怎么样了", "推荐一部适合全家看的电影"};
    private static final int COLUMN_W = 1440;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Gateway gateway;
    private LinearLayout messages, empty, waveBars;
    private ScrollView scroll;
    private View thinkingRow;
    private TextView status, waveLabel;
    private FrameLayout micButton;
    private Icons micIcon;
    private EditText input;
    private ObjectAnimator micPulse;
    private volatile boolean alive;
    private volatile Recorder recorder;
    private long lastSeq = 0;
    private boolean typingSeen = false;

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
        alive = true;
        if (gateway != null) new Thread(this::pollLoop, "agent-poll").start();
        if (getIntent().getBooleanExtra("probe", false)) {   // 调试：麦克风探测，见 Recorder.probe()
            getIntent().removeExtra("probe");
            status.setText("麦克风探测中，请一直对着电视说话（约 30 秒）…");
            new Thread(() -> { Recorder.probe(); ui.post(() -> status.setText("探测完成")); }, "probe").start();
        }
        XiaoaiBridge.attach(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    protected void onPause() {
        super.onPause();
        alive = false;
        Recorder r = recorder;
        if (r != null) r.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        XiaoaiBridge.detach(this);
    }

    /** 遥控器语音键说的话（小爱听写）。 */
    @Override
    public void onVoiceText(String text) {
        status.setText("");
        send(text);
    }

    /** 麦克风选中时：按下确认键开始录，松开结束并发送（按键事件吃掉，不触发 onClick）。 */
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

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && recorder != null) {
            recorder.stop();   // 录音中按返回 = 结束这次，不退出页面
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---------------------------------------------------------------- 布局

    private View build() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        // 左上角一团很淡的蓝紫光，给纯黑底一点层次
        View aura = new View(this);
        GradientDrawable ag = new GradientDrawable();
        ag.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ag.setGradientRadius(px(780));   // 小于视图半宽，不然渐变被视图边界切出直角
        ag.setColors(new int[]{0x3300A4DC, 0x00000000});
        aura.setBackground(ag);
        root.addView(aura, new FrameLayout.LayoutParams(px(1600), px(1600)));   // 正方形，半径 780 < 半边 800，渐变不会被切出直角
        aura.setTranslationX(-px(500));
        aura.setTranslationY(-px(500));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(96), px(56), px(96), px(44));
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout head = Ui.pageHeader(this, Icons.Kind.CHAT, "问问", "按住确认键说话，或按遥控器语音键说；也能打字");
        head.addView(Ui.iconButton(this, Icons.Kind.SPARK, "新对话", v -> newConversation()));
        col.addView(head);

        FrameLayout middle = new FrameLayout(this);
        col.addView(middle, new LinearLayout.LayoutParams(-1, 0, 1));

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(false);
        messages = new LinearLayout(this);
        messages.setOrientation(LinearLayout.VERTICAL);
        messages.setPadding(0, px(32), 0, px(24));
        scroll.addView(messages);
        middle.addView(scroll, new FrameLayout.LayoutParams(px(COLUMN_W), -1, Gravity.CENTER_HORIZONTAL));

        empty = buildEmpty();
        // 横向撑满 + 内容居中：按内容宽度测的话，子 TextView 默认撑满父级，两边互相等，宽度会塌成一个字
        middle.addView(empty, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

        status = Ui.text(this, "", 22, Ui.TEXT_DIM, Typeface.DEFAULT);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 0, 0, px(12));
        col.addView(status, new LinearLayout.LayoutParams(-1, -2));

        col.addView(buildDock(), new LinearLayout.LayoutParams(px(COLUMN_W), px(112)) {{ gravity = Gravity.CENTER_HORIZONTAL; }});
        micButton.post(micButton::requestFocus);
        return root;
    }

    /** 还没聊过时：大星标 + 一句引导 + 几条能直接点的示例。 */
    private LinearLayout buildEmpty() {
        LinearLayout e = new LinearLayout(this);
        e.setOrientation(LinearLayout.VERTICAL);
        e.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView star = new ImageView(this);
        star.setImageDrawable(new Icons(Icons.Kind.SPARK, 0xFF5AC8FA));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(px(84), px(84));
        sp.gravity = Gravity.CENTER_HORIZONTAL;
        e.addView(star, sp);
        TextView t = Ui.text(this, "想看什么？直接说", 40, Color.WHITE, Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, px(24), 0, px(8));
        e.addView(t);
        TextView sub = Ui.text(this, "找剧、下载、查进度、修字幕，家里电脑上的事都能问", 24, Ui.TEXT_DIM, Typeface.DEFAULT);
        sub.setGravity(Gravity.CENTER);
        e.addView(sub);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_HORIZONTAL);
        row.setPadding(0, px(36), 0, 0);
        for (String s : SUGGESTIONS) {
            TextView chip = Ui.text(this, s, 24, Color.WHITE, Typeface.DEFAULT);
            chip.setPadding(px(26), px(14), px(26), px(14));
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
            chip.setOnClickListener(v -> send(s));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = lp.rightMargin = px(8);
            row.addView(chip, lp);
        }
        e.addView(row);
        return e;
    }

    /** 底部输入坞：麦克风圆钮 | 输入框（录音时换成声纹条） | 发送。 */
    private View buildDock() {
        LinearLayout dock = new LinearLayout(this);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setPadding(px(12), 0, px(16), 0);
        dock.setBackground(Ui.rounded(0xFF1C1C1E, px(56)));
        dock.setClipChildren(false);

        micButton = new FrameLayout(this);
        micButton.setFocusable(true);
        micButton.setFocusableInTouchMode(true);
        Ui.noHighlight(micButton);
        micButton.setClickable(true);
        micButton.setContentDescription("按住说话");
        GradientDrawable micIdle = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF00A4DC, 0xFF7B5CFF});
        micIdle.setShape(GradientDrawable.OVAL);
        GradientDrawable micOn = new GradientDrawable();
        micOn.setShape(GradientDrawable.OVAL);
        micOn.setColor(Color.WHITE);
        micButton.setBackground(micIdle);
        micIcon = new Icons(Icons.Kind.MIC, Color.WHITE);
        ImageView mi = new ImageView(this);
        mi.setImageDrawable(micIcon);
        micButton.addView(mi, new FrameLayout.LayoutParams(px(40), px(40), Gravity.CENTER));
        micButton.setOnFocusChangeListener((v, has) -> {
            v.setBackground(has ? micOn : micIdle);
            micIcon.setColor(has ? Color.BLACK : Color.WHITE);
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(140).start();
        });
        micButton.setOnClickListener(v -> toggleListen());   // 只有触屏（模拟器）会走到
        dock.addView(micButton, new LinearLayout.LayoutParams(px(88), px(88)));

        FrameLayout center = new FrameLayout(this);
        input = new EditText(this);
        input.setHint("按住左边的麦克风说话，或在这里打字…");
        input.setHintTextColor(0xFF6E6E73);
        input.setTextColor(Color.WHITE);
        input.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(28));
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setBackground(null);
        input.setPadding(px(28), 0, px(16), 0);
        input.setOnEditorActionListener((v, actionId, e) -> { sendInput(); return true; });
        center.addView(input, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout wave = new LinearLayout(this);
        wave.setGravity(Gravity.CENTER_VERTICAL);
        wave.setPadding(px(28), 0, 0, 0);
        wave.setVisibility(View.GONE);
        waveBars = new LinearLayout(this);
        waveBars.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < 9; i++) {
            View bar = new View(this);
            bar.setBackground(Ui.rounded(0xFF5AC8FA, px(4)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(8), px(10));
            lp.rightMargin = px(7);
            waveBars.addView(bar, lp);
        }
        wave.addView(waveBars);
        waveLabel = Ui.text(this, "正在听…松开发送", 26, Color.WHITE, Typeface.DEFAULT);
        waveLabel.setPadding(px(20), 0, 0, 0);
        wave.addView(waveLabel);
        center.addView(wave, new FrameLayout.LayoutParams(-1, -1));
        dock.addView(center, new LinearLayout.LayoutParams(0, -1, 1));

        LinearLayout send = Ui.iconButton(this, Icons.Kind.SEND, "", v -> sendInput());
        send.getChildAt(0).setLayoutParams(new LinearLayout.LayoutParams(px(72), px(72)));
        send.removeViewAt(1);
        send.setLayoutParams(new LinearLayout.LayoutParams(px(72), px(72)));
        dock.addView(send);
        return dock;
    }

    private void addMessage(String role, String text) {
        empty.setVisibility(View.GONE);
        boolean me = "user".equals(role);
        TextView b = Ui.text(this, text, 30, Color.WHITE, Typeface.DEFAULT);
        b.setLineSpacing(px(10), 1f);
        b.setPadding(px(30), px(20), px(30), px(22));
        b.setMaxWidth(px(1080));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(me ? Gravity.END : Gravity.START);
        if (me) {
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF0A84FF, 0xFF0060DF});
            g.setCornerRadii(new float[]{px(26), px(26), px(26), px(26), px(8), px(8), px(26), px(26)});
            b.setBackground(g);
            row.addView(b);
        } else {
            row.addView(avatar());
            GradientDrawable g = Ui.rounded(0xFF1C1C1E, px(26));
            g.setCornerRadii(new float[]{px(8), px(8), px(26), px(26), px(26), px(26), px(26), px(26)});
            b.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = px(18);
            row.addView(b, lp);
        }
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.bottomMargin = px(22);
        messages.addView(row, rp);
        while (messages.getChildCount() > 40) messages.removeViewAt(0);
        row.setAlpha(0f);
        row.setTranslationY(px(16));
        row.animate().alpha(1f).translationY(0).setDuration(180).start();
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private View avatar() {
        FrameLayout a = new FrameLayout(this);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF00A4DC, 0xFF7B5CFF});
        g.setShape(GradientDrawable.OVAL);
        a.setBackground(g);
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(new Icons(Icons.Kind.SPARK, Color.WHITE));
        a.addView(iv, new FrameLayout.LayoutParams(px(28), px(28), Gravity.CENTER));
        a.setLayoutParams(new LinearLayout.LayoutParams(px(56), px(56)));
        return a;
    }

    /** 「思考中」：头像 + 三个依次起伏的点，放在对话最末尾。 */
    private void setThinking(boolean on) {
        if (on && thinkingRow == null) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(avatar());
            LinearLayout dots = new LinearLayout(this);
            dots.setPadding(px(30), px(26), px(30), px(26));
            dots.setBackground(Ui.rounded(0xFF1C1C1E, px(26)));
            for (int i = 0; i < 3; i++) {
                View d = new View(this);
                GradientDrawable dg = new GradientDrawable();
                dg.setShape(GradientDrawable.OVAL);
                dg.setColor(0xFF8E8E93);
                d.setBackground(dg);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(14), px(14));
                lp.leftMargin = i == 0 ? 0 : px(10);
                dots.addView(d, lp);
                ObjectAnimator an = ObjectAnimator.ofFloat(d, "translationY", 0, -px(10), 0);
                an.setDuration(900);
                an.setStartDelay(i * 150L);
                an.setRepeatCount(ValueAnimator.INFINITE);
                an.start();
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = px(18);
            row.addView(dots, lp);
            thinkingRow = row;
            messages.addView(row);
            empty.setVisibility(View.GONE);
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        } else if (!on && thinkingRow != null) {
            messages.removeView(thinkingRow);
            thinkingRow = null;
        }
    }

    // ---------------------------------------------------------------- 收发

    private void pollLoop() {
        while (alive) {
            try {
                JSONObject r = gateway.events(lastSeq, typingSeen);
                JSONArray msgs = r.optJSONArray("messages");
                boolean typing = r.optBoolean("typing");
                ui.post(() -> {
                    if (msgs != null && msgs.length() > 0) setThinking(false);
                    if (msgs != null) for (int i = 0; i < msgs.length(); i++) {
                        JSONObject m = msgs.optJSONObject(i);
                        if (m.optLong("seq") <= lastSeq) continue;
                        lastSeq = m.optLong("seq");
                        addMessage(m.optString("role"), m.optString("text"));
                    }
                    typingSeen = typing;
                    setThinking(typing);
                    if (status.getText().toString().startsWith("连不上")) status.setText("");
                });
            } catch (Exception e) {
                ui.post(() -> status.setText("连不上家里的电脑：" + e.getMessage()));
                try { Thread.sleep(3000); } catch (InterruptedException ignored) { return; }
            }
        }
    }

    private void newConversation() {
        if (gateway == null) return;
        messages.removeAllViews();
        thinkingRow = null;
        empty.setVisibility(View.VISIBLE);
        new Thread(() -> {
            try {
                gateway.reset();
            } catch (Exception e) {
                ui.post(() -> status.setText("没清掉：" + e.getMessage()));
            }
        }, "agent-reset").start();
    }

    private void sendInput() {
        String t = input.getText().toString().trim();
        if (t.isEmpty()) return;
        input.setText("");
        send(t);
    }

    private void send(String text) {
        if (gateway == null) return;
        new Thread(() -> {
            try {
                gateway.chat(text);
            } catch (Exception e) {
                ui.post(() -> status.setText("没发出去：" + e.getMessage()));
            }
        }, "agent-send").start();
    }

    private void toggleListen() {
        Recorder r = recorder;
        if (r != null) r.stop();
        else startListening();
    }

    private void startListening() {
        if (gateway == null) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            return;
        }
        Recorder rec = new Recorder(true);
        recorder = rec;
        showListening(true);
        status.setText("");
        new Thread(() -> {
            String err = null, text = null;
            try {
                byte[] wav = rec.record(level -> ui.post(() -> wave(level)));
                if (wav != null) {
                    ui.post(() -> waveLabel.setText("识别中…"));
                    text = gateway.asr(wav);
                }
            } catch (Exception e) {
                err = e.getMessage();
            }
            final String fe = err, ft = text;
            ui.post(() -> {
                recorder = null;
                showListening(false);
                if (fe != null) status.setText("语音出错：" + fe);
                else if (ft == null) status.setText("按住麦克风不放，说完再松开");
                else if (ft.isEmpty()) status.setText("没听清，再说一次？");
                else send(ft);
            });
        }, "agent-listen").start();
    }

    private void showListening(boolean on) {
        input.setVisibility(on ? View.INVISIBLE : View.VISIBLE);
        ((View) waveBars.getParent()).setVisibility(on ? View.VISIBLE : View.GONE);
        waveLabel.setText("正在听…松开发送");
        if (on) {
            micPulse = ObjectAnimator.ofFloat(micButton, "alpha", 1f, 0.55f, 1f);
            micPulse.setDuration(1000);
            micPulse.setRepeatCount(ValueAnimator.INFINITE);
            micPulse.start();
        } else if (micPulse != null) {
            micPulse.cancel();
            micButton.setAlpha(1f);
        }
    }

    /** 声纹条：中间高两边低，高度跟音量走，再加一点随机抖动显得像在「听」。 */
    private void wave(float level) {
        int n = waveBars.getChildCount();
        for (int i = 0; i < n; i++) {
            float shape = 1f - Math.abs(i - (n - 1) / 2f) / ((n - 1) / 2f + 1);
            float h = px(10) + px(56) * Math.min(1f, level * 3f) * shape * (0.6f + 0.4f * (float) Math.random());
            View bar = waveBars.getChildAt(i);
            bar.getLayoutParams().height = Math.round(h);
            bar.requestLayout();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED) status.setText("没有录音权限");
    }

    private int px(int v) {
        return Ui.px(this, v);
    }
}
