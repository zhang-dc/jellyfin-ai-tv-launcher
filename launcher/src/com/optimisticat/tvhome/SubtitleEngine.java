package com.optimisticat.tvhome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 播放器自己的字幕：解析 SRT → cue 列表 → 按「播放位置 − 偏移」查当前句。
 *
 * 为什么不用 ExoPlayer 的字幕：它没有偏移 API，而字幕对不上（片源和字幕不是一个版本）正是最常见的问题；
 * 自己渲染才能边看边按遥控器调。Jellyfin 能把外挂 srt/ass 和内嵌文本轨都转成 SRT 下发，所以只解析这一种。
 *
 * 偏移约定：offsetMs > 0 = 字幕往后挪（字幕比声音早出来时往 + 调）。
 */
final class SubtitleEngine {

    static final class Cue {
        final long start, end;
        /** 主行（中文）和副行（英文）；单语字幕 secondary 为空。 */
        final String primary, secondary;

        Cue(long start, long end, String primary, String secondary) {
            this.start = start;
            this.end = end;
            this.primary = primary;
            this.secondary = secondary;
        }
    }

    private static final Pattern TIME = Pattern.compile(
            "(\\d+):(\\d{2}):(\\d{2})[,.](\\d{1,3})\\s*-->\\s*(\\d+):(\\d{2}):(\\d{2})[,.](\\d{1,3})");
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff\\u3040-\\u30ff]");

    private final List<Cue> cues = new ArrayList<>();
    private long[] starts = new long[0];

    int size() {
        return cues.size();
    }

    void load(String srt) {
        cues.clear();
        String[] blocks = srt.replace("\r", "").replace("\uFEFF", "").split("\n\\s*\n");
        for (String block : blocks) {
            String[] lines = block.trim().split("\n");
            int t = -1;
            for (int i = 0; i < Math.min(lines.length, 3); i++) if (TIME.matcher(lines[i]).find()) { t = i; break; }
            if (t < 0) continue;
            Matcher m = TIME.matcher(lines[t]);
            if (!m.find()) continue;
            long start = ms(m, 1), end = ms(m, 5);
            List<String> text = new ArrayList<>();
            for (int i = t + 1; i < lines.length; i++) {
                String clean = clean(lines[i]);
                if (!clean.isEmpty()) text.add(clean);
            }
            if (text.isEmpty() || end <= start) continue;
            cues.add(split(start, end, text));
        }
        Collections.sort(cues, (a, b) -> Long.compare(a.start, b.start));
        starts = new long[cues.size()];
        for (int i = 0; i < starts.length; i++) starts[i] = cues.get(i).start;
    }

    /**
     * 播放位置 positionMs 时屏幕上该有的字幕：**所有**正在生效的句子合成一条。
     *
     * 字幕「重叠」有三种来源，都在这里化解（2026-10-19 用户反馈有时会叠在一起）：
     *   1. 两人同时说话，两句时间段交叠 → 按出现先后上下排，不互相盖住
     *   2. ASS 特效字幕同一句分好几层（描边 / 阴影 / 位置层），转成 SRT 后变成好几条一模一样的 → 去重
     *   3. 中英双语拆成两条时间相同的字幕 → 中文归主行、英文归副行，和单条双语一样显示
     * 结果按内容缓存：同一组句子返回同一个对象，播放器据此判断要不要刷新文字。
     */
    Cue at(long positionMs, long offsetMs) {
        long t = positionMs - offsetMs;
        int lo = 0, hi = starts.length - 1, best = -1;
        while (lo <= hi) {   // 最后一个 start <= t 的
            int mid = (lo + hi) >>> 1;
            if (starts[mid] <= t) { best = mid; lo = mid + 1; } else hi = mid - 1;
        }
        List<Cue> active = new ArrayList<>();
        // 往前最多看 30 句：长句子（比如持续十几秒的地名标注）可能早就开始了
        for (int i = best; i >= 0 && i >= best - 30; i--) {
            Cue c = cues.get(i);
            if (t >= c.start && t < c.end) active.add(0, c);
        }
        if (active.isEmpty()) return null;
        if (active.size() == 1) return active.get(0);
        java.util.LinkedHashSet<String> primary = new java.util.LinkedHashSet<>(), secondary = new java.util.LinkedHashSet<>();
        long start = Long.MAX_VALUE, end = 0;
        for (Cue c : active) {
            for (String l : c.primary.split("\n")) if (!l.isEmpty()) primary.add(l);
            for (String l : c.secondary.split("\n")) if (!l.isEmpty()) secondary.add(l);
            start = Math.min(start, c.start);
            end = Math.max(end, c.end);
        }
        String key = String.join("\n", primary) + "|" + String.join("\n", secondary);
        if (merged != null && key.equals(mergedKey)) return merged;
        mergedKey = key;
        merged = new Cue(start, end, String.join("\n", primary), String.join("\n", secondary));
        return merged;
    }

    private Cue merged;
    private String mergedKey;

    /** 旧的单句查找（只取最近一句），留着对照。 */
    Cue atSingle(long positionMs, long offsetMs) {
        long t = positionMs - offsetMs;
        int lo = 0, hi = starts.length - 1, best = -1;
        while (lo <= hi) {   // 最后一个 start <= t 的
            int mid = (lo + hi) >>> 1;
            if (starts[mid] <= t) { best = mid; lo = mid + 1; } else hi = mid - 1;
        }
        // 往前多看两句：字幕之间偶尔有重叠（上一句还没结束下一句就开始了）
        for (int i = best; i >= 0 && i >= best - 2; i--) {
            Cue c = cues.get(i);
            if (t >= c.start && t < c.end) return c;
        }
        return null;
    }

    private static long ms(Matcher m, int g) {
        String frac = (m.group(g + 3) + "00").substring(0, 3);
        return ((Long.parseLong(m.group(g)) * 60 + Long.parseLong(m.group(g + 1))) * 60 + Long.parseLong(m.group(g + 2))) * 1000
                + Long.parseLong(frac);
    }

    /** 去掉 HTML 标签（<i> <font>）和 ASS 特效（{\an8}{\fs20} 这类），合并多余空白。 */
    private static String clean(String s) {
        return s.replaceAll("\\{[^}]*\\}", "").replaceAll("<[^>]+>", "").replace("\\N", " ").replace("\\n", " ")
                .replaceAll("\\s+", " ").trim();
    }

    /** 中英双语字幕：含中日文的行算主行，纯拉丁字母的行算副行；都是中文就合成一行主行。 */
    private static Cue split(long start, long end, List<String> lines) {
        StringBuilder primary = new StringBuilder(), secondary = new StringBuilder();
        for (String l : lines) {
            StringBuilder target = CJK.matcher(l).find() ? primary : secondary;
            if (target.length() > 0) target.append('\n');
            target.append(l);
        }
        if (primary.length() == 0) return new Cue(start, end, secondary.toString(), "");   // 纯外文字幕
        return new Cue(start, end, primary.toString(), secondary.toString());
    }
}
