<script setup lang="ts">
import type { ScoredChunk } from '../types'

const props = defineProps<{ hit: ScoredChunk; rank: number }>()

function pct(v?: number | null): string {
  return v === undefined || v === null ? '—' : v.toFixed(4)
}
</script>

<template>
  <div class="hit">
    <div class="hit-top">
      <span class="badge">#{{ props.rank }}</span>
      <span class="doc">{{ props.hit.chunk.docTitle }}</span>
      <span class="section">/ {{ props.hit.chunk.section }}</span>
      <span class="badge gray">{{ props.hit.chunk.id }}</span>
    </div>
    <p class="text">{{ props.hit.chunk.text }}</p>
    <div class="scores">
      <span class="score-chip">融合 {{ pct(props.hit.score) }}</span>
      <span class="score-chip">
        BM25 {{ props.hit.bm25Rank ? '#' + props.hit.bm25Rank : '—' }}
        <template v-if="props.hit.bm25Score !== undefined">({{ pct(props.hit.bm25Score) }})</template>
      </span>
      <span class="score-chip">
        向量 {{ props.hit.vectorRank ? '#' + props.hit.vectorRank : '—' }}
        <template v-if="props.hit.vectorScore !== undefined">({{ pct(props.hit.vectorScore) }})</template>
      </span>
      <span v-if="props.hit.rerankScore !== undefined" class="score-chip green-chip">
        特征分 {{ pct(props.hit.rerankScore) }}
      </span>
    </div>
  </div>
</template>
