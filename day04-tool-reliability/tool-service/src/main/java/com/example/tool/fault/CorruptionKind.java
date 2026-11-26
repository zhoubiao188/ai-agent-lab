package com.example.tool.fault;

/**
 * 模型最容易犯的六类参数错误。这不是凭空设计的,是按真实 trace 里出现频次排的:
 * 键名风格不一致 > 数字被写成字符串 > 枚举大小写 > 多带字段 > 漏必填 > 类型完全错。
 *
 * <p>评测里按这个分布做参数污染,才能测出"参数修复"到底覆盖了百分之多少的真实场景。</p>
 */
public enum CorruptionKind {
    /** orderId 写成 order_id,Java/JS 两种命名习惯打架 */
    SNAKE_KEY,
    /** 金额 199 写成 "199" */
    STRING_NUMBER,
    /** 枚举 normal 写成 Normal */
    ENUM_CASE,
    /** 漏掉必填的 orderId */
    MISSING_REQUIRED,
    /** 多带一个 schema 里没有的字段 */
    EXTRA_FIELD,
    /** 布尔写成字符串 true */
    STRING_BOOLEAN
}
