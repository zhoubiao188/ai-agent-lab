package com.example.rag.eval;

/**
 * 标注单元。
 *
 * @param docId  正确文档
 * @param anchor 定位锚点:必须是正确块里真实存在的一段短语
 *               <p>用锚点而不是块 ID,是因为分块参数一改块 ID 就全变了,
 *               而锚点在文本里,永远有效。这是评测集能长期复用的关键。</p>
 */
public record Gold(String docId, String anchor) {
}
