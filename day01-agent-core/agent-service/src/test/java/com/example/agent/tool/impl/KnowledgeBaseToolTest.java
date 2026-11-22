package com.example.agent.tool.impl;

import com.example.agent.tool.ToolContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeBaseToolTest {

    private final KnowledgeBaseTool tool = new KnowledgeBaseTool();
    private final ObjectMapper mapper = new ObjectMapper();

    private String search(String query) throws Exception {
        return tool.execute(mapper.readTree("{\"query\":\"" + query + "\",\"topK\":3}"),
                new ToolContext("t", "c", "u")).content();
    }

    @Test
    void findsReturnPolicyByKeyword() throws Exception {
        String out = search("退货 运费 谁承担");
        assertTrue(out.contains("KB-001"), out);
    }

    @Test
    void findsInvoiceDoc() throws Exception {
        String out = search("发票 抬头 税号");
        assertTrue(out.contains("KB-003"), out);
    }

    @Test
    void returnsFriendlyMessageWhenNothingMatched() throws Exception {
        String out = search("火星基地 氧气 循环");
        assertTrue(out.contains("未检索到"), out);
    }

    @Test
    void tokenizerProducesBigramsForChinese() {
        assertTrue(KnowledgeBaseTool.tokenize("退货运费").contains("退货"));
        assertTrue(KnowledgeBaseTool.tokenize("退货运费").contains("运费"));
    }
}
