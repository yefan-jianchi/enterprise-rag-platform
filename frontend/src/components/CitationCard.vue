<script setup>
/**
 * 引用溯源卡片：展示单个引用来源的文档名、页码、相关度与原文片段
 * 点击卡片可展开/收起片段详情
 */
import { computed, ref } from 'vue'

const props = defineProps({
  // 引用数据 {index,docName,pageNo,chunkIndex,score,snippet}
  citation: {
    type: Object,
    required: true
  },
  // 默认是否展开片段
  defaultExpanded: {
    type: Boolean,
    default: false
  }
})

const expanded = ref(props.defaultExpanded)

/**
 * 相关度转百分比（score 为 0-1 的小数）
 */
const scorePercent = computed(() => {
  const score = Number(props.citation.score)
  if (Number.isNaN(score)) return 0
  return Math.round(score * 100)
})

/**
 * 相关度对应的颜色：越高越绿
 */
const scoreColor = computed(() => {
  const p = scorePercent.value
  if (p >= 80) return '#67c23a'
  if (p >= 60) return '#409eff'
  if (p >= 40) return '#e6a23c'
  return '#909399'
})

function toggle () {
  expanded.value = !expanded.value
}
</script>

<template>
  <div class="citation-card" :class="{ 'is-expanded': expanded }" @click="toggle">
    <div class="citation-head">
      <span class="citation-index">[{{ citation.index }}]</span>
      <el-icon class="citation-icon"><Document /></el-icon>
      <span class="citation-name text-ellipsis" :title="citation.docName">
        {{ citation.docName || '未知文档' }}
      </span>
      <el-icon class="citation-arrow">
        <component :is="expanded ? 'ArrowUp' : 'ArrowDown'" />
      </el-icon>
    </div>

    <div class="citation-meta">
      <el-tag v-if="citation.pageNo !== null && citation.pageNo !== undefined" size="small" type="info" effect="plain">
        第 {{ citation.pageNo }} 页
      </el-tag>
      <el-tag v-if="citation.chunkIndex !== null && citation.chunkIndex !== undefined" size="small" type="info" effect="plain">
        分块 #{{ citation.chunkIndex }}
      </el-tag>
      <span class="citation-score" :style="{ color: scoreColor }">
        相关度 {{ scorePercent }}%
      </span>
      <el-progress
        :percentage="scorePercent"
        :stroke-width="4"
        :show-text="false"
        :color="scoreColor"
        class="citation-progress"
      />
    </div>

    <!-- 展开后才显示原文片段，避免面板过长 -->
    <div v-if="expanded" class="citation-snippet">
      <div class="snippet-label">原文片段</div>
      <div class="snippet-content">{{ citation.snippet || '（后端未返回片段内容）' }}</div>
    </div>
    <div v-else class="citation-preview text-ellipsis">
      {{ citation.snippet || '（后端未返回片段内容）' }}
    </div>
  </div>
</template>

<style scoped>
.citation-card {
  padding: 10px 12px;
  margin-bottom: 8px;
  background: #fff;
  border: 1px solid var(--app-border);
  border-left: 3px solid #409eff;
  border-radius: 4px;
  cursor: pointer;
  transition: box-shadow 0.2s, border-color 0.2s;
}

.citation-card:hover {
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
}

.citation-head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
}

.citation-index {
  color: #409eff;
  flex-shrink: 0;
}

.citation-icon {
  color: #909399;
  flex-shrink: 0;
}

.citation-name {
  flex: 1;
  min-width: 0;
}

.citation-arrow {
  color: #c0c4cc;
  flex-shrink: 0;
}

.citation-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
  flex-wrap: wrap;
}

.citation-score {
  font-size: 12px;
  font-weight: 600;
}

.citation-progress {
  width: 70px;
}

.citation-preview,
.citation-snippet {
  margin-top: 8px;
  font-size: 12px;
  line-height: 1.6;
  color: #606266;
}

.citation-preview {
  color: var(--app-text-secondary);
}

.snippet-label {
  font-size: 12px;
  color: var(--app-text-secondary);
  margin-bottom: 4px;
}

.snippet-content {
  padding: 8px;
  background: #f7f8fa;
  border-radius: 4px;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 220px;
  overflow: auto;
}
</style>
