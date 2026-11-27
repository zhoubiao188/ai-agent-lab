package com.example.o11y.span;

/** 一次运行的最终结论。SILENT_FAILURE 是本文重点:接口 200、状态 OK,但结果其实是错的。 */
public enum Outcome {
    SUCCESS("成功"),
    FAILED("失败"),
    SILENT_FAILURE("沉默失败"),
    UNKNOWN("未知");

    private final String label;

    Outcome(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
