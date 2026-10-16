<script setup>
/**
 * 消息气泡组件
 * 支持两种角色：user（我，右对齐）与 assistant（AI，左对齐）
 *
 * 流式渲染顺序：先展示引用卡片（meta 事件到达即渲染），再逐字输出正文（delta 事件）
 */
import { computed } from 'vue'
import MarkdownContent from './MarkdownContent.vue'
import { formatDateTime, formatDuration } from '@/utils/format'

const props = defineProps({
  // 消息对象 {role, content, citations, model, latencyMs, streaming, error, createdAt}
  message: {
    type: Object,
    required: true
  }
})

const emit = defineEmits(['select-citation'])

// 是否为用户消息
const isUser = computed(() => props.message.role === 'user')
// 引用列表
const citations = computed(() => props.message.citations || [])
// 是否正在流式输出
const streaming = computed(() => !!props.message.streaming)

/**
 * 点击引用卡片时，把该条引用同步到右侧溯源面板
 * @param {object} citation 引用对象
 */
function handleSelect (citation) {
  emit('select-citation', citation)
}
</script>

<template>
  <div class="message-row" :class="isUser ? 'is-user' : 'is-assistant'">
    <el-avatar :size="32" class="message-avatar" :class="isUser ? 'avatar-user' : 'avatar-ai'">
      {{ isUser ? '我' : 'AI' }}
    </el-avatar>

    <div class="message-main">
      <div class="message-head">
        <span class="message-role">{{ isUser ? '我' : 'AI 助手' }}</span>
        <span class="message-time">{{ formatDateTime(message.createdAt) }}</span>
      </div>

      <div class="message-body" :class="isUser ? 'body-user' : 'body-ai'">
        <!-- 引用来源：meta 事件先于正文到达，因此展示在正文之前 -->
        <div v-if="!isUser && citations.length" class="message-citations">
          <div class="citations-title">
            <el-icon><Link /></el-icon>
            <span>引用来源（{{ citations.length }}）</span>
          </div>
          <div class="citations-list">
            <div
              v-for="item in citations"
              :key="item.index"
              class="citation-chip"
              :title="item.snippet"
              @click="handleSelect(item)"
            >
              <span class="chip-index">[{{ item.index }}]</span>
              <span class="chip-name text-ellipsis">{{ item.docName }}</span>
              <span v-if="item.pageNo !== null && item.pageNo !== undefined" class="chip-page">
                P{{ item.pageNo }}
              </span>
              <span class="chip-score">{{ Math.round((item.score || 0) * 100) }}%</span>
            </div>
          </div>
        </div>

        <!-- 正文 -->
        <MarkdownContent v-if="message.content" :content="message.content" />
        <span v-if="streaming" class="typing-cursor"></span>
        <span v-if="streaming && !message.content" class="thinking-text">正在检索知识库并生成回答…</span>

        <!-- 错误提示 -->
        <div v-if="message.error" class="message-error">
          <el-icon><WarningFilled /></el-icon>
          <span>{{ message.error }}</span>
        </div>
      </div>

      <!-- 生成统计信息 -->
      <div v-if="!isUser" class="message-footer">
        <el-tag v-if="message.model" size="small" type="info" effect="plain">
          {{ message.provider ? message.provider + ' / ' : '' }}{{ message.model }}
        </el-tag>
        <el-tag v-if="message.latencyMs" size="small" type="success" effect="plain">
          耗时 {{ formatDuration(message.latencyMs) }}
        </el-tag>
        <el-tag v-if="message.totalTokens" size="small" type="info" effect="plain">
          {{ message.totalTokens }} tokens
        </el-tag>
        <el-tag v-if="streaming" size="small" type="warning" effect="plain">生成中</el-tag>
      </div>
    </div>
  </div>
</template>

<style scoped>
.message-row {
  display: flex;
  gap: 10px;
  margin-bottom: 20px;
}

.message-row.is-user {
  flex-direction: row-reverse;
}

.message-avatar {
  flex-shrink: 0;
  font-size: 12px;
}

.avatar-user {
  background: #409eff;
  color: #fff;
}

.avatar-ai {
  background: #67c23a;
  color: #fff;
}

.message-main {
  max-width: 78%;
  min-width: 0;
}

.is-user .message-main {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.message-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
  font-size: 12px;
  color: var(--app-text-secondary);
}

.is-user .message-head {
  flex-direction: row-reverse;
}

.message-role {
  font-weight: 600;
  color: #606266;
}

.message-body {
  padding: 10px 14px;
  border-radius: 6px;
  border: 1px solid var(--app-border);
  background: #fff;
}

.body-user {
  background: #ecf5ff;
  border-color: #d9ecff;
}

.message-citations {
  padding-bottom: 8px;
  margin-bottom: 8px;
  border-bottom: 1px dashed var(--app-border);
}

.citations-title {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-bottom: 6px;
  font-size: 12px;
  font-weight: 600;
  color: #409eff;
}

.citations-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.citation-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 260px;
  padding: 2px 8px;
  font-size: 12px;
  background: #f7f8fa;
  border: 1px solid #ebeef5;
  border-radius: 12px;
  cursor: pointer;
  transition: all 0.2s;
}

.citation-chip:hover {
  background: #ecf5ff;
  border-color: #b3d8ff;
}

.chip-index {
  color: #409eff;
  font-weight: 600;
}

.chip-name {
  max-width: 130px;
}

.chip-page,
.chip-score {
  color: var(--app-text-secondary);
  font-size: 11px;
}

.chip-score {
  color: #67c23a;
}

/* 打字机光标 */
.typing-cursor {
  display: inline-block;
  width: 6px;
  height: 14px;
  margin-left: 2px;
  vertical-align: text-bottom;
  background: #409eff;
  animation: cursor-blink 1s steps(1) infinite;
}

@keyframes cursor-blink {
  0%,
  50% {
    opacity: 1;
  }
  50.01%,
  100% {
    opacity: 0;
  }
}

.thinking-text {
  font-size: 13px;
  color: var(--app-text-secondary);
}

.message-error {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 6px;
  font-size: 12px;
  color: #f56c6c;
}

.message-footer {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 6px;
}
</style>
