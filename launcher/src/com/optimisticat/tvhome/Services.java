package com.optimisticat.tvhome;

import android.content.Context;

/** 各页面共用的 Jellyfin / gateway 客户端；没配置（assets 里缺文件）就是 null，相关功能自动隐藏。 */
final class Services {
    private static Jellyfin jellyfin;
    private static Gateway gateway;
    private static boolean loaded;

    static synchronized Jellyfin jellyfin(Context ctx) {
        load(ctx);
        return jellyfin;
    }

    static synchronized Gateway gateway(Context ctx) {
        load(ctx);
        return gateway;
    }

    private static void load(Context ctx) {
        if (loaded) return;
        loaded = true;
        Context app = ctx.getApplicationContext();
        try {
            jellyfin = new Jellyfin(app);
        } catch (Exception e) {
            jellyfin = null;
        }
        try {
            gateway = jellyfin == null ? null : new Gateway(app, jellyfin);
        } catch (Exception e) {
            gateway = null;
        }
    }
}
