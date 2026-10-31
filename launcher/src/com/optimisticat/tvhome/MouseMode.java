package com.optimisticat.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.HashSet;
import java.util.Set;

/**
 * 鼠标模式：只做了手机触屏交互的应用（遥控器方向键走不进内容区）在前台时，屏幕上出一个光标。
 * - 短按方向键：光标挪一步；按住：连续移动（匀加速）；顶到屏幕边再往外推 = 滑动翻页。
 * - 确认键 = 点一下，长按确认 = 长按；菜单键开 / 关鼠标模式（关了就是应用自己的方向键焦点）；返回键照常交给应用。
 *
 * 光标是无障碍浮层（TYPE_ACCESSIBILITY_OVERLAY，不抢焦点、不挡触摸），点击 / 滑动用 dispatchGesture 模拟，
 * 都只靠 HomeKeyService 已有的无障碍权限，不用 root、不用 INJECT_EVENTS。
 */
final class MouseMode {

    private static final String TAG = "TvMouse";
    /** 遥控器确认键大家按得慢，0.5 秒会把普通点击误判成长按 */
    private static final long LONG_PRESS_MS = 800;
    /** 方向键按住超过这么久开始连续移动（按下时先挪一步） */
    private static final long HOLD_MS = 280;
    /** 短按一步的距离（1080p） */
    private static final float STEP = 48;
    private static final String HINT = "鼠标模式 · 方向键移动（按住连续移动）· 确认键点击 · 菜单键关闭";

    /** 默认开鼠标模式的应用（assets/apps.properties 的 touch_apps） */
    private final Set<String> touchApps;

    private final AccessibilityService service;
    private final WindowManager wm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final int screenW, screenH;
    private final float unit;   // 1080p 下 1，按屏宽缩放
    private CursorView cursor;
    private WindowManager.LayoutParams cursorLp;
    private TextView hint;
    private float x, y;
    private String pkg;
    /** 用户在这些应用里按菜单键关掉了鼠标模式（进程内记住，换应用再回来仍是关的） */
    private final Set<String> userOff = new HashSet<>();
    private boolean okDown;
    /** 确认键按住到点就直接长按（不等松手），光标恢复原样当反馈；松手后不再补一次点击 */
    private final Runnable longPress = () -> {
        if (!okDown || cursor == null) return;
        cursor.setPressed(false);
        press(800);
    };

    // 方向键状态
    private int dirX, dirY;
    private boolean freeMoving;
    private long freeStart, lastTick, lastSwipe;

    MouseMode(AccessibilityService service, Set<String> touchApps) {
        this.service = service;
        this.touchApps = touchApps;
        wm = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
        Point p = new Point();
        wm.getDefaultDisplay().getRealSize(p);
        screenW = Math.max(p.x, p.y);
        screenH = Math.min(p.x, p.y);
        unit = screenW / 1920f;
        x = screenW / 2f;
        y = screenH / 2f;
    }

    boolean active() {
        return cursor != null;
    }

    /** 前台应用变了（或同一个应用的窗口变了）就调一次 */
    void onForeground(String foreground) {
        if (foreground == null || foreground.equals(pkg)) return;
        pkg = foreground;
        boolean want = touchApps.contains(pkg) && !userOff.contains(pkg);
        if (want && cursor == null) show(true);
        else if (!want && cursor != null) hide();
    }

