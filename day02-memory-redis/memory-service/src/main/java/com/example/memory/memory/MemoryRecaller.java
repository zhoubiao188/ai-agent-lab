package com.example.memory.memory;

import com.example.memory.domain.Msg;
import com.example.memory.memory.TurnSegmenter.Segment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 归档召回 —— 让"被压缩掉的历史"还能被找回来。
 *
 * <p>摘要是有损的。用户在第 5 轮说"我上周买的那个键盘按键失灵了",到第 40 轮突然问
 * "那个键盘的订单号是多少" —— 摘要里可能只剩"用户在咨询键盘售后",订单号早被压没了。
 * 这时唯一能救场的就是<b>原始归档</b>。</p>
 *
 * <p>为什么不直接上向量检索:归档量在单会话维度很小(上限 400 条消息、几十轮),
 * 每次召回只扫几十个片段,BM25 级别的关键词打分是<b>微秒级</b>的,还省掉一个向量库和
 * 一次 embedding 调用(那才是真正拖慢首字延迟的东西)。等归档跨会话、跨用户检索时,
 * 再换向量检索也不迟 —— 接口不变。</p>
 *
 * <p>打分 = 词项 IDF 加权命中 / 文档长度归一 + 时间衰减。时间衰减很重要:
 * 同样相关的两段历史,越近的越可能是当前话题的上下文。</p>
 */
@Component
public class MemoryRecaller {

    private static final int SNIPPET_MAX_CHARS = 260;
    private static final double RECENCY_WEIGHT = 0.15;

    public record Hit(long startSeq, long endSeq, String text, double score) {
    }

    public List<Hit> recall(String query, List<Msg> archive, int topK, double minScore) {
        if (query == null || query.isBlank() || archive == null || archive.isEmpty() || topK <= 0) {
            return List.of();
        }
        List<String> queryTerms = terms(query);
        if (queryTerms.isEmpty()) {
            return List.of();
        }
        List<Segment> segments = TurnSegmenter.segment(archive);
        if (segments.isEmpty()) {
            return List.of();
        }

        List<Set<String>> docTerms = new ArrayList<>(segments.size());
        Map<String, Integer> df = new HashMap<>();
        for (Segment s : segments) {
            Set<String> ts = new HashSet<>(terms(s.digest(SNIPPET_MAX_CHARS)));
            docTerms.add(ts);
            for (String t : ts) {
                df.merge(t, 1, Integer::sum);
            }
        }

        int n = segments.size();
        List<Hit> hits = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Set<String> ts = docTerms.get(i);
            double score = 0;
            for (String q : queryTerms) {
                if (!ts.contains(q)) {
                    continue;
                }
                int docFreq = df.getOrDefault(q, 0);
                double idf = Math.log(1.0 + (double) n / (1 + docFreq));
                score += idf;
            }
            if (score <= 0) {
                continue;
            }
            score = score / Math.sqrt(Math.max(1, ts.size()));
            // 越靠后的片段(seq 越大)越新
            score += RECENCY_WEIGHT * ((double) (i + 1) / n);
            if (score < minScore) {
                continue;
            }
            Segment s = segments.get(i);
            hits.add(new Hit(s.startSeq(), s.endSeq(), s.digest(SNIPPET_MAX_CHARS).trim(), round(score)));
        }

        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        if (hits.size() > topK) {
            hits = new ArrayList<>(hits.subList(0, topK));
        }
        return hits;
    }

    /**
     * 轻量分词:英文/数字按词切,中文按<b>二元组</b>切。
     * 不引入词典分词的取舍是——中文二元组召回率略高、精确率略低,但对"找回相关片段"这个任务,
     * 召回率更重要(多给一段无关历史,模型自己会忽略;少给一段关键历史,就真丢了)。
     */
    public static List<String> terms(String text) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (text == null) {
            return List.of();
        }
        StringBuilder latin = new StringBuilder();
        List<Integer> cjkRun = new ArrayList<>();

        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            boolean cjk = isCjk(cp);
            if (cjk) {
                flushLatin(latin, out);
                cjkRun.add(cp);
            } else if (Character.isLetterOrDigit(cp)) {
                flushCjk(cjkRun, out);
                latin.appendCodePoint(Character.toLowerCase(cp));
            } else {
                flushLatin(latin, out);
                flushCjk(cjkRun, out);
            }
            i += Character.charCount(cp);
        }
        flushLatin(latin, out);
        flushCjk(cjkRun, out);
        return new ArrayList<>(out);
    }

    private static void flushLatin(StringBuilder latin, Set<String> out) {
        if (latin.length() >= 2) {
            out.add(latin.toString());
        }
        latin.setLength(0);
    }

    private static void flushCjk(List<Integer> run, Set<String> out) {
        if (run.isEmpty()) {
            return;
        }
        if (run.size() <= 4) {
            StringBuilder whole = new StringBuilder();
            run.forEach(whole::appendCodePoint);
            out.add(whole.toString());
        }
        for (int i = 0; i + 1 < run.size(); i++) {
            out.add(new String(Character.toChars(run.get(i))) + new String(Character.toChars(run.get(i + 1))));
        }
        run.clear();
    }

    private static boolean isCjk(int cp) {
        return cp >= 0x4E00 && cp <= 0x9FFF;
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    /** 把召回结果渲染成可注入 system 的文本块 */
    public static String render(List<Hit> hits) {
        if (hits == null || hits.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【更早对话的相关片段(已压缩,按相关度排序)】\n");
        int i = 1;
        for (Hit h : hits) {
            sb.append(i++).append(". ").append(h.text().replace("\n", " ")).append('\n');
        }
        return sb.toString();
    }
}
