package com.optimisticat.tvhome;

import android.content.Context;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/**
 * 按应用的特殊处理名单，来自 assets/apps.properties（本地文件，不入库；格式见 apps.properties.example）。
 * 没有这个文件就是空名单：鼠标模式和代理都不对任何应用生效。
 */
final class AppLists {

    /** 只有手机触屏界面、方向键走不进内容区的应用：在前台时开鼠标模式（MouseMode） */
    final Set<String> touchApps;
    /** 切到前台时让 v2rayNG 起分应用 VPN 的应用（HomeKeyService.onAppForeground） */
    final Set<String> proxyApps;

    AppLists(Context ctx) {
        Properties p = new Properties();
        try (InputStream in = ctx.getAssets().open("apps.properties")) {
            p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
        touchApps = split(p.getProperty("touch_apps", ""));
        proxyApps = split(p.getProperty("proxy_apps", ""));
    }

    private static Set<String> split(String csv) {
        Set<String> s = new HashSet<>();
        for (String x : csv.split(",")) if (!x.trim().isEmpty()) s.add(x.trim());
        return Collections.unmodifiableSet(s);
    }
}
