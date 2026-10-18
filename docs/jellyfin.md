# Jellyfin 配置

首页的「最近播放」「最近下载」两排，读的是你家 Jellyfin 的数据。桌面**不预装、不内置**任何服务器信息，按下面几步自己配。

## 需要什么

- 一台跑着 Jellyfin 的电脑（10.8+，和电视在同一个局域网）
- 电视上装好 Jellyfin 官方 Android TV 客户端（`org.jellyfin.androidtv`），并且已经登录。点首页卡片时，桌面会让这个客户端直接打开那一集

## 1. 建一个 API 密钥

Jellyfin 网页端 → 控制台 → **API 密钥** → 新建，应用名随便填（比如 `tv-launcher`）。复制生成的密钥。

> 用 API 密钥而不是账号密码：密钥可以随时单独吊销，不影响你的账号。

## 2. 查用户 ID

「最近播放」是按**用户**算的，要填电视上 Jellyfin 客户端登录的那个账号。

控制台 → **用户** → 点进该用户，浏览器地址栏里 `userId=` 后面那串就是。也可以用密钥查：

```bash
curl -H "X-Emby-Token: <API密钥>" http://<Jellyfin主机>:8096/Users
```

返回列表里对应用户的 `Id`。

## 3. 填配置文件

```bash
cd launcher
cp assets/jellyfin.properties.example assets/jellyfin.properties
```

```properties
server=http://<Jellyfin主机IP>:8096
apiKey=<第 1 步的密钥>
userId=<第 2 步的用户 ID>
```

这个文件含密钥，已在 `.gitignore` 里，**不要提交**。改完重新 `node build.mjs` 打包安装。

## 4. 服务器 IP 变了怎么办

不用管。连不上 `server` 时，桌面会用 Jellyfin 自带的局域网发现（UDP 7359 广播 `Who is JellyfinServer?`）重新找服务器，找到的新地址会记下来。前提是 Jellyfin 的「控制台 → 网络 → 启用自动发现」是打开的（默认就是开的）。

## 桌面用到的 Jellyfin 接口（供参考）

| 用途 | 接口 |
|---|---|
| 看到一半的 | `GET /Users/{userId}/Items/Resume?MediaTypes=Video` |
| 各剧的下一集 | `GET /Shows/NextUp?userId={userId}` |
| 最近入库 | `GET /Users/{userId}/Items/Latest?IncludeItemTypes=Episode,Movie&GroupItems=true` |
| 图片 | `GET /Items/{id}/Images/{Primary|Thumb|Backdrop}?maxWidth=480` |

点卡片打开那一集：启动 `org.jellyfin.androidtv/.ui.startup.StartupActivity`，带 extra `ItemId=<条目ID>`。这是 Jellyfin 客户端自己首页频道用的入口，会直接进到详情页。