    /** 返回 true 表示按键被鼠标模式吃掉 */
    boolean onKey(KeyEvent e) {
        int code = e.getKeyCode();
        // 菜单键只在名单里的应用接管（别的应用 / 我们自己的播放器要用菜单键）
        if (code == KeyEvent.KEYCODE_MENU && touchApps.contains(pkg)) {
            if (e.getAction() == KeyEvent.ACTION_UP) toggle();
            return true;
        }
        if (cursor == null) return false;
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: arrow(down, e.getRepeatCount(), 0, -1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: arrow(down, e.getRepeatCount(), 0, 1); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: arrow(down, e.getRepeatCount(), -1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: arrow(down, e.getRepeatCount(), 1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down && e.getRepeatCount() == 0) {
                    okDown = true;
                    cursor.setPressed(true);
                    ui.postDelayed(longPress, LONG_PRESS_MS);
                } else if (!down && okDown) {
                    // 长按到点已经在按住时做掉了，松手就只收尾
                    okDown = false;
                    ui.removeCallbacks(longPress);
                    if (cursor.isPressed()) { cursor.setPressed(false); press(60); }
                }
                return true;
            default:
                return false;
        }
    }

    void release() {
        hide();
    }

    // ---------------------------------------------------------------- 方向键：短按一步、按住连续移动

    private void arrow(boolean down, int repeat, int dx, int dy) {
        if (down) {
            if (repeat > 0) return;   // 按住时系统补发的重复按键不管，自由移动自己按帧走
            stopFree();
            dirX = dx;
            dirY = dy;
            step(dx, dy);
            ui.postDelayed(startFree, HOLD_MS);
        } else {
            if (dx != dirX || dy != dirY) return;   // 松开的不是当前方向（两个键交叠按）
            ui.removeCallbacks(startFree);
            stopFree();
            dirX = dirY = 0;
        }
    }

    private final Runnable startFree = () -> {
        freeMoving = true;
        freeStart = lastTick = SystemClock.uptimeMillis();
        ui.post(this::freeTick);
    };

    private void freeTick() {
        if (!freeMoving || cursor == null) return;
        long now = SystemClock.uptimeMillis();
        if (now - freeStart > 15_000) { stopFree(); return; }   // 抬起事件丢了的兜底
        float dt = (now - lastTick) / 1000f, held = (now - freeStart) / 1000f;
        lastTick = now;
        // 起步 450px/s，一秒内加到 1700px/s：先能对准，再能快速横穿屏幕
        float speed = (450 + Math.min(held, 1f) * 1250) * unit;
        float nx = x + dirX * speed * dt, ny = y + dirY * speed * dt;
        float m = 2 * unit;
        if ((nx <= m && dirX < 0) || (nx >= screenW - m && dirX > 0) || (ny <= m && dirY < 0) || (ny >= screenH - m && dirY > 0)) {
            if (now - lastSwipe > 450) { lastSwipe = now; scroll(dirX, dirY); }
        }
        x = clamp(nx, m, screenW - m);
        y = clamp(ny, m, screenH - m);
        place();
        ui.postDelayed(this::freeTick, 16);
    }

    /** 短按：挪一步；已经顶在边上还往外按就翻页 */
    private void step(int dx, int dy) {
        float m = 2 * unit, s = STEP * unit;
        float nx = x + dx * s, ny = y + dy * s;
        if ((nx < m && dx < 0) || (nx > screenW - m && dx > 0) || (ny < m && dy < 0) || (ny > screenH - m && dy > 0)) {
            long now = SystemClock.uptimeMillis();
            if (now - lastSwipe > 350) { lastSwipe = now; scroll(dx, dy); }
        }
        x = clamp(nx, m, screenW - m);
        y = clamp(ny, m, screenH - m);
        place();
    }

    private void stopFree() {
        freeMoving = false;
        ui.removeCallbacks(startFree);
    }

    // ---------------------------------------------------------------- 手势

    private void press(long duration) {
        Path p = new Path();
        p.moveTo(x, y);
        dispatch(p, duration);
        Log.i(TAG, (duration > 100 ? "long press " : "tap ") + Math.round(x) + "," + Math.round(y));
    }

    /** 按方向键的方向翻内容（手指往反方向划），一次 0.4 屏 */
    private void scroll(int dx, int dy) {
        float cx = dx != 0 ? screenW / 2f : clamp(x, screenW * 0.15f, screenW * 0.85f);
        float cy = dy != 0 ? screenH / 2f : clamp(y, screenH * 0.2f, screenH * 0.8f);
        float half = (dy != 0 ? screenH : screenW) * 0.2f;
        Path p = new Path();
        p.moveTo(cx + dx * half, cy + dy * half);
        p.lineTo(cx - dx * half, cy - dy * half);
        dispatch(p, 280);
        Log.i(TAG, "scroll " + dx + "," + dy);
    }

    private void dispatch(Path path, long duration) {
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, duration)).build();
        if (!service.dispatchGesture(g, null, null)) Log.w(TAG, "dispatchGesture refused");
    }

    // ---------------------------------------------------------------- 浮层

    private void toggle() {
        if (cursor != null) {
            userOff.add(pkg);
            hide();
            flashHint("鼠标模式已关 · 菜单键重新打开");
        } else {
            userOff.remove(pkg);
            show(false);
            flashHint(HINT);
        }
    }

    private void show(boolean withHint) {
        if (cursor != null) return;
        cursor = new CursorView(service, unit);
        int size = Math.round(CursorView.SIZE * unit);
        cursorLp = overlayParams(size, size);
        place();
        try {
            wm.addView(cursor, cursorLp);
        } catch (RuntimeException ex) {
            Log.w(TAG, "add cursor failed", ex);
            cursor = null;
            return;
        }
        Log.i(TAG, "on for " + pkg);
        if (withHint) flashHint(HINT);
    }

    private void hide() {
        stopFree();
        if (cursor == null) return;
        try { wm.removeView(cursor); } catch (RuntimeException ignored) { }
        cursor = null;
        Log.i(TAG, "off");
    }

    private void place() {
        if (cursorLp == null) return;
        // 光标尖端在窗口左上角往里 2.8px（1080p）的位置
        cursorLp.x = Math.round(x - 2.8f * unit);
        cursorLp.y = Math.round(y - 2.8f * unit);
        if (cursor != null && cursor.isAttachedToWindow()) wm.updateViewLayout(cursor, cursorLp);
    }

    private void flashHint(String text) {
        if (hint == null) {
            hint = new TextView(service);
            hint.setTextColor(Color.WHITE);
            hint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 26 * unit);
            hint.setTypeface(Typeface.DEFAULT_BOLD);
            int ph = Math.round(30 * unit), pv = Math.round(14 * unit);
            hint.setPadding(ph, pv, ph, pv);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(0xCC000000);
            bg.setCornerRadius(28 * unit);
            hint.setBackground(bg);
            WindowManager.LayoutParams lp = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = Math.round(48 * unit);
            try { wm.addView(hint, lp); } catch (RuntimeException ex) { hint = null; return; }
        }
        hint.setText(text);
        hint.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideHint);
        ui.postDelayed(hideHint, 4000);
    }

    private final Runnable hideHint = this::removeHint;

    private void removeHint() {
        if (hint == null) return;
        try { wm.removeView(hint); } catch (RuntimeException ignored) { }
        hint = null;
    }

    private static WindowManager.LayoutParams overlayParams(int w, int h) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        return lp;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** 白色箭头 + 黑描边；按下确认键时变小一圈当按下反馈 */
    private static final class CursorView extends View {
        static final int SIZE = 72;   // 1.4 倍的 52：3 米外看得清
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();
        private final float unit;

        CursorView(Context c, float unit) {
            super(c);
            this.unit = unit;
            fill.setColor(Color.WHITE);
            stroke.setColor(0xFF111111);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(4 * unit);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            float u = unit * 1.4f;
            arrow.moveTo(2 * u, 2 * u);
            arrow.lineTo(2 * u, 40 * u);
            arrow.lineTo(12 * u, 31 * u);
            arrow.lineTo(19 * u, 47 * u);
            arrow.lineTo(26 * u, 44 * u);
            arrow.lineTo(19 * u, 28 * u);
            arrow.lineTo(32 * u, 28 * u);
            arrow.close();
            setLayerType(LAYER_TYPE_HARDWARE, null);
        }

        @Override
        protected void onDraw(Canvas c) {
            float s = isPressed() ? 0.82f : 1f;
            c.save();
            c.scale(s, s, 2.8f * unit, 2.8f * unit);
            c.drawPath(arrow, fill);
            c.drawPath(arrow, stroke);
            c.restore();
        }

        @Override
        public void setPressed(boolean pressed) {
            super.setPressed(pressed);
            invalidate();
        }
    }
}
