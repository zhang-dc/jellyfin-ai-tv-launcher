package com.optimisticat.tvhome;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 录一句话：电视内置麦克风（小爱远场唤醒用的那组），交给 whisper 的是 16kHz 单声道 16bit。
 *
 * 2026-10-19 在小米电视 6 OLED 上用 probe() 实测：只有 VOICE_COMMUNICATION / VOICE_RECOGNITION 两种来源有声音，
 * 而且**只能 48kHz**——开 16kHz 时 read() 两秒只回 1 帧（驱动不支持，卡在重采样），录音就一直停在「正在听」。
 * MIC / DEFAULT / CAMCORDER 全是 0。所以按 48kHz 录、每 3 个样本取平均降到 16kHz。
 * VOICE_COMMUNICATION 带回声消除（电视自己放的声音会被减掉）、音量也更大（实测说话时均值 310 vs 198），优先用它。
 *
 * 结束条件（简单能量端点检测，不依赖任何库）：
 *   说过话之后连续 1.2 秒安静 → 结束；一直没开口 6 秒 → 放弃；最长 20 秒。
 * 阈值按开头 300ms 的底噪自适应，客厅开着电视声音也能用（电视自己的声音会被当成底噪的一部分）。
 */
final class Recorder {

    interface Listener {
        /** 0~1 的音量，用来画录音动画。主线程以外调用。 */
        void onLevel(float level);
    }

    static final int RATE = 16000;
    private static final int CAPTURE_RATE = 48000;
    private static final int DECIMATE = CAPTURE_RATE / RATE;
    private static final int FRAME = RATE / 50;            // 20ms 一帧（降采样后）
    private static final int[] SOURCES = {MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.VOICE_RECOGNITION};
    private static final int SILENCE_END_MS = 1200;
    private static final int NO_SPEECH_GIVE_UP_MS = 6000;
    private static final int MAX_MS = 20000;
    private static final int CALIBRATE_MS = 300;

    private volatile boolean stopped;
    /**
     * 按住说话模式（默认）：不判断说没说完，stop() 才结束（遥控器松开确认键）。
     * 2026-10-19 用户实测「说完自动结束」在客厅底噪下结束不了，改成对讲机式。
     */
    private final boolean holdToTalk;

    Recorder() {
        this(true);
    }

    Recorder(boolean holdToTalk) {
        this.holdToTalk = holdToTalk;
    }

    /** 外部（再按一次麦克风 / 返回键）提前结束。 */
    void stop() {
        stopped = true;
    }

