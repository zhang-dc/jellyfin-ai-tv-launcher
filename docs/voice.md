# 语音助手配置

「问问」和「找剧」页支持两种语音输入，都由**你的后端**把声音转成文字（见 [protocol.md](protocol.md) 的 `/tv/asr`）。桌面不预装任何识别模型。

## 两种说话方式

| 方式 | 怎么用 | 声音从哪来 | 谁来识别 |
|---|---|---|---|
| 按住说话 | 选中麦克风按钮，**按住确认键**说，**松手**发送 | 电视内置麦克风 | 你的后端（`/tv/asr`） |
| 遥控器语音键 | 在「问问」或「找剧」页按遥控器语音键说话 | 遥控器麦克风 | 小爱同学听写，桌面截取结果 |

为什么做成「按住说话」，而不是「说完自动结束」：客厅里电视一直在出声，背景噪音很大，靠「安静了就算说完」来判断，很难判对。按住、松手最可靠。

## 推荐方案：本机 whisper.cpp

有 NVIDIA 显卡的话，在跑 Jellyfin 和后端的那台电脑上部署 [whisper.cpp](https://github.com/ggml-org/whisper.cpp)：中文识别准、免费，录音也不出家门。

1. 从 whisper.cpp 的 Releases 下载对应平台的 CUDA 版（Windows 选 `whisper-bin-win-cuda-*-x64.zip`）
2. 下载模型，推荐 `ggml-large-v3-turbo.bin`（约 1.6GB，中文效果和速度比较均衡）：
   `https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo.bin`
3. 启动服务，只监听本机，由后端转调：

```bash
whisper-server -m ggml-large-v3-turbo.bin --host 127.0.0.1 --port 18797 -l zh -t 4
```

4. 后端的 `/tv/asr` 收到 WAV 后，用 multipart 转给 `http://127.0.0.1:18797/inference`，字段 `file`（音频）、`language=zh`、`response_format=json`、`temperature=0`，取返回的 `text`

参考数据（RTX 50 系显卡）：5 秒的话 0.1 到 0.4 秒识别完，占显存约 2GB。**新显卡第一次识别可能要几十秒**，是 CUDA 在现场编译计算核心，之后会缓存。

建议把它设成开机自启的服务（Windows 用任务计划程序，Linux 用 systemd），挂了自动拉起。

没有显卡也能跑，CPU 上可以换小一点的模型（`ggml-small.bin`），慢一些。也可以换成任何云端语音识别，只要 `/tv/asr` 的输入输出不变就行。

## 电视端要的权限

`node tv.mjs enable-home-service` 会一并授予：

- `RECORD_AUDIO`：按住说话要用内置麦克风
- `READ_LOGS`：遥控器语音键借小爱听写，要读系统日志里小爱的识别结果

授完权限要**重启一次桌面进程**才生效（`READ_LOGS` 对应的系统用户组只在进程启动时分配）：`adb shell am force-stop com.optimisticat.tvhome`，然后再跑一次 `enable-home-service`。

## 小米电视上的两个关键坑

详见 [xiaomi-notes.md](xiaomi-notes.md)：

- 内置麦克风**只有 48kHz** 能录到声音（16kHz 会卡住不出数据），录音来源也只有 `VOICE_COMMUNICATION` 和 `VOICE_RECOGNITION` 两种可用。桌面已经按 48kHz 录、再自己降到 16kHz
- 遥控器语音键**不走安卓的按键通道**，第三方应用拦不到，所以改成借小爱听写、从日志里截取识别结果
