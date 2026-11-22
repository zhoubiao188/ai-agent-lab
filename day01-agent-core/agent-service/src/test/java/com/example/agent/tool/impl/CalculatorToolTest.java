package com.example.agent.tool.impl;

import com.example.agent.tool.ToolContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalculatorToolTest {

    private final CalculatorTool tool = new CalculatorTool();
    private final ObjectMapper mapper = new ObjectMapper();

    private String eval(String expr) throws Exception {
        return tool.execute(mapper.readTree("{\"expression\":\"" + expr + "\"}"),
                new ToolContext("t", "c", "u")).content();
    }

    @Test
    void respectsOperatorPrecedence() throws Exception {
        assertTrue(eval("1+2*3").endsWith("= 7"));
    }

    @Test
    void handlesParenthesesAndDecimals() throws Exception {
        assertTrue(eval("(128.5 + 71.5) * 3 / 2").endsWith("= 300"));
    }

    @Test
    void handlesUnaryMinus() throws Exception {
        assertTrue(eval("-3 * -4").endsWith("= 12"));
    }

    @Test
    void rejectsDivisionByZero() throws Exception {
        String out = eval("5/0");
        assertTrue(out.startsWith("ERROR:"), out);
    }

    @Test
    void rejectsArbitraryCode() throws Exception {
        // 这是关键安全用例:绝不能把表达式当代码执行
        String out = eval("T(java.lang.Runtime).getRuntime().exec('id')");
        assertTrue(out.startsWith("ERROR:"), out);
        assertFalse(out.contains("uid="));
    }

    @Test
    void parserMatchesBigDecimalDirectly() {
        CalculatorTool.Parser p = new CalculatorTool.Parser("2*(3+4)");
        assertEquals(0, new BigDecimal("14").compareTo(p.parseExpression()));
    }
}
