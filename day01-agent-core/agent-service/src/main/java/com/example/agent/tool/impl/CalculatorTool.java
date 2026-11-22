package com.example.agent.tool.impl;

import com.example.agent.tool.AgentTool;
import com.example.agent.tool.Schemas;
import com.example.agent.tool.ToolContext;
import com.example.agent.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * 四则运算工具。
 * <p>为什么不直接写 ScriptEngine / SpEL:那等于把 RCE 送上门。这里用递归下降写一个
 * 只认 {@code + - * / % ( )} 与数字的解析器,永不执行用户提供的代码。</p>
 */
@Component
public class CalculatorTool implements AgentTool {

    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);

    @Override
    public String name() {
        return "calculator";
    }

    @Override
    public String description() {
        return "计算数学表达式,支持 + - * / % 与括号的任意组合(如 (12.5+3)*4/2)。"
                + "涉及任何算术、汇率折算、百分比计算时都必须调用本工具,不要心算。";
    }

    @Override
    public JsonNode parameterSchema() {
        return Schemas.string(Schemas.object(), "expression",
                "只含数字、小数点、+ - * / % ( ) 的表达式,不能包含字母或函数名", true);
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext ctx) {
        long start = System.currentTimeMillis();
        String expr = Schemas.str(args, "expression", null);
        if (expr == null || expr.isBlank()) {
            return ToolResult.fail("expression 不能为空", System.currentTimeMillis() - start);
        }
        try {
            Parser parser = new Parser(expr);
            BigDecimal result = parser.parseExpression();
            parser.expectEnd();
            String text = result.stripTrailingZeros().toPlainString();
            return ToolResult.ok("表达式 %s = %s".formatted(expr.trim(), text),
                    System.currentTimeMillis() - start);
        } catch (ArithmeticException e) {
            return ToolResult.fail("数学错误: " + e.getMessage(), System.currentTimeMillis() - start);
        } catch (IllegalArgumentException e) {
            return ToolResult.fail("表达式非法: " + e.getMessage(), System.currentTimeMillis() - start);
        }
    }

    /** 递归下降解析器:expression := term (('+'|'-') term)* */
    static final class Parser {
        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src;
        }

        BigDecimal parseExpression() {
            BigDecimal value = parseTerm();
            while (true) {
                skipSpaces();
                if (consume('+')) {
                    value = value.add(parseTerm(), MC);
                } else if (consume('-')) {
                    value = value.subtract(parseTerm(), MC);
                } else {
                    return value;
                }
            }
        }

        private BigDecimal parseTerm() {
            BigDecimal value = parseFactor();
            while (true) {
                skipSpaces();
                if (consume('*')) {
                    value = value.multiply(parseFactor(), MC);
                } else if (consume('/')) {
                    BigDecimal divisor = parseFactor();
                    if (divisor.signum() == 0) {
                        throw new ArithmeticException("除数不能为 0");
                    }
                    value = value.divide(divisor, MC);
                } else if (consume('%')) {
                    BigDecimal divisor = parseFactor();
                    if (divisor.signum() == 0) {
                        throw new ArithmeticException("取模的除数不能为 0");
                    }
                    value = value.remainder(divisor, MC);
                } else {
                    return value;
                }
            }
        }

        private BigDecimal parseFactor() {
            skipSpaces();
            if (consume('-')) {
                return parseFactor().negate();
            }
            if (consume('+')) {
                return parseFactor();
            }
            if (consume('(')) {
                BigDecimal inner = parseExpression();
                skipSpaces();
                if (!consume(')')) {
                    throw new IllegalArgumentException("缺少右括号,位置 " + pos);
                }
                return inner;
            }
            return parseNumber();
        }

        private BigDecimal parseNumber() {
            skipSpaces();
            int begin = pos;
            boolean dot = false;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (Character.isDigit(c)) {
                    pos++;
                } else if (c == '.' && !dot) {
                    dot = true;
                    pos++;
                } else {
                    break;
                }
            }
            if (begin == pos) {
                throw new IllegalArgumentException("位置 " + pos + " 处期望数字");
            }
            return new BigDecimal(src.substring(begin, pos));
        }

        void expectEnd() {
            skipSpaces();
            if (pos != src.length()) {
                throw new IllegalArgumentException("位置 " + pos + " 处存在无法识别的字符 '" + src.charAt(pos) + "'");
            }
        }

        private void skipSpaces() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }

        private boolean consume(char c) {
            if (pos < src.length() && src.charAt(pos) == c) {
                pos++;
                return true;
            }
            return false;
        }
    }
}
