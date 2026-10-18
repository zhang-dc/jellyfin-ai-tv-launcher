package com.optimisticat.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * 桌面首页那两排剧的数据源：家里的 Jellyfin（取 jellyfin.properties 里 userId 那个用户的记录，一般就是电视上 Jellyfin 登录的账号）。
 *
 * 配置在 assets/jellyfin.properties（server / apiKey / userId，含密钥不入库）。
 * 家用服务器的 IP 多半由 DHCP 分配、会变，所以连不上时用 Jellyfin 的
 * 局域网发现协议（UDP 7359 广播 "Who is JellyfinServer?"）找一次，找到的地址记在 SharedPreferences。
 * 所有方法都是阻塞的，只在后台线程调。
 */
final class Jellyfin {

    static final class Item {
        String id, title, subtitle, imageUrl;
        float progress;   // 0~1，没看过为 0
    }

    private final SharedPreferences prefs;
    private final String apiKey, userId, defaultServer;
    private String server;

    Jellyfin(Context ctx) throws Exception {
        Properties p = new Properties();
        try (InputStream in = ctx.getAssets().open("jellyfin.properties")) {
            p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        }
        apiKey = p.getProperty("apiKey");
        userId = p.getProperty("userId");
        defaultServer = p.getProperty("server");
        prefs = ctx.getSharedPreferences("jellyfin", Context.MODE_PRIVATE);
        server = prefs.getString("server", defaultServer);
    }

    /** 最近播放：先放看到一半的，再放各剧的「下一集」（同一部剧只出现一次）。 */
    List<Item> recentPlayed(int limit) throws Exception {
        List<Item> out = new ArrayList<>();
        Set<String> series = new HashSet<>();
        JSONArray resume = getJson("/Users/" + userId + "/Items/Resume?Limit=" + limit
                + "&MediaTypes=Video&Fields=PrimaryImageAspectRatio").getJSONArray("Items");
        for (int i = 0; i < resume.length() && out.size() < limit; i++) {
            JSONObject o = resume.getJSONObject(i);
            out.add(toItem(o, true));
            if (o.has("SeriesId")) series.add(o.getString("SeriesId"));
        }
        JSONArray next = getJson("/Shows/NextUp?userId=" + userId + "&Limit=" + limit).getJSONArray("Items");
        for (int i = 0; i < next.length() && out.size() < limit; i++) {
            JSONObject o = next.getJSONObject(i);
            if (series.add(o.optString("SeriesId"))) out.add(toItem(o, false));
        }
        return out;
    }

    /** 最近下载（入库）：同一部剧的新集合并成一张剧集卡。 */
    List<Item> recentAdded(int limit) throws Exception {
        JSONArray arr = getJsonArray("/Users/" + userId + "/Items/Latest?Limit=" + limit
                + "&IncludeItemTypes=Episode,Movie&GroupItems=true");
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) out.add(toItem(arr.getJSONObject(i), false));
        return out;
    }

    /** 当前在用的服务器地址（可能是局域网发现后更新过的）。约定网关和它在同一台电脑上。 */
    String serverUrl() {
        return server;
    }

    Bitmap image(String url) throws Exception {
        HttpURLConnection c = open(url);
        try (InputStream in = c.getInputStream()) {
            return BitmapFactory.decodeStream(in);
        } finally {
            c.disconnect();
        }
    }

    // ------------------------------------------------------------------

    private Item toItem(JSONObject o, boolean inProgress) {
        Item it = new Item();
        it.id = o.optString("Id");
        String type = o.optString("Type");
        JSONObject tags = o.optJSONObject("ImageTags");
        if ("Episode".equals(type)) {
            it.title = o.optString("SeriesName");
            String se = "S" + o.optInt("ParentIndexNumber") + "E" + o.optInt("IndexNumber");
            it.subtitle = se + " · " + o.optString("Name");
            // 单集截图是 16:9 的 Primary；没有就用剧的横幅
            if (tags != null && tags.has("Primary")) it.imageUrl = img(it.id, "Primary");
            else if (o.has("SeriesThumbImageTag")) it.imageUrl = img(o.optString("SeriesId"), "Thumb");
            else if (o.has("ParentBackdropItemId")) it.imageUrl = img(o.optString("ParentBackdropItemId"), "Backdrop");
        } else {
            it.title = o.optString("Name");
            int count = o.optJSONObject("UserData") != null ? o.optJSONObject("UserData").optInt("UnplayedItemCount") : 0;
            it.subtitle = "Series".equals(type) ? (count > 0 ? count + " 集未看" : "剧集")
                    : (o.optInt("ProductionYear") > 0 ? o.optInt("ProductionYear") + " · 电影" : "电影");
            JSONArray bd = o.optJSONArray("BackdropImageTags");
            if (tags != null && tags.has("Thumb")) it.imageUrl = img(it.id, "Thumb");
            else if (bd != null && bd.length() > 0) it.imageUrl = img(it.id, "Backdrop");
            else if (tags != null && tags.has("Primary")) it.imageUrl = img(it.id, "Primary");
        }
        JSONObject ud = o.optJSONObject("UserData");
        if (inProgress && ud != null) it.progress = (float) ud.optDouble("PlayedPercentage", 0) / 100f;
        return it;
    }

    private String img(String id, String kind) {
        return "/Items/" + id + "/Images/" + kind + "?maxWidth=480&quality=80";
    }

    private JSONObject getJson(String path) throws Exception {
        return new JSONObject(get(path));
    }

    private JSONArray getJsonArray(String path) throws Exception {
        return new JSONArray(get(path));
    }

    private String get(String path) throws Exception {
        HttpURLConnection c = open(path);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** 先用记住的地址；连不上就局域网广播找服务器，换了地址重试一次。 */
    private HttpURLConnection open(String path) throws Exception {
        try {
            return connect(server + path);
        } catch (java.io.IOException e) {
            String found = discover();
            if (found == null || found.equals(server)) throw e;
            server = found;
            prefs.edit().putString("server", found).apply();
            return connect(server + path);
        }
    }

    private HttpURLConnection connect(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(2500);
        c.setReadTimeout(8000);
        c.setRequestProperty("X-Emby-Token", apiKey);
        c.connect();
        if (c.getResponseCode() != 200) {
            c.disconnect();
            throw new java.io.IOException("HTTP " + c.getResponseCode() + " " + url);
        }
        return c;
    }

    private String discover() {
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            s.setSoTimeout(2000);
            byte[] q = "Who is JellyfinServer?".getBytes(StandardCharsets.US_ASCII);
            s.send(new DatagramPacket(q, q.length, InetAddress.getByName("255.255.255.255"), 7359));
            byte[] buf = new byte[2048];
            DatagramPacket r = new DatagramPacket(buf, buf.length);
            s.receive(r);
            String addr = new JSONObject(new String(buf, 0, r.getLength(), StandardCharsets.UTF_8)).optString("Address");
            return addr.isEmpty() ? null : addr.replaceAll("/+$", "");
        } catch (Exception e) {
            return defaultServer.equals(server) ? null : defaultServer;
        }
    }
}
