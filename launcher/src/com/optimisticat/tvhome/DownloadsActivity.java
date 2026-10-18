package com.optimisticat.tvhome;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * 「下载进度」：后端报上来的全部下载任务（GET /tv/hub/downloads，见 docs/protocol.md），每 3 秒刷新。
 * 每个任务一张卡：左边状态圆标（下载中蓝 / 暂停灰 / 完成绿），中间名字 + 速度剩余时间，右边大号百分比，底边进度条。
 * 确认键弹操作：暂停 / 继续 / 删除任务（默认保留已下载的文件）。
 */
public class DownloadsActivity extends Activity {

    private static final int BLUE = 0xFF0A84FF, GREEN = 0xFF30D158, GRAY = 0xFF8E8E93;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Gateway gateway;
    private LinearLayout list, empty;
    private TextView summary;
    private volatile boolean alive;
    private String signature = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.fullscreen(this);
        gateway = Services.gateway(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Ui.BG);
        col.setPadding(px(96), px(56), px(96), px(24));
        LinearLayout head = Ui.pageHeader(this, Icons.Kind.DOWNLOAD, "下载进度", "读取中…");
        summary = (TextView) ((LinearLayout) head.getChildAt(1)).getChildAt(1);
        col.addView(head);

        FrameLayout body = new FrameLayout(this);
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setFocusable(false);   // 别让滚动容器自己抢焦点（还会被系统加一整块灰色高亮）
        Ui.noHighlight(sv);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(px(12), px(36), px(12), px(40));
        list.setClipChildren(false);
        list.setClipToPadding(false);
        sv.addView(list);
        body.addView(sv, new FrameLayout.LayoutParams(-1, -1));
        empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER_HORIZONTAL);
        ImageView ei = new ImageView(this);
        ei.setImageDrawable(new Icons(Icons.Kind.DOWNLOAD, 0xFF3A3A3C));
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(px(96), px(96));
        ep.gravity = Gravity.CENTER_HORIZONTAL;
        empty.addView(ei, ep);
        TextView et = Ui.text(this, "现在没有下载任务", 30, Ui.TEXT_DIM, Typeface.DEFAULT);
        et.setGravity(Gravity.CENTER);
        et.setPadding(0, px(20), 0, 0);
        empty.addView(et);
        empty.setVisibility(View.GONE);
        body.addView(empty, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        col.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(col);
        if (gateway == null) summary.setText("没有配置 gateway（assets/gateway.properties），用不了");
    }

    @Override
    protected void onResume() {
        super.onResume();
        alive = true;
        if (gateway != null) new Thread(this::loop, "downloads").start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        alive = false;
    }

    private void loop() {
        while (alive) {
            try {
                JSONArray d = gateway.hubGet("downloads", null).optJSONArray("downloads");
                ui.post(() -> show(d));
            } catch (Exception e) {
                ui.post(() -> summary.setText("读不到下载列表：" + e.getMessage()));
            }
            try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
        }
    }

    static boolean active(JSONObject t) {
        String s = t.optString("state");
        return t.optInt("progress") < 100 && !s.startsWith("paused") && !s.startsWith("stopped") && !s.equals("error");
    }

    private static boolean done(JSONObject t) {
        return t.optInt("progress") >= 100;
    }

    private void show(JSONArray d) {
        List<JSONObject> rows = new ArrayList<>();
        for (int i = 0; d != null && i < d.length(); i++) rows.add(d.optJSONObject(i));
        // 下载中 → 暂停 → 已完成；同类按进度
        Collections.sort(rows, (a, b) -> rank(a) != rank(b) ? rank(a) - rank(b) : a.optInt("progress") - b.optInt("progress"));
        int running = 0, finished = 0;
        long speed = 0;
        for (JSONObject t : rows) {
            if (active(t)) { running++; speed += t.optLong("dlspeed"); }
            if (done(t)) finished++;
        }
        summary.setText(rows.isEmpty() ? "没有任务" : running + " 个在下载" + (running > 0 ? "（共 " + Ui.size(speed) + "/s）" : "")
                + " · " + finished + " 个已完成，还在做种");
        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);

        StringBuilder sig = new StringBuilder();
        for (JSONObject t : rows) sig.append(t.optString("hash")).append(rank(t)).append(',');
        View focused = getCurrentFocus();
        String focusedHash = focused != null && focused.getTag() instanceof String ? (String) focused.getTag() : null;
        if (!sig.toString().equals(signature)) {
            signature = sig.toString();
            list.removeAllViews();
            for (JSONObject t : rows) list.addView(row(t));
        }
        // 原地更新进度，不重建（重建会把遥控器焦点弄丢）
        for (int i = 0; i < list.getChildCount() && i < rows.size(); i++) update(list.getChildAt(i), rows.get(i));
        if (getCurrentFocus() == null && list.getChildCount() > 0) {
            View target = list.getChildAt(0);
            for (int i = 0; i < list.getChildCount(); i++) if (list.getChildAt(i).getTag().equals(focusedHash)) target = list.getChildAt(i);
            target.requestFocus();
        }
    }

    private static int rank(JSONObject t) {
        return active(t) ? 0 : done(t) ? 2 : 1;
    }

    private View row(JSONObject t) {
        FrameLayout card = new FrameLayout(this);
        card.setTag(t.optString("hash"));
        card.setClipToOutline(true);
        GradientDrawable idle = Ui.rounded(0xFF1C1C1E, px(20));
        GradientDrawable on = Ui.rounded(0xFF2C2C2E, px(20));
        card.setBackground(idle);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, px(128));
        lp.bottomMargin = px(16);
        card.setLayoutParams(lp);

        LinearLayout content = new LinearLayout(this);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(px(28), 0, px(36), 0);

        int color = active(t) ? BLUE : done(t) ? GREEN : GRAY;
        FrameLayout dot = new FrameLayout(this);
        GradientDrawable dg = new GradientDrawable();
        dg.setShape(GradientDrawable.OVAL);
        dg.setColor((color & 0x00FFFFFF) | 0x33000000);
        dot.setBackground(dg);
        ImageView di = new ImageView(this);
        di.setImageDrawable(new Icons(active(t) ? Icons.Kind.DOWNLOAD : done(t) ? Icons.Kind.PLAY : Icons.Kind.PAUSE, color));
        dot.addView(di, new FrameLayout.LayoutParams(px(32), px(32), Gravity.CENTER));
        content.addView(dot, new LinearLayout.LayoutParams(px(68), px(68)));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(px(24), 0, px(24), 0);
        TextView name = Ui.text(this, t.optString("name"), 26, Color.WHITE, Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        mid.addView(name);
        TextView meta = Ui.text(this, "", 22, Ui.TEXT_DIM, Typeface.DEFAULT);
        meta.setPadding(0, px(8), 0, 0);
        mid.addView(meta);
        content.addView(mid, new LinearLayout.LayoutParams(0, -2, 1));

        TextView pct = Ui.text(this, "", 40, color, Typeface.DEFAULT_BOLD);
        pct.setGravity(Gravity.END);
        content.addView(pct, new LinearLayout.LayoutParams(px(140), -2));
        card.addView(content, new FrameLayout.LayoutParams(-1, -1));

        View track = new View(this);
        track.setBackgroundColor(0x14FFFFFF);
        card.addView(track, new FrameLayout.LayoutParams(-1, px(6), Gravity.BOTTOM));
        View bar = new View(this);
        bar.setBackgroundColor(color);
        card.addView(bar, new FrameLayout.LayoutParams(0, px(6), Gravity.BOTTOM));

        Ui.focusRing(this, card, 1.015f);
        View.OnFocusChangeListener ring = card.getOnFocusChangeListener();
        card.setOnFocusChangeListener((v, has) -> { ring.onFocusChange(v, has); v.setBackground(has ? on : idle); });
        card.setOnClickListener(v -> actions(t));
        return card;
    }

    private void update(View row, JSONObject t) {
        FrameLayout card = (FrameLayout) row;
        LinearLayout content = (LinearLayout) card.getChildAt(0);
        TextView meta = (TextView) ((LinearLayout) content.getChildAt(1)).getChildAt(1);
        TextView pct = (TextView) content.getChildAt(2);
        View bar = card.getChildAt(2);
        int p = t.optInt("progress");
        pct.setText(p + "%");
        String state = done(t) ? "已下完 · 做种中" : active(t)
                ? Ui.size(t.optLong("dlspeed")) + "/s" + (Ui.eta(t.optLong("eta")).isEmpty() ? "" : " · " + Ui.eta(t.optLong("eta")))
                : "已暂停";
        meta.setText(t.optString("kind") + " · " + Ui.size(t.optLong("size")) + " · " + state);
        int w = card.getWidth();
        if (w > 0) {
            bar.getLayoutParams().width = Math.round(w * p / 100f);
            bar.requestLayout();
        } else {
            card.post(() -> update(row, t));
        }
    }

    private void actions(JSONObject t) {
        String hash = t.optString("hash");
        boolean paused = !active(t) && !done(t);
        String first = done(t) ? "停止做种" : paused ? "继续下载" : "暂停";
        HashSet<Integer> danger = new HashSet<>();
        danger.add(2);
        Ui.choose(this, t.optString("name"), t.optInt("progress") + "% · " + Ui.size(t.optLong("size")),
                new String[]{first, "删除任务（保留已下载的文件）", "删除任务和文件", "取消"}, danger, which -> {
                    try {
                        JSONObject body = new JSONObject().put("hash", hash);
                        if (which == 0) body.put("action", paused ? "start" : "stop");
                        else if (which == 1) body.put("action", "delete").put("deleteFiles", false);
                        else if (which == 2) {
                            HashSet<Integer> red = new HashSet<>();
                            red.add(0);
                            Ui.choose(this, "连文件一起删？", "删了要重新下", new String[]{"删除", "取消"}, red, w -> {
                                if (w == 0) {
                                    try {
                                        task(new JSONObject().put("hash", hash).put("action", "delete").put("deleteFiles", true));
                                    } catch (Exception ignored) {
                                    }
                                }
                            });
                            return;
                        } else return;
                        task(body);
                    } catch (Exception ignored) {
                    }
                });
    }

    private void task(JSONObject body) {
        new Thread(() -> {
            try {
                gateway.hubPost("task", body);
            } catch (Exception e) {
                ui.post(() -> summary.setText("操作失败：" + e.getMessage()));
            }
        }, "task").start();
    }

    private int px(int v) {
        return Ui.px(this, v);
    }
}
