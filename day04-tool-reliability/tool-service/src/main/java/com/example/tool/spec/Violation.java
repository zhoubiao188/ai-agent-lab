package com.example.tool.spec;

/**
 * 一条参数校验不通过的记录。
 *
 * <p>关键设计:{@code path} 必须是 JSON Pointer 风格(如 {@code /items/0/price}),
 * 因为这条记录最终要原文回灌给模型。只说"参数不对"模型只能瞎猜,
 * 说"/paidAmount 期望 number,实际是 string \"199.00\"",它一次就能改对。</p>
 */
public record Violation(String path, Code code, String message) {

    public enum Code {
        NOT_OBJECT,
        MISSING_REQUIRED,
        TYPE_MISMATCH,
        ENUM_MISMATCH,
        OUT_OF_RANGE,
        PATTERN_MISMATCH,
        UNKNOWN_FIELD
    }

    /** 未知字段只告警不阻塞:宁可多带一个字段,也别为了它整轮失败 */
    public boolean blocking() {
        return code != Code.UNKNOWN_FIELD;
    }

    @Override
    public String toString() {
        return path + " [" + code + "] " + message;
    }
}
