package com.example.memory.tool.impl;

import com.example.memory.tool.AgentTool;
import com.example.memory.tool.Schemas;
import com.example.memory.tool.ToolContext;
import com.example.memory.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 内部知识库检索(轻量 BM25 变体,零依赖)。
 * <p>够用的原因:企业 FAQ 通常只有几十到几百条,词面检索召回足够好;
 * 等文档量上千、用户表述很口语化时,再换成向量检索(day03 会讲怎么平滑替换)。</p>
 */
@Component
public class KnowledgeBaseTool implements AgentTool {

    private static final double K1 = 1.5;
    private static final double B = 0.75;

    private record Doc(String id, String title, String body) {
    }

    private final List<Doc> docs = List.of(
            new Doc("KB-001", "退货政策",
                    "自签收之日起 7 天内,商品未拆封且不影响二次销售可无理由退货。"
                            + "生鲜、定制类、已激活的电子产品不支持无理由退货。退货运费由责任方承担:"
                            + "质量问题商家承担,个人原因买家承担。"),
            new Doc("KB-002", "换货流程",
                    "换货需在签收后 15 天内申请,进入「我的订单 - 申请售后 - 换货」提交,"
                            + "审核通过后按页面提示寄回。换货不支持更换为不同商品,只能换同款同规格。"),
            new Doc("KB-003", "发票开具",
                    "订单完成后 30 天内可在订单详情页申请电子发票,支持个人与企业抬头。"
                            + "企业抬头需提供公司名称与税号。发票一旦开具不支持修改抬头,请仔细核对。"),
            new Doc("KB-004", "会员等级规则",
                    "会员分为 V1~V5,按近 12 个月累计消费金额升级:V2 满 1000 元,V3 满 5000 元,"
                            + "V4 满 20000 元,V5 满 50000 元。等级有效期为 12 个月,到期按当期消费重新核算。"),
            new Doc("KB-005", "物流时效",
                    "现货商品在付款后 24 小时内发货,偏远地区 48 小时内。默认使用顺丰/京东物流,"
                            + "一般 1~3 天送达;新疆、西藏等地区 3~7 天。超时未发货可申请补偿券。")
    );

    private final Map<String, Integer> docLength = new HashMap<>();
    private final Map<String, Integer> docFreq = new HashMap<>();
    private final double avgLength;

    public KnowledgeBaseTool() {
        int total = 0;
        for (Doc d : docs) {
            List<String> tokens = tokenize(d.title() + " " + d.body());
            docLength.put(d.id(), tokens.size());
            total += tokens.size();
            Set<String> unique = Set.copyOf(tokens);
            unique.forEach(t -> docFreq.merge(t, 1, Integer::sum));
        }
        this.avgLength = (double) total / Math.max(1, docs.size());
    }

    @Override
    public String name() {
        return "search_knowledge_base";
    }

    @Override
    public String description() {
        return "在企业内部知识库中检索售后政策、发票、会员、物流等制度说明。"
                + "当用户询问平台规则、政策、流程类问题时必须调用,不要凭常识回答。";
    }

    @Override
    public JsonNode parameterSchema() {
        ObjectNode schema = Schemas.object();
        Schemas.string(schema, "query",
                "检索语句,用用户问题中的关键名词,例如「退货 运费」「发票 抬头」", true);
        Schemas.number(schema, "topK", "返回条数,默认 3,最大 5", false);
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        long start = System.currentTimeMillis();
        String query = Schemas.str(args, "query", "");
        if (query.isBlank()) {
            return ToolResult.fail("query 不能为空", System.currentTimeMillis() - start);
        }
        int topK = Math.min(5, Math.max(1, args.path("topK").asInt(3)));
        List<String> tokens = tokenize(query);
        if (tokens.isEmpty()) {
            return ToolResult.fail("query 中没有任何可检索的关键词", System.currentTimeMillis() - start);
        }

        record Scored(Doc doc, double score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (Doc d : docs) {
            List<String> docTokens = tokenize(d.title() + " " + d.body());
            double score = 0;
            for (String t : Set.copyOf(tokens)) {
                int tf = 0;
                for (String dt : docTokens) {
                    if (dt.equals(t)) {
                        tf++;
                    }
                }
                if (tf == 0) {
                    continue;
                }
                int df = docFreq.getOrDefault(t, 0);
                double idf = Math.log(1 + (docs.size() - df + 0.5) / (df + 0.5));
                double norm = tf * (K1 + 1) / (tf + K1 * (1 - B + B * docLength.get(d.id()) / avgLength));
                // 标题命中加权,标题往往最能代表主题
                double titleBoost = d.title().contains(t) ? 1.6 : 1.0;
                score += idf * norm * titleBoost;
            }
            if (score > 0) {
                scored.add(new Scored(d, score));
            }
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));

        if (scored.isEmpty()) {
            return ToolResult.ok("知识库中未检索到与「%s」相关的内容。请告知用户暂无相关制度说明,建议联系人工客服。"
                    .formatted(query), System.currentTimeMillis() - start);
        }

        StringBuilder sb = new StringBuilder("知识库检索结果(按相关度降序,请在回答中标注出处编号):\n");
        for (int i = 0; i < Math.min(topK, scored.size()); i++) {
            Scored s = scored.get(i);
            sb.append("\n[").append(i + 1).append("] ").append(s.doc().id())
                    .append(" 《").append(s.doc().title()).append("》(相关度 ")
                    .append(String.format("%.2f", s.score())).append(")\n")
                    .append(s.doc().body()).append('\n');
        }
        return ToolResult.ok(sb.toString(), System.currentTimeMillis() - start);
    }

    /**
     * 中文按 2-gram 切分,英文/数字按空白切分。无需分词器依赖。
     * <p>刻意<b>不</b>保留单字:中文单字歧义太大,「氧气」的「气」会命中「空气质量」,
     * 实测单字召回带来的噪声远大于收益。</p>
     */
    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder ascii = new StringBuilder();
        List<Character> cjk = new ArrayList<>();
        for (char c : text.toLowerCase().toCharArray()) {
            if (c >= 0x4e00 && c <= 0x9fff) {
                flushAscii(ascii, tokens);
                cjk.add(c);
            } else if (Character.isLetterOrDigit(c)) {
                ascii.append(c);
            } else {
                flushAscii(ascii, tokens);
                flushCjk(cjk, tokens);
            }
        }
        flushAscii(ascii, tokens);
        flushCjk(cjk, tokens);
        return tokens;
    }

    private static void flushAscii(StringBuilder buf, List<String> out) {
        if (buf.length() > 0) {
            out.add(buf.toString());
            buf.setLength(0);
        }
    }

    private static void flushCjk(List<Character> cjk, List<String> out) {
        if (cjk.size() == 1) {
            out.add(String.valueOf(cjk.get(0)));
        }
        for (int i = 0; i + 1 < cjk.size(); i++) {
            out.add("" + cjk.get(i) + cjk.get(i + 1));
        }
        cjk.clear();
    }
}
