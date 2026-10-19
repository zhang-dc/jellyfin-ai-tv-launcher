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
        String id, type, title, subtitle, imageUrl;
        String seriesId;   // 单集所属的剧，别的为空
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

    /**
     * 最近播放：「看到一半的」和「各剧下一集」合在一起，**按最后观看时间排**，同一部剧只留一张卡
     * （有看到一半的集就放那一集，否则放下一集）。
     *
     * 2026-10-20 用户问「刚看的行尸走肉怎么不在首页」：一口气看完 S1E1~E3 都是「已看完」，不进 Resume，
     * 只在 NextUp 里（还排第一）；而原来是先放满 Resume 再补 NextUp，Resume 里几个月前的旧条目正好凑够 12 条，
     * NextUp 一条都没轮上。所以改成按时间合并。下一集的时间取「这部剧最近看完的那一集」和它自己的时间里较新的。
     */
    List<Item> recentPlayed(int limit) throws Exception {
        java.util.Map<String, Object[]> best = new java.util.LinkedHashMap<>();   // key -> {Item, lastPlayedMs}
        JSONArray resume = getJson("/Users/" + userId + "/Items/Resume?Limit=" + (limit * 2)
                + "&MediaTypes=Video&Fields=PrimaryImageAspectRatio").getJSONArray("Items");
        for (int i = 0; i < resume.length(); i++) {
            JSONObject o = resume.getJSONObject(i);
            String key = o.has("SeriesId") ? o.getString("SeriesId") : o.optString("Id");
            long t = playedMs(o.optJSONObject("UserData"));
            Object[] cur = best.get(key);
            if (cur == null || (long) cur[1] < t) best.put(key, new Object[]{toItem(o, true), t});
        }
        JSONArray next = getJson("/Shows/NextUp?userId=" + userId + "&Limit=" + (limit * 2)).getJSONArray("Items");
        // 剧的最后观看时间：Jellyfin 在「剧」这一层不记 LastPlayedDate（实测为空），只在单集上记。
        // 一次取最近看完的一批单集，按剧取最新的那个时间。
        java.util.Map<String, Long> seriesPlayed = new java.util.HashMap<>();
        JSONArray played = getJson("/Users/" + userId + "/Items?Recursive=true&IncludeItemTypes=Episode&Filters=IsPlayed"
                + "&SortBy=DatePlayed&SortOrder=Descending&Limit=200&Fields=UserData").optJSONArray("Items");
        for (int i = 0; played != null && i < played.length(); i++) {
            JSONObject o = played.getJSONObject(i);
            String sid = o.optString("SeriesId");
            if (!sid.isEmpty() && !seriesPlayed.containsKey(sid)) seriesPlayed.put(sid, playedMs(o.optJSONObject("UserData")));
        }
        for (int i = 0; i < next.length(); i++) {
            JSONObject o = next.getJSONObject(i);
            String sid = o.optString("SeriesId");
            if (sid.isEmpty() || best.containsKey(sid)) continue;   // 这部剧有看到一半的集，就放那一集
            Long t = seriesPlayed.get(sid);
            long own = playedMs(o.optJSONObject("UserData"));
            best.put(sid, new Object[]{toItem(o, false), Math.max(t == null ? 0L : t, own)});
        }
        List<Object[]> all = new ArrayList<>(best.values());
        java.util.Collections.sort(all, (x, y) -> Long.compare((long) y[1], (long) x[1]));
        List<Item> out = new ArrayList<>();
        for (Object[] e : all) {
            if (out.size() >= limit) break;
            out.add((Item) e[0]);
        }
        return out;
    }

    private static long playedMs(JSONObject userData) {
        if (userData == null) return 0;
        String d = userData.optString("LastPlayedDate", "");
        if (d.length() < 19) return 0;
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return f.parse(d.substring(0, 19)).getTime();
        } catch (java.text.ParseException e) {
            return 0;
        }
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

