# 后端接口约定

电视端（`launcher/.../Gateway.java`）只通过下面这几个 HTTP 接口和你的后端说话。你可以用任何语言、在任何服务里实现它们。**把这份文档交给你的 AI 编程助手，让它在你的服务上实现即可。**

## 基本约定

- **地址**：`http://<Jellyfin 所在主机>:<port>`。电视端不单独配后端地址，直接沿用 Jellyfin 当前用的主机名（Jellyfin 支持局域网自动发现，IP 变了也能跟上），所以**后端要和 Jellyfin 跑在同一台机器上**。端口写在 `assets/gateway.properties` 的 `port`。
- **鉴权**：每个请求都带请求头 `X-TV-Token: <token>`，值等于 `assets/gateway.properties` 的 `token`。不对就返回 `401`。
- **只开放给局域网**：建议监听 `0.0.0.0`，再用防火墙限制只允许本地子网访问。不要暴露到公网，「问问」背后的 Agent 通常能在你的机器上执行操作。
- **编码**：JSON 一律 UTF-8，`Content-Type: application/json; charset=utf-8`。
- **出错**：返回 4xx/5xx，响应体 `{"error": "人能看懂的原因"}`，电视会把 `error` 显示出来。

`assets/gateway.properties` 示例：

```properties
port=18796
token=换成一串足够长的随机字符
```

---

## 一、对话（「问问」页）

对话是**异步**的：电视发消息后立刻返回，回复通过长轮询取。这样 Agent 可以慢慢干活（搜索、下载、跑脚本），中间还能推「正在思考」状态。

### `POST /tv/chat`

```json
{ "text": "帮我下载摩登家庭第六季" }
```

→ `200 {"seq": 12}`。把这条消息记进对话历史（role=`user`），交给你的 Agent 处理。

**同一个电视的消息应该串行处理**：Agent 还在忙时又来一条，就排队，或者合并到下一轮。

### `GET /tv/events?since=<seq>&typing=<0|1>`

长轮询。满足下面任一条件就立刻返回，否则挂起，最多等 **25 秒**再返回（电视端读超时设为 35 秒）：

- 历史里有 `seq > since` 的消息
- 当前「是否在思考」和请求里的 `typing` 不一致（`typing=1` 表示电视以为正在思考）

```json
{
  "messages": [
    { "seq": 12, "role": "user",      "text": "帮我下载摩登家庭第六季", "atMs": 1792340794566 },
    { "seq": 13, "role": "assistant", "text": "找到 3 个资源，推荐 1080p 那个，要下吗？", "atMs": 1792340804680 }
  ],
  "typing": false,
  "last": 13
}
```

- `seq` 全局递增，电视用最后一条的 `seq` 作为下次的 `since`
- `since=0` 返回全部历史，电视刚打开页面时就这么调。建议只保留最近几十条
- `typing`：Agent 正在处理时为 `true`，电视会显示三个跳动的点
- `text` 用**纯文本**，不要 Markdown。电视上是大字气泡，建议在 Agent 的人设里写明「回复简短、口语化、先给结论」

### `POST /tv/reset`

「新对话」按钮。清空历史，让 Agent 开一个新会话。→ `200 {"ok": true}`。

可以再推一条 `assistant` 消息作为确认（比如「好的，开始新对话」）。

---

## 二、语音识别

### `POST /tv/asr`

- 请求体：**WAV**（RIFF，PCM 16bit，单声道，16000Hz），`Content-Type: audio/wav`，一般几秒钟、几十到几百 KB
- 响应：`200 {"text": "帮我下载摩登家庭第五季，要带中文字幕的"}`，没识别出内容就返回空字符串

电视端已经把录音处理成 16kHz，后端直接转给识别引擎即可。参考实现见 [voice.md](voice.md)（本机 whisper.cpp，有显卡时识别一句话不到 0.5 秒）。

识别结果建议把换行换成逗号，电视上显示为一句话。

---

## 三、找剧 / 下载

路径前缀是 `/tv/hub/`。资源从哪里搜、用什么下载，都由你的后端决定，电视只负责展示和传话。

### `GET /tv/hub/search?q=<关键词>`

```json
{
  "results": [
    {
      "title": "Show.Name.S01.1080p.BluRay.x265-Group",
      "size": 5368709120,
      "seeders": 582,
      "indexer": "某站",
      "kind": "tv",
      "grab": "<下载时原样传回的标识，比如下载链接>"
    }
  ]
}
```

- **请按推荐程度排好序**，电视按顺序显示前 60 条
- `seeders` 没有就给 `null`，`kind` 填 `"movie"` 或 `"tv"`
- 电视会从 `title` 里自动识别清晰度（4K/1080P/720P）、片源（蓝光/WEB-DL…）和「中字」做成标签，不用你额外给
- 一次搜索十几秒都没问题，电视会显示加载中

### `POST /tv/hub/grab`

```json
{ "grab": "<search 结果里的 grab>", "folder": "用户的搜索词", "kind": "tv" }
```

→ `200 {"ok": true, "folder": "实际用的文件夹名"}`。`folder` 是电视建议的存放文件夹（默认就是搜索词），你可以清洗后再用。

### `GET /tv/hub/downloads`

首页「下载中」和「下载进度」页都靠它，每 3 到 5 秒请求一次，要轻量。

```json
{
  "downloads": [
    {
      "hash": "唯一标识",
      "name": "任务名",
      "kind": "剧集",
      "progress": 42,
      "state": "downloading",
      "dlspeed": 5242880,
      "eta": 1800,
      "size": 5368709120
    }
  ]
}
```

- `progress` 是 0 到 100 的整数，`dlspeed` 单位字节/秒，`eta` 单位秒（不知道就填很大的数）
- `state`：以 `paused` 或 `stopped` 开头算暂停，`error` 算出错，其他且 `progress<100` 都算正在下载（字段语义照搬 qBittorrent）
- `kind` 是显示用的文字，比如「剧集」「电影」

### `POST /tv/hub/task`

「下载进度」页上的操作：

```json
{ "hash": "...", "action": "stop" }                        // 暂停
{ "hash": "...", "action": "start" }                       // 继续
{ "hash": "...", "action": "delete", "deleteFiles": false } // 删任务，保留文件
{ "hash": "...", "action": "delete", "deleteFiles": true }  // 连文件一起删
```

→ `200 {"ok": true}`

---

## 最小实现清单

1. 一个 HTTP 服务，校验 `X-TV-Token`
2. `/tv/chat` + `/tv/events` + `/tv/reset`：内存里存一个消息列表，再加一个「有新消息就唤醒长轮询」的机制，把 `user` 消息交给你的 Agent，回复追加成 `assistant`
3. `/tv/asr`：转发给语音识别
4. `/tv/hub/*`：对接你的资源搜索和下载器（只想要 AI 对话的话可以不做，「找剧」页会报错，其他功能不受影响）
