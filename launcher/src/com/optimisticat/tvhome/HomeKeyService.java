package com.optimisticat.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * 让遥控器主页键进本桌面 —— 国内版小米电视不让第三方当默认桌面，只能这么绕。
 *
 * 2026-10-19 在 MiTV-MTEQ0 上实测：
 * - `cmd package set-home-activity` 返回 Success 但偏好不落盘，主页仍是原厂 com.mitv.tvhome；
 * - 停用原厂桌面后系统宁可停在 FallbackHome 空白页（日志 "User unlocked but no home"），
 *   也不选我们（query-activities 里我们 priority 0、FallbackHome -1000）—— 小米只认系统应用当主页。
 * 所以原厂桌面保持启用，由这个无障碍服务：
 *   1. 截下主页键，直接拉起本桌面（按键被吃掉，原厂桌面不会出来）；
 *   2. 原厂桌面自己跑到前台（开机、从应用退出落回主页）时切回本桌面；
 *   3. 用户从「小米影视」入口主动打开原厂桌面时放行（MainActivity 打开前调 allowStockHome()）；
 *   4. 只有手机触屏界面的应用在前台时给遥控器一个光标（MouseMode）。
 * 无障碍服务被系统绑定，所以后台拉起 Activity 不受 Android 10 的限制。
 *
 * 启用（adb，一次性）：node tv.mjs enable-home-service。
 */
public class HomeKeyService extends AccessibilityService {

    private static final String TAG = "TvHomeKey";
    static final String STOCK_HOME = "com.mitv.tvhome";
    /** 主动打开原厂桌面后的放行截止时刻（elapsedRealtime）；同进程共享，不用持久化。 */
    private static long allowStockUntil = 0;
    /** 用户正在用「小米影视」（含从它点进去的播放器）：期间原厂桌面在前台不拦，直到按主页键或回到本桌面。 */
    private static boolean inStockHome = false;

    private static HomeKeyService instance;
    /** 触屏应用的遥控器光标，见 MouseMode */
    private MouseMode mouse;

    /**
     * 小爱的弹窗 / 卡片还在屏幕上就按一次返回关掉（借小爱听写后，别让它自己再答一遍、再念一遍）。
     * 先确认前台真是小爱再按，不然返回键会落到我们自己的页面上。
     */
    static void dismissXiaoai() {
        HomeKeyService s = instance;
        if (s == null) return;
        for (android.view.accessibility.AccessibilityWindowInfo w : s.getWindows()) {
            android.view.accessibility.AccessibilityNodeInfo root = w.getRoot();
            if (root == null) continue;
            CharSequence p = root.getPackageName();
            root.recycle();
            if (p != null && "com.xiaomi.voicecontrol".contentEquals(p)) {
                s.performGlobalAction(GLOBAL_ACTION_BACK);
                Log.i(TAG, "dismissed xiaoai");
                return;
            }
        }
    }

    static void leftStockHome() {
        inStockHome = false;
    }

    static void allowStockHome() {
        allowStockUntil = SystemClock.elapsedRealtime() + 5000;
        inStockHome = true;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME) return mouse != null && mouse.onKey(event);
        if (event.getAction() == KeyEvent.ACTION_UP) {
            inStockHome = false;
            goHome();
        }
        return true;   // 按下、抬起都吃掉，系统的主页逻辑一概不走
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        // 原厂桌面从应用退回前台时不发 windowStateChanged（实测只收到 systemui 的），
        // 所以不看事件本身是谁发的，而是每次窗口变化都看当前活动窗口归哪个包
        String pkg = foregroundPackage();
        if (mouse != null) {
            // 普通应用看活动窗口（使用记录在应用刚打开时还没写进去，会慢一拍）；原厂桌面对无障碍隐藏，才退回用使用记录
            String win = activeAppWindowPackage();
            mouse.onForeground(win != null ? win : pkg);
            onAppForeground(win != null ? win : pkg);
        }
        if (!STOCK_HOME.equals(pkg)) return;
        if (inStockHome || SystemClock.elapsedRealtime() < allowStockUntil) return;
        Log.i(TAG, "stock home in front, redirect");
        goHome();
    }

    /**
     * 当前前台应用。原厂桌面的窗口对无障碍服务完全隐藏（getWindows() 里没有它、getRootInActiveWindow() 为 null），
     * 所以看系统的应用使用记录：最近一条「切到前台」事件是谁。需要 GET_USAGE_STATS（adb appops 授予，见 tv.mjs）。
     */
    private String foregroundPackage() {
        android.app.usage.UsageStatsManager usm =
                (android.app.usage.UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
        long now = System.currentTimeMillis();
        android.app.usage.UsageEvents events = usm.queryEvents(now - 60_000, now + 1000);
        android.app.usage.UsageEvents.Event ev = new android.app.usage.UsageEvents.Event();
        String last = null;
        while (events.hasNextEvent()) {
            events.getNextEvent(ev);
            if (ev.getEventType() == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) last = ev.getPackageName();
        }
        return last;
    }

    /** 鼠标模式 / 代理的应用名单（assets/apps.properties） */
    private AppLists apps;
    private String lastForeground;

    /**
     * 名单里的应用（电视 DNS 被污染、本身没代理，靠 v2rayNG 分应用 VPN 转到本机 Clash）切到前台时让 v2rayNG 起 VPN —— 电视待机会把 v2rayNG 一起强停，VPN 就断了。
     * 走它给 Tasker 开的接收器；它自己会判断是否已在运行，重复发不会断线。
     * 被强停的包收不到隐式广播，所以显式指定组件 + FLAG_INCLUDE_STOPPED_PACKAGES。
     */
    private void onAppForeground(String pkg) {
        if (pkg == null || pkg.equals(lastForeground)) return;
        lastForeground = pkg;
        if (!apps.proxyApps.contains(pkg)) return;
        android.os.Bundle b = new android.os.Bundle();
        b.putBoolean("tasker_extra_bundle_switch", true);
        b.putString("tasker_extra_bundle_guid", "Default");
        Intent i = new Intent("com.twofortyfouram.locale.intent.action.FIRE_SETTING")
                .setClassName("com.v2ray.ang", "com.v2ray.ang.receiver.TaskerReceiver")
                .putExtra("com.twofortyfouram.locale.intent.extra.BUNDLE", b)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
        sendBroadcast(i);
        Log.i(TAG, "kick v2rayNG for " + pkg);
    }

    private String activeAppWindowPackage() {
        for (android.view.accessibility.AccessibilityWindowInfo w : getWindows()) {
            if (w.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION || !w.isActive()) continue;
            android.view.accessibility.AccessibilityNodeInfo root = w.getRoot();
            if (root == null) continue;
            CharSequence p = root.getPackageName();
            root.recycle();
            if (p != null) return p.toString();
        }
        return null;
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        if (mouse != null) { mouse.release(); mouse = null; }
        return super.onUnbind(intent);
    }

    @Override
    protected void onServiceConnected() {
        Log.i(TAG, "connected");
        instance = this;
        apps = new AppLists(this);
        mouse = new MouseMode(this, apps.touchApps);
        // 刚连上时前台可能已经是触屏应用（guard 补回服务、系统重绑），不等下一个窗口事件
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (mouse == null) return;
            String win = activeAppWindowPackage();
            String fg = win != null ? win : foregroundPackage();
            mouse.onForeground(fg);
            onAppForeground(fg);
        }, 500);
    }

    private void goHome() {
        Log.i(TAG, "goHome");
        Intent i = new Intent(this, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startActivity(i);
    }
}
