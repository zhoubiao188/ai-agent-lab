export interface Chunk {
  id: string
  docId: string
  docTitle: string
  section: string
  ord: number
  text: string
}

export interface ScoredChunk {
  chunk: Chunk
  score: number
  bm25Rank?: number
  bm25Score?: number
  vectorRank?: number
  vectorScore?: number
  rerankScore?: number
}

export interface RetrieveResponse {
  query: string
  mode: string
  tookMs: number
  total: number
  hits: ScoredChunk[]
}

export interface DocStat {
  docId: string
  title: string
  chunks: number
}

export interface KbStats {
  docs: number
  chunks: number
  vocab: number
  avgDocLen: number
  dim: number
  embedding: string
  indexType: string
  buildMillis: number
  embedMillis: number
  cacheHitRate: number
  llm: string
  byDoc: DocStat[]
}

export interface TypeStat {
  n: number
  recall5: number
  mrr: number
}

export interface StrategyResult {
  mode: string
  label: string
  recall1: number
  recall3: number
  recall5: number
  mrr: number
  ndcg5: number
  p50Micros: number
  p95Micros: number
  byType: Record<string, TypeStat>
}

export interface Delta {
  from: string
  to: string
  recall5: number
  mrr: number
  ndcg5: number
}

export interface CaseRow {
  id: string
  type: string
  query: string
  golds: string[]
  ranks: Record<string, number>
}

export interface EvalReport {
  generatedAt: string
  embeddingModel: string
  indexType: string
  docs: number
  chunks: number
  cases: number
  elapsedMs: number
  strategies: StrategyResult[]
  deltas: Delta[]
  details: CaseRow[]
}

export interface Citation {
  index: number
  chunkId: string
  title: string
  section: string
  score: number
  snippet: string
}

export type Mode = 'bm25' | 'vector' | 'hybrid' | 'full'
