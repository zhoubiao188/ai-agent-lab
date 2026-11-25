package com.example.rag.chunk;

/**
 * 一个可检索的最小单元。
 *
 * @param id       全局唯一,格式 docId#序号,便于在评测集里做锚点标注
 * @param docId    来源文档
 * @param docTitle 文档标题
 * @param section  小节名
 * @param ord      文档内序号
 * @param text     正文,入库前会拼上标题与小节名(见 embedText)
 */
public record Chunk(String id, String docId, String docTitle, String section, int ord, String text) {

    /**
     * 送给 embedding 模型的文本。
     * <p>关键点:块头必须注入标题与小节名。否则出现"它默认是 200"这种无主语块,
     * 向量会漂移,生成阶段模型也不知道在讲什么。这一步对准确率的提升远比换模型明显。</p>
     */
    public String embedText() {
        return docTitle + " / " + section + "\n" + text;
    }

    /** 给用户看的引用标签。 */
    public String citation() {
        return docTitle + " · " + section;
    }
}
