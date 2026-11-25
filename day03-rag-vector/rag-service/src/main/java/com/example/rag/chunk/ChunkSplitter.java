package com.example.rag.chunk;

import com.example.rag.config.RagProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 结构化优先、长度兜底的分块器。
 * <p>顺序是:先按 `##` 切小节 → 小节超长再按句子切 → 句子还超长才硬切。
 * 代码围栏整块当原子,不允许切 —— 半个代码块比没有代码块更糟。</p>
 */
@Component
public class ChunkSplitter {

    private final RagProperties.Chunk cfg;

    public ChunkSplitter(RagProperties props) {
        this.cfg = props.getChunk();
    }

    public List<Chunk> split(DocLoader.Doc doc) {
        List<Chunk> chunks = new ArrayList<>();
        int ord = 0;
        for (Section section : sections(doc.body())) {
            for (String piece : pack(section.body())) {
                chunks.add(new Chunk(doc.id() + "#" + String.format("%02d", ord),
                        doc.id(), doc.title(), section.title(), ord, piece));
                ord++;
            }
        }
        return chunks;
    }

    private record Section(String title, String body) {
    }

    /** 按 `## ` 切小节。第一个 `##` 之前的内容归入"概述"。 */
    private List<Section> sections(String body) {
        List<Section> out = new ArrayList<>();
        String currentTitle = "概述";
        StringBuilder buf = new StringBuilder();
        for (String line : body.split("\n")) {
            if (line.startsWith("# ") && !line.startsWith("## ")) {
                continue; // 一级标题是文档名,已经在 Chunk.docTitle 里
            }
            if (line.startsWith("## ")) {
                if (buf.toString().trim().length() > 0) {
                    out.add(new Section(currentTitle, buf.toString().trim()));
                }
                currentTitle = line.substring(3).trim();
                buf.setLength(0);
                continue;
            }
            buf.append(line).append('\n');
        }
        if (buf.toString().trim().length() > 0) {
            out.add(new Section(currentTitle, buf.toString().trim()));
        }
        return out;
    }

    /** 把一段正文打成不超过 maxChars 的块,相邻块带 overlapChars 的重叠。 */
    List<String> pack(String text) {
        List<String> atoms = atoms(text);
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < atoms.size()) {
            int start = i;
            int len = 0;
            while (i < atoms.size()) {
                int next = atoms.get(i).length();
                if (len > 0 && len + next > cfg.getMaxChars()) {
                    break;
                }
                len += next;
                i++;
            }
            StringBuilder sb = new StringBuilder();
            for (int j = start; j < i; j++) {
                sb.append(atoms.get(j));
            }
            String piece = sb.toString().trim();
            if (!piece.isEmpty()) {
                out.add(piece);
            }
            if (i >= atoms.size()) {
                break;
            }
            // 回退若干句作为重叠区。至少保留一个原子,否则会原地打转
            int back = i;
            int overlap = 0;
            while (back > start + 1 && overlap + atoms.get(back - 1).length() <= cfg.getOverlapChars()) {
                overlap += atoms.get(back - 1).length();
                back--;
            }
            // 单句就超过 overlap 预算时,至少重叠一句,否则两块之间会出现信息断层
            if (back == i && i > start + 1) {
                back = i - 1;
            }
            i = back;
        }
        return mergeTiny(out);
    }

    /** 末尾碎片并入前一块,避免出现只有半句话的块。 */
    private List<String> mergeTiny(List<String> pieces) {
        if (pieces.size() <= 1) {
            return pieces;
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < pieces.size(); i++) {
            String p = pieces.get(i);
            boolean last = i == pieces.size() - 1;
            if (last && p.length() < cfg.getMinChars() && !out.isEmpty()) {
                int idx = out.size() - 1;
                String merged = out.get(idx) + "\n" + p;
                if (merged.length() <= cfg.getMaxChars() * 1.25) {
                    out.set(idx, merged);
                    continue;
                }
            }
            out.add(p);
        }
        return out;
    }

    /**
     * 切成句子与代码块两种原子。
     * <p>必须是"一句一个原子":如果先把多句攒成一大块再切,硬切的位置必然落在句子中间,
     * 重叠区也会以整块为单位回退,导致重叠要么大到超过上限、要么完全没有 ——
     * 这个 bug 一开始让我的 overlap 实测值为 0。</p>
     */
    private List<String> atoms(String text) {
        List<String> out = new ArrayList<>();
        boolean inCode = false;
        StringBuilder code = new StringBuilder();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                if (inCode) {
                    code.append(line).append('\n');
                    out.add(code.toString());
                    code.setLength(0);
                    inCode = false;
                } else {
                    code.append(line).append('\n');
                    inCode = true;
                }
                continue;
            }
            if (inCode) {
                code.append(line).append('\n');
                continue;
            }
            if (trimmed.isEmpty()) {
                continue;
            }
            List<String> ss = sentences(line);
            for (int k = 0; k < ss.size(); k++) {
                String atom = ss.get(k);
                // 行末补换行,保住原文的段落结构
                out.add(k == ss.size() - 1 ? atom + "\n" : atom);
            }
        }
        if (code.length() > 0) {
            out.add(code.toString());
        }
        // 单个原子本身就超过上限时硬切(例如一整段没有任何标点的长文本)
        List<String> normalized = new ArrayList<>();
        for (String atom : out) {
            if (atom.length() <= cfg.getMaxChars()) {
                normalized.add(atom);
                continue;
            }
            int from = 0;
            while (from < atom.length()) {
                int to = Math.min(atom.length(), from + cfg.getMaxChars());
                normalized.add(atom.substring(from, to));
                from = to;
            }
        }
        return normalized;
    }

    /** 按中英文句末标点切句,标点保留在句尾。 */
    private List<String> sentences(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            sb.append(c);
            if (c == '。' || c == '！' || c == '？' || c == '；' || c == '!' || c == '?' || c == ';') {
                out.add(sb.toString());
                sb.setLength(0);
            }
        }
        if (sb.length() > 0) {
            out.add(sb.toString());
        }
        return out;
    }
}
