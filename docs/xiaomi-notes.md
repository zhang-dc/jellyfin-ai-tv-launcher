# 国内版小米电视：坑和绕法

在**小米电视 6 OLED**（型号 MiTV-MTEQ0，Android 10，32 位 armeabi-v7a，系统是 PatchWall）上实测。其他国内版小米电视大概率类似，但包名、日志格式可能不同。

## 1. 不能把第三方应用设成默认桌面

现象：

- `cmd package set-home-activity <你的桌面>` 返回 `Success`，但按主页键还是回原厂桌面（偏好根本没写进去）
- 停用原厂桌面 `com.mitv.tvhome` 之后，系统**宁可停在 `FallbackHome` 空白页**，也不选你的桌面（日志反复报 `User unlocked but no home`），哪怕你的优先级更高。看起来小米只认系统应用当主页
- 系统设置里没有「默认桌面」选项，还内置了一份黑名单（`settings get system pi_config`），拦截当贝桌面这类常见第三方桌面的安装

**所以不要停用原厂桌面**，不然电视会停在空白页上。万一停了，用 `adb shell pm enable com.mitv.tvhome` 恢复。

绕法：**无障碍服务**（`HomeKeyService.java`）

1. `onKeyEvent` 截下遥控器主页键（按下、抬起都吃掉），直接拉起自己的桌面
2. 开机、或者从应用退出落回原厂桌面时，切回自己的桌面
3. 用户从「小米影视」入口主动打开原厂桌面时放行

第 2 步有个坑：**原厂桌面的窗口对无障碍服务是隐藏的**（`getWindows()` 里没有它，`getRootInActiveWindow()` 返回 null），从应用退回原厂桌面时也不发 `TYPE_WINDOW_STATE_CHANGED`。所以改用系统的**应用使用记录**（`UsageStatsManager.queryEvents` 里最后一条 `ACTIVITY_RESUMED`）来判断前台是谁，需要 `appops set <包名> GET_USAGE_STATS allow`。

无障碍服务是被系统绑定的，所以在后台拉起 Activity 不受 Android 10 的限制。

> 注意：用 `adb shell input keyevent KEYCODE_HOME` 模拟的主页键**不会**经过无障碍服务的按键过滤，测试要用真遥控器。

## 2. 去广告：哪些包可以停

`disable-list.txt` 里分了组。实测停用后不影响正常使用的：

- **广告、统计、推送、云控**（建议都停）：`com.miui.analytics`、`com.miui.tv.analytics`、`com.xiaomi.statistic`、`com.mitv.cloudcontrol`（下发广告和推荐配置）、`com.xiaomi.mitv.tvpush.tvpushservice`（弹窗推广）、`com.duokan.videodaily`
- **按需**：游戏中心、商城、视频通话、音乐、K 歌、天气、日历、相册
- **别停**：原厂桌面（见上）、投屏相关（`miplay`、`airkan`、`upnp`、`smartshare`）、`tvplayer`（HDMI 信号源）、输入法

只用 `pm disable-user --user 0`（停用，不卸载），`node tv.mjs restore` 能全部恢复。另外，原厂桌面在停用后重新启用、重启时，会弹一次 15 秒的视频广告。

## 3. 内置麦克风：只有 48kHz、只有两种来源

用 `Recorder.probe()` 把每种录音来源和采样率都试了一遍（对着电视说话时录 2 秒，看音量）：

| 来源 | 16kHz | 48kHz |
|---|---|---|
| `VOICE_RECOGNITION` | 2 秒只读到 1 帧（卡住） | **有声音** |
| `VOICE_COMMUNICATION` | 2 秒只读到 1 帧（卡住） | **有声音，音量更大，带回声消除** |
| `MIC` / `DEFAULT` / `CAMCORDER` | 全 0 | 全 0 |

所以按 48kHz 用 `VOICE_COMMUNICATION` 录，再每 3 个样本取平均降到 16kHz 给语音识别。说话时帧音量的均值大约 200 到 300，峰值上千，安静时几十。

调试入口：`adb shell am start -n com.optimisticat.tvhome/.AgentActivity --ez probe true`，对着电视说话，然后 `adb logcat -s TvMicProbe`。

## 4. 遥控器语音键：拦不到，借小爱听写

- 小米遥控器的语音键**不发安卓按键事件**，无障碍服务的按键过滤一条都收不到。声音走的是小爱私有的遥控器麦克风通道（日志里是 `audioSource=RC`、`NEAR_FIELD`），第三方应用拿不到
- 小爱的查询历史（`content://com.xiaomi.voicecontrol.provider.queryhistory/...`）是空的
- 但小爱每次识别完，会在系统日志里打这样一行：

```
onConversationFinished Conversation(type=QUERY, engineType=NEAR_FIELD, queryType=ASR, query=最近有没有什么新的电影, requestId='...', ...)
```

所以 `XiaoaiBridge.java` 在「问问」「找剧」页打开时，用 `logcat -v raw -T 1 -e onConversationFinished` 只读这一行，取出 `query=` 后面的文字交给当前页面，再用无障碍服务按一次返回关掉小爱的弹窗。需要 `READ_LOGS` 权限（adb 授予后要重启进程）。

**小爱升级改了日志格式，这条路就会失效**，到时看日志重新对正则。其他页面上按语音键，还是原来的小爱。

## 5. 别的电视观看历史拿不到

腾讯视频（云视听极光）、酷喵、B 站这些，确实会把观看历史报给电视厂商的桌面（广播 `com.tv.history.add`），原厂首页「全部历史」就是这么来的。但是：

- 发不发、发给谁，由各平台**服务端下发的配置**决定，不看本机型号（模拟器改成小米型号也不发）
- 广播是 `setPackage` 定向发给原厂桌面的，原厂桌面又是系统应用，不能被同名替换
- 字段内容按平台和厂商约定的密钥加密，密钥也在服务端下发的配置里

所以第三方桌面接不进这个通道。首页「最近播放」只放 Jellyfin 的。

## 6. 其他小坑

- 电视的 IP 是路由器分配的，**会变**。连不上先扫一下局域网；端口探测「看起来是通的」不能作数（有代理软件开了 TUN 模式时，会替不存在的地址接住 TCP 连接）。真正的 adbd 收到 `CNXN` 握手会回 `AUTH`
- 用 `adb exec-out screencap` 截图，厂商驱动会往 PNG 开头混入日志，导致图片坏掉。改成先存到 `/sdcard` 再 `adb pull`（`tv.mjs shot` 就是这么做的）
- Git Bash 里执行 `adb shell` 时，要设 `MSYS_NO_PATHCONV=1`，不然 `/data` 这类路径会被改写成 Windows 路径
- 第三方桌面本身也会被安卓 8.0+ 加上默认的灰色焦点高亮，要对每个可聚焦控件调用 `setDefaultFocusHighlightEnabled(false)`，不然圆形按钮背后会多出一块方底