// ---------------------------------------------------------------- 播放器用（PlayerActivity）

    /** 条目详情 + 媒体源（含每条音视频 / 字幕流）。 */
    JSONObject item(String id) throws Exception {
        return getJson("/Users/" + userId + "/Items/" + id + "?Fields=MediaSources,MediaStreams,Path");
    }

    /** 原文件直出（电视硬解）。播放器直接拿这个 URL 去拉，鉴权走 api_key 参数。 */
    String streamUrl(String id, String mediaSourceId) {
        return server + "/Videos/" + id + "/stream?static=true&mediaSourceId=" + mediaSourceId + "&api_key=" + apiKey;
    }

    /** 放不了原文件时让服务器转成 H.264 + AAC 的 HLS（本机有显卡转码）。audioStreamIndex = MediaStreams 里的 Index，-1 用默认音轨。 */
    String transcodeUrl(String id, String mediaSourceId, int audioStreamIndex) {
        return server + "/Videos/" + id + "/master.m3u8?mediaSourceId=" + mediaSourceId + "&api_key=" + apiKey
                + (audioStreamIndex >= 0 ? "&AudioStreamIndex=" + audioStreamIndex : "")
                + "&VideoCodec=h264&AudioCodec=aac&TranscodingMaxAudioChannels=2&SegmentContainer=ts&DeviceId=tvhome&PlaySessionId="
                + Long.toHexString(System.nanoTime());
    }

    /**
     * 某条字幕流的文本，统一转成 SRT —— 外挂 srt/ass 和内嵌文本轨 Jellyfin 都能转，播放器只解析一种格式。
     * 图形字幕（PGS 等）转不了，调用方别拿它来调。
     */
    String subtitleSrt(String id, String mediaSourceId, int streamIndex) throws Exception {
        return get("/Videos/" + id + "/" + mediaSourceId + "/Subtitles/" + streamIndex + "/0/Stream.srt");
    }

    /**
     * 存观看进度 / 看完。2026-10-19 在 Jellyfin 10.11 上实测：用 API 密钥调 /PlayingItems 的开始/进度/停止上报
     * 都回 204 但不落库（API 密钥不绑会话），只有直接写 UserData 才生效，首页「最近播放」靠的就是它。
     */
    void saveProgress(String id, long positionTicks, boolean played) throws Exception {
        JSONObject body = new JSONObject()
                .put("PlaybackPositionTicks", played ? 0 : positionTicks)
                .put("Played", played)
                .put("LastPlayedDate", iso(System.currentTimeMillis()));
        post("/UserItems/" + id + "/UserData?userId=" + userId, body.toString());
    }

    /** 片头 / 片尾段（Jellyfin 10.10+，需片头检测插件；没有返回空数组）。 */
    JSONArray mediaSegments(String id) throws Exception {
        JSONArray a = getJson("/MediaSegments/" + id).optJSONArray("Items");
        return a != null ? a : new JSONArray();
    }

    /** 同一部剧里紧接着的下一集；没有返回 null。 */
    JSONObject nextEpisode(String seriesId, String currentId) throws Exception {
        JSONArray eps = getJson("/Shows/" + seriesId + "/Episodes?userId=" + userId + "&startItemId=" + currentId + "&Limit=2")
                .optJSONArray("Items");
        return eps != null && eps.length() > 1 ? eps.getJSONObject(1) : null;
    }

// ---------------------------------------------------------------- 剧集详情页用（SeriesActivity）

    /** 这部剧的各季（含特别篇「第 0 季」）。 */
    JSONArray seasons(String seriesId) throws Exception {
        JSONArray a = getJson("/Shows/" + seriesId + "/Seasons?userId=" + userId + "&Fields=UserData").optJSONArray("Items");
        return a != null ? a : new JSONArray();
    }

    /** 某一季的全部单集，带简介、进度、截图标记。 */
    JSONArray episodes(String seriesId, String seasonId) throws Exception {
        JSONArray a = getJson("/Shows/" + seriesId + "/Episodes?userId=" + userId + "&seasonId=" + seasonId
                + "&Fields=Overview,UserData").optJSONArray("Items");
        return a != null ? a : new JSONArray();
    }

    /** 这部剧该接着看哪一集（看到一半的那集，或者看完那集的下一集）；一集没看过返回 null。 */
    JSONObject nextUp(String seriesId) throws Exception {
        JSONArray a = getJson("/Shows/NextUp?userId=" + userId + "&seriesId=" + seriesId + "&Limit=1&enableResumable=true")
                .optJSONArray("Items");
        return a != null && a.length() > 0 ? a.getJSONObject(0) : null;
    }

    private static String iso(long ms) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date(ms));
    }

    private void post(String path, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(server + path).openConnection();
        c.setConnectTimeout(2500);
        c.setReadTimeout(8000);
        c.setRequestMethod("POST");
        c.setRequestProperty("X-Emby-Token", apiKey);
        c.setRequestProperty("Content-Type", "application/json");
        c.setDoOutput(true);
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(b.length);
        try (java.io.OutputStream out = c.getOutputStream()) {
            out.write(b);
        }
        int code = c.getResponseCode();
        c.disconnect();
        if (code >= 300) throw new java.io.IOException("HTTP " + code + " " + path);
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
        it.type = type;
        JSONObject tags = o.optJSONObject("ImageTags");
        if ("Episode".equals(type)) {
            it.seriesId = o.optString("SeriesId");
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
