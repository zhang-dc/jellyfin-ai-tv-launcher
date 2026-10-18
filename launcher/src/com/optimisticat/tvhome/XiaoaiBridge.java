package com.optimisticat.tvhome;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 遥控器语音键 → 我们的 Agent：借小爱听写。
 *
 * 小米遥控器的语音键不走安卓按键通道（无障碍服务收不到），录音走小爱私有的遥控器麦克风通道（日志里 audioSource=RC），
 * 第三方拿不到。但小爱每次识别完会打一行日志：
 *   onConversationFinished Conversation(type=QUERY, engineType=NEAR_FIELD, queryType=ASR, query=最近有没有什么新的电影, requestId=…
 * 所以「问问」/「找剧」页开着时，读 logcat 里这一行，拿到文字交给当前页面，同时关掉小爱的弹窗。
 * 需要 READ_LOGS（adb pm grant，见 tv.mjs enable-home-service）。小爱升级改了日志格式就会失效——到时看 logcat 重新对正则。
 */
final class XiaoaiBridge {

    interface Target {
        void onVoiceText(String text);
    }

    private static final String TAG = "TvXiaoai";
    private static final Pattern QUERY = Pattern.compile("onConversationFinished Conversation\\(type=QUERY,.*?queryType=ASR, query=(.*?), requestId=");
    private static final Handler ui = new Handler(Looper.getMainLooper());
    private static Target target;
    private static Thread reader;
    private static Process proc;

    /** 页面 onResume 调：之后小爱听到的话交给它。 */
    static synchronized void attach(Target t) {
        target = t;
        if (reader == null) start();
    }

    /** 页面 onDestroy / 回到首页时调。 */
    static synchronized void detach(Target t) {
        if (target != t) return;
        target = null;
        stop();
    }

    static synchronized void detachAll() {
        target = null;
        stop();
    }

    private static void start() {
        reader = new Thread(() -> {
            try {
                // -T 1：只看从现在起的新日志；-e：只要这一行，读起来很轻
                proc = Runtime.getRuntime().exec(new String[]{"logcat", "-v", "raw", "-T", "1", "-e", "onConversationFinished"});
                BufferedReader in = new BufferedReader(new InputStreamReader(proc.getInputStream(), "UTF-8"));
                for (String line; (line = in.readLine()) != null; ) {
                    Matcher m = QUERY.matcher(line);
                    if (!m.find()) continue;
                    String text = m.group(1).trim();
                    if (text.isEmpty()) continue;
                    Log.i(TAG, "xiaoai heard: " + text);
                    ui.post(() -> deliver(text));
                }
            } catch (Exception e) {
                Log.w(TAG, "logcat reader stopped: " + e);
            }
        }, "xiaoai-bridge");
        reader.setDaemon(true);
        reader.start();
    }

    private static synchronized void stop() {
        if (proc != null) proc.destroy();
        proc = null;
        reader = null;
    }

    private static void deliver(String text) {
        Target t;
        synchronized (XiaoaiBridge.class) {
            t = target;
        }
        if (t == null) return;
        HomeKeyService.dismissXiaoai();   // 小爱弹窗还在就关掉，别让它自己也答一遍
        t.onVoiceText(text);
    }
}
