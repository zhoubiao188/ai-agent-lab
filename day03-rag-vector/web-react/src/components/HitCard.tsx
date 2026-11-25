import type { ScoredChunk } from '../types'

function fmt(v?: number | null): string {
  return v === undefined || v === null ? '—' : v.toFixed(4)
}

export default function HitCard({ hit, rank }: { hit: ScoredChunk; rank: number }) {
  return (
    <div className="hit">
      <div className="hit-top">
        <span className="badge">#{rank}</span>
        <span className="doc">{hit.chunk.docTitle}</span>
        <span className="section">/ {hit.chunk.section}</span>
        <span className="badge gray">{hit.chunk.id}</span>
      </div>
      <p className="text">{hit.chunk.text}</p>
      <div className="scores">
        <span className="score-chip">融合 {fmt(hit.score)}</span>
        <span className="score-chip">
          BM25 {hit.bm25Rank ? `#${hit.bm25Rank}` : '—'}
          {hit.bm25Score !== undefined ? ` (${fmt(hit.bm25Score)})` : ''}
        </span>
        <span className="score-chip">
          向量 {hit.vectorRank ? `#${hit.vectorRank}` : '—'}
          {hit.vectorScore !== undefined ? ` (${fmt(hit.vectorScore)})` : ''}
        </span>
        {hit.rerankScore !== undefined && (
          <span className="score-chip green-chip">特征分 {fmt(hit.rerankScore)}</span>
        )}
      </div>
    </div>
  )
}