    /** 阻塞录音，返回 WAV；一直没说话返回 null。 */
    byte[] record(Listener listener) {
        int min = AudioRecord.getMinBufferSize(CAPTURE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord rec = null;
        for (int src : SOURCES) {
            rec = new AudioRecord(src, CAPTURE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min, FRAME * DECIMATE * 2 * 8));
            if (rec.getState() == AudioRecord.STATE_INITIALIZED) break;
            rec.release();
            rec = null;
        }
        if (rec == null) throw new IllegalStateException("麦克风打不开（没给录音权限，或被小爱占着）");
        short[] raw = new short[FRAME * DECIMATE];
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        short[] frame = new short[FRAME];
        double noise = 0;
        int noiseFrames = 0;
        boolean spoke = false;
        int quietMs = 0, totalMs = 0;
        rec.startRecording();
        try {
            while (!stopped && totalMs < MAX_MS) {
                int got = rec.read(raw, 0, raw.length);
                if (got <= 0) continue;
                int n = got / DECIMATE;
                for (int i = 0; i < n; i++) {   // 48k → 16k：三点平均，顺带当了个低通
                    int k = i * DECIMATE;
                    frame[i] = (short) ((raw[k] + raw[k + 1] + raw[k + 2]) / 3);
                }
                totalMs += n * 1000 / RATE;
                double rms = rms(frame, n);
                if (listener != null) listener.onLevel((float) Math.min(1, rms / 1500));

                if (holdToTalk) {
                    if (rms > 120) spoke = true;
                } else if (totalMs <= CALIBRATE_MS) {
                    noise += rms;
                    noiseFrames++;
                } else {
                    double floor = noiseFrames == 0 ? 200 : noise / noiseFrames;
                    // 实测说话时帧音量均值 200~300、峰值上千，安静时几十；门槛取底噪 3 倍、至少 120
                    boolean voiced = rms > Math.max(floor * 3, 120);
                    if (voiced) {
                        spoke = true;
                        quietMs = 0;
                    } else if (spoke) {
                        quietMs += n * 1000 / RATE;
                        if (quietMs >= SILENCE_END_MS) break;
                    } else if (totalMs >= NO_SPEECH_GIVE_UP_MS) {
                        break;
                    }
                }
                ByteBuffer bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < n; i++) bb.putShort(frame[i]);
                pcm.write(bb.array(), 0, n * 2);
            }
        } finally {
            rec.stop();
            rec.release();
        }
        // 按一下就松开 = 误触；按住了但全程没到说话音量 = 没说话。两种都不送识别：
        // whisper 拿到静音会编出「优优独播剧场——YoYo Television Series Exclusive」这类训练数据里的片头字幕（2026-10-20 实际发生）
        if (holdToTalk ? (totalMs < 400 || !spoke) : (!spoke && !stopped)) return null;
        return wav(pcm.toByteArray());
    }

    /**
     * 麦克风探测（调试用）：每种录音来源各录 2 秒，日志里打平均 / 最大音量，看哪种能收到声音。
     * adb: am start -n com.optimisticat.tvhome/.AgentActivity --ez probe true，然后对着电视说话，看 logcat -s TvMicProbe。
     */
    static void probe() {
        int[] sources = {MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.DEFAULT, MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.CAMCORDER, MediaRecorder.AudioSource.UNPROCESSED, 1999 /* 小米私有？ */};
        for (int src : sources) for (int rate : new int[]{16000, 48000}) {
            try {
                int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                AudioRecord rec = new AudioRecord(src, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 8192));
                if (rec.getState() != AudioRecord.STATE_INITIALIZED) { android.util.Log.i("TvMicProbe", "src=" + src + " rate=" + rate + " init failed"); rec.release(); continue; }
                short[] buf = new short[rate / 50];
                double sum = 0, max = 0; int frames = 0, zeros = 0;
                rec.startRecording();
                long end = System.currentTimeMillis() + 2000;
                while (System.currentTimeMillis() < end) {
                    int n = rec.read(buf, 0, buf.length);
                    if (n <= 0) continue;
                    double r = rms(buf, n);
                    sum += r; max = Math.max(max, r); frames++;
                    if (r == 0) zeros++;
                }
                rec.stop(); rec.release();
                android.util.Log.i("TvMicProbe", "src=" + src + " rate=" + rate + " frames=" + frames + " avg=" + Math.round(frames == 0 ? 0 : sum / frames) + " max=" + Math.round(max) + " zeroFrames=" + zeros);
            } catch (Exception e) {
                android.util.Log.i("TvMicProbe", "src=" + src + " rate=" + rate + " error " + e);
            }
        }
        android.util.Log.i("TvMicProbe", "done");
    }

    private static double rms(short[] s, int n) {
        double sum = 0;
        for (int i = 0; i < n; i++) sum += s[i] * (double) s[i];
        return Math.sqrt(sum / n);
    }

    private static byte[] wav(byte[] pcm) {
        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVE".getBytes());
        h.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16);
        h.put("data".getBytes()).putInt(pcm.length);
        byte[] out = new byte[44 + pcm.length];
        System.arraycopy(h.array(), 0, out, 0, 44);
        System.arraycopy(pcm, 0, out, 44, pcm.length);
        return out;
    }
}
