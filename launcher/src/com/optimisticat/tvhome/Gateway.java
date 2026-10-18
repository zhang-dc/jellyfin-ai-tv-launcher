package com.optimisticat.tvhome;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * 连你自己的后端网关（接口约定见 docs/protocol.md）：Agent 对话、语音识别、找剧 / 下载。
 *
 * 约定网关和 Jellyfin 跑在同一台电脑上，所以主机名直接沿用 Jellyfin 当前用的地址（它会自动发现 IP 变化），
 * 端口和令牌在 assets/gateway.properties（含密钥，不入库）。所有方法阻塞，只在后台线程调。
 */
final class Gateway {

    private final Jellyfin jellyfin;
    private final String token;
    private final int port;

    Gateway(Context ctx, Jellyfin jellyfin) throws Exception {
        this.jellyfin = jellyfin;
        Properties p = new Properties();
        try (InputStream in = ctx.getAssets().open("gateway.properties")) {
            p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        }
        token = p.getProperty("token");
        port = Integer.parseInt(p.getProperty("port", "18796"));
    }

    /** 发一条消息给 Agent；回复从 events() 取。 */
    void chat(String text) throws Exception {
        JSONObject body = new JSONObject().put("text", text);
        request("POST", "/tv/chat", "application/json", body.toString().getBytes(StandardCharsets.UTF_8), 15_000);
    }

    /** 「新对话」：服务端清历史并开新会话。 */
    void reset() throws Exception {
        request("POST", "/tv/reset", "application/json", "{}".getBytes(StandardCharsets.UTF_8), 15_000);
    }

    /** 长轮询：since 之后的新消息，或「正在思考」状态和 typingSeen 不同才返回（服务端最多挂 25 秒）。 */
    JSONObject events(long since, boolean typingSeen) throws Exception {
        return new JSONObject(new String(request("GET",
                "/tv/events?since=" + since + "&typing=" + (typingSeen ? 1 : 0), null, null, 35_000), StandardCharsets.UTF_8));
    }

    /** 16kHz 单声道 WAV → 文字（后端转给语音识别，比如本机 whisper.cpp）。 */
    String asr(byte[] wav) throws Exception {
        JSONObject r = new JSONObject(new String(request("POST", "/tv/asr", "audio/wav", wav, 30_000), StandardCharsets.UTF_8));
        return r.optString("text").trim();
    }

    JSONObject hubGet(String api, String query) throws Exception {
        String path = "/tv/hub/" + api + (query == null ? "" : "?" + query);
        return new JSONObject(new String(request("GET", path, null, null, 60_000), StandardCharsets.UTF_8));
    }

    JSONObject hubPost(String api, JSONObject body) throws Exception {
        return new JSONObject(new String(request("POST", "/tv/hub/" + api, "application/json",
                body.toString().getBytes(StandardCharsets.UTF_8), 30_000), StandardCharsets.UTF_8));
    }

    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    private byte[] request(String method, String path, String type, byte[] body, int readTimeout) throws Exception {
        String host = new URL(jellyfin.serverUrl()).getHost();
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + ":" + port + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(3000);
        c.setReadTimeout(readTimeout);
        c.setRequestProperty("X-TV-Token", token);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", type);
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = c.getOutputStream()) {
                out.write(body);
            }
        }
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) bo.write(buf, 0, n);
            in.close();
        }
        c.disconnect();
        if (code >= 400) throw new java.io.IOException("HTTP " + code + " " + bo.toString("UTF-8"));
        return bo.toByteArray();
    }
}
