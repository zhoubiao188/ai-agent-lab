# Day 03 · RAG 检索工程化:分块 + 混合召回 + 重排 + 召回评测集

> 系列第三篇。前两篇分别是[从零手写 AI Agent](../day01-agent-core)和[Redis 会话记忆](../day02-memory-redis)。
> 这一篇解决一个具体问题:**RAG 效果不好时,你到底该改哪里?**

大多数 RAG demo 的写法是"文档切 500 字 → 存向量库 → top3 拼进 prompt",跑通很容易,上线后一旦答错就无从下手:
是分块切断了上下文?是向量模型不懂这个词?是 top-k 太小?还是模型没按资料回答?
没有数字,所有调整都是玄学。

所以这个项目做了三件在 demo 里通常被跳过的事:

1. **四路检索并排可对比**:BM25 / 向量 / RRF 混合 / 混合+重排,同一个查询一键切换;
2. **50 条人工标注的召回评测集**,输出 Recall@1/3/5、MRR、NDCG@5、P50/P95 延迟,以及按查询类型的拆解和失败样本;
3. **每次返回都带全部中间分数**——两路名次、融合分、特征分,一眼看出这条结果为什么排在这。

## 实测结果(本机 M1,6 篇文档 / 49 块 / 50 条样本)

| 策略 | Recall@1 | Recall@3 | Recall@5 | MRR | NDCG@5 | P95 |
| --- | --- | --- | --- | --- | --- | --- |
| ① 关键词 BM25 | 0.900 | 0.960 | 0.970 | 0.953 | 0.949 | 101μs |
| ② 向量召回 | 0.860 | 0.910 | 0.960 | 0.913 | 0.919 | 86μs |
| ③ 混合 RRF | 0.900 | 0.960 | 0.980 | 0.950 | 0.953 | 84μs |
| ④ 混合 + 重排 | 0.920 | 0.970 | 0.980 | 0.959 | 0.961 | 437μs |

三条值得记住的结论:

- **纯向量召回在这批数据上输给了 BM25**(MRR 0.913 vs 0.953)。技术文档里大量查询是术语、配置项、类名,精确匹配的收益比语义泛化更大。
- **混合的收益是"互补"而不是"更强"**:向量在 `paraphrase` 类型上 1.00、BM25 只有 0.92;BM25 在 `english` 上 1.00、向量只有 0.50。融合后两类都不掉。
- **重排只值 +0.009 MRR,代价是 5 倍延迟**。它是锦上添花,不是救命稻草。先把 Recall@5 做上去,再考虑重排。

## 快速开始

```bash
# 后端:rag-service(8081)+ gateway(8080)
mvn -q package -DskipTests
java -jar rag-service/target/rag-service-1.0.0.jar &
java -jar gateway/target/gateway-1.0.0.jar &

# 前端二选一
cd web-vue && npm i && npm run dev     # http://localhost:5173
cd web-react && npm i && npm run dev   # http://localhost:5174
```

零配置即可跑:向量用本地字符 n-gram 哈希实现,LLM 用 mock,不需要任何 Key。
接真实模型只要换环境变量:

```bash
export RAG_EMBED_PROVIDER=openai RAG_EMBED_BASE_URL=https://api.openai.com/v1 \
       RAG_EMBED_API_KEY=sk-xxx RAG_EMBED_MODEL=text-embedding-3-small \
       RAG_LLM_PROVIDER=openai RAG_LLM_BASE_URL=https://api.deepseek.com/v1 \
       RAG_LLM_API_KEY=sk-xxx RAG_LLM_MODEL=deepseek-chat
```

分块、索引、融合、重排的代码一行都不用改。

## 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/kb/stats` | 语料统计:文档/块/词表/维度/建索引耗时/缓存命中率 |
| GET | `/api/kb/chunks?docId=` | 查看任意文档被切成了哪些块 |
| POST | `/api/retrieve` | 检索调试,`mode` 取 `bm25`/`vector`/`hybrid`/`full` |
| GET | `/api/eval/report?force=true` | 跑消融实验,返回四种策略的指标与失败样本 |
| POST | `/api/ask` | SSE 引用式问答,先推 `citations` 再推 `delta` |

## 目录

```
rag-service/
  chunk/     DocLoader + ChunkSplitter(结构优先、代码块原子、句子级重叠)
  text/      Tokenizer(中文 bigram + 英文词/驼峰拆解,与向量模型解耦)
  search/    Bm25Index、RetrievalService(RRF 融合 + 重排编排)
  index/     FlatVectorIndex(基线)、IvfVectorIndex(k-means 聚类,有召回损失)
  embed/     HashingEmbeddingModel(零依赖)、OpenAiEmbeddingModel、带 LRU 缓存装饰器
  rerank/    FeatureReranker(五特征可解释重排)
  eval/      EvalRunner + Metrics(Recall/MRR/NDCG)+ 50 条评测集
  llm/       PromptBuilder + mock/OpenAI 兼容客户端
gateway/     Spring Cloud Gateway:SSE 超时与反缓冲、X-Request-Id 全链路追踪
web-vue/     Vue 3 + Vite 检索控制台
web-react/   React 18 + Vite 同一套控制台(用于对比两种写法)
```

## 设计取舍

- **RRF 而不是加权分数融合**:BM25 分数无上界、余弦在 0~1,直接加权必须归一化,而归一化依赖当次结果分布,换查询就漂。RRF 只用名次,`Σ 1/(60+rank)`,对量纲免疫。
- **特征重排而不是 Cross-Encoder**:五个可解释特征(IDF 加权覆盖率、连续短语、小节标题命中、块长偏好、命中位置)纯 CPU 微秒级,线上出问题能定位到是哪一项把正确块压下去的。
- **不引第三方分词器**:BM25 分词与向量分词解耦,行为可预测,出问题能一眼看出为什么没召回。
- **近似索引必须能看见召回损失**:IVF 的 `nprobe` 是速度/召回旋钮,写出来才能理解"某条数据永远搜不到"是怎么发生的。

## 测试

```bash
mvn test
```

覆盖分块(代码围栏不被切断、overlap 不为 0)、BM25 排序、RRF 尺度和不变性、IVF 召回损失上界、
NDCG 不重复计增益、SSE 事件顺序、50 条评测集跑通。另有两个"实验型"测试会打印延迟分段与 α 扫描结果:

- `LatencyProbeTest`:向量化/BM25/检索/重排各段耗时,以及 590 字超长查询的 P50/P95;
- `RerankTuningTest`:α 从 0 到 1 扫描,实测 α=0.2 最优(α=0.7 会把 MRR 从 0.95 拖到 0.92)。
