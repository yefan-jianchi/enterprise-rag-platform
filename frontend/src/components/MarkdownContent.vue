<script setup>
/**
 * 轻量 Markdown 渲染组件（无第三方依赖）
 *
 * RAG 回答 / 客服回答通常是 Markdown 文本，包含标题、列表、表格、代码块等，
 * 直接输出纯文本会影响可读性，因此这里实现一个「够用就好」的子集解析器：
 *   标题 / 无序列表 / 有序列表 / 引用 / 分割线 / 围栏代码块 / 表格 / 行内样式
 *
 * 安全说明：所有文本先做 HTML 转义再拼接标签，避免 XSS 注入。
 * 之所以不引入 markdown-it：本项目要求依赖尽量精简，且该子集足够展示。
 */
import { computed } from 'vue'

const props = defineProps({
  // Markdown 源文本
  content: {
    type: String,
    default: ''
  }
})

/**
 * HTML 转义，防止内容中的尖括号被当作标签解析
 * @param {string} text 原始文本
 */
function escapeHtml (text) {
  return String(text)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/**
 * 处理行内样式：粗体、斜体、行内代码、链接
 * 注意顺序：先处理行内代码，避免代码里的 * 号被当成强调符号
 * @param {string} text 已转义的文本
 */
function inline (text) {
  return text
    // 行内代码
    .replace(/`([^`]+)`/g, '<code class="md-inline-code">$1</code>')
    // 粗体
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    // 斜体
    .replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em>$2</em>')
    // 链接（仅允许 http/https，降低风险）
    .replace(
      /\[([^\]]+)\]\((https?:\/\/[^\s)]+)\)/g,
      '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>'
    )
}

/**
 * 判断是否为表格分隔行，如 |---|---|
 * @param {string} line 行文本
 */
function isTableSeparator (line) {
  return /^\s*\|?[\s:|-]+\|[\s:|-]*$/.test(line) && line.includes('-')
}

/**
 * 解析表格：返回起始的结束下标与生成的 HTML
 * @param {string[]} lines 全部行
 * @param {number} start 表头所在行下标
 */
function parseTable (lines, start) {
  const headerCells = splitTableRow(lines[start])
  const rows = []
  let i = start + 2 // 跳过表头行与分隔行
  while (i < lines.length && lines[i].includes('|') && lines[i].trim()) {
    rows.push(splitTableRow(lines[i]))
    i++
  }
  let html = '<div class="md-table-wrap"><table class="md-table"><thead><tr>'
  html += headerCells.map((c) => `<th>${inline(escapeHtml(c))}</th>`).join('')
  html += '</tr></thead><tbody>'
  for (const row of rows) {
    html += '<tr>' + row.map((c) => `<td>${inline(escapeHtml(c))}</td>`).join('') + '</tr>'
  }
  html += '</tbody></table></div>'
  return { html, next: i }
}

/**
 * 拆分表格行：去掉首尾竖线后按竖线切分
 * @param {string} line 表格行
 */
function splitTableRow (line) {
  let text = line.trim()
  if (text.startsWith('|')) text = text.slice(1)
  if (text.endsWith('|')) text = text.slice(0, -1)
  return text.split('|').map((c) => c.trim())
}

/**
 * 核心解析：把 Markdown 文本转换为 HTML 字符串
 * @param {string} source Markdown 源文本
 */
function renderMarkdown (source) {
  if (!source) return ''
  const lines = String(source).replace(/\r\n/g, '\n').split('\n')
  const html = []
  // 列表状态：type 为 ul/ol 时表示正在收集列表项
  let listType = ''
  let listItems = []
  // 引用块缓冲
  let quoteLines = []

  /** 收尾当前列表 */
  function flushList () {
    if (!listType) return
    html.push(`<${listType} class="md-list">`)
    for (const item of listItems) {
      html.push(`<li>${inline(escapeHtml(item))}</li>`)
    }
    html.push(`</${listType}>`)
    listType = ''
    listItems = []
  }

  /** 收尾当前引用块 */
  function flushQuote () {
    if (!quoteLines.length) return
    html.push(
      `<blockquote class="md-quote">${quoteLines.map((l) => inline(escapeHtml(l))).join('<br/>')}</blockquote>`
    )
    quoteLines = []
  }

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    const trimmed = line.trim()

    // ---------- 围栏代码块 ----------
    if (/^\s*```/.test(line)) {
      flushList()
      flushQuote()
      const lang = trimmed.replace(/^```/, '').trim()
      const codeLines = []
      i++
      while (i < lines.length && !/^\s*```/.test(lines[i])) {
        // 去掉代码块整体的首行缩进，避免同时出现 4 空格缩进与 padding
        codeLines.push(lines[i].replace(/^ {4}/, ''))
        i++
      }
      html.push(
        `<pre class="md-code"><code data-lang="${escapeHtml(lang)}">${escapeHtml(codeLines.join('\n'))}</code></pre>`
      )
      continue
    }

    // ---------- 空行：结束列表与引用 ----------
    if (!trimmed) {
      flushList()
      flushQuote()
      continue
    }

    // ---------- 表格 ----------
    if (trimmed.includes('|') && i + 1 < lines.length && isTableSeparator(lines[i + 1])) {
      flushList()
      flushQuote()
      const table = parseTable(lines, i)
      html.push(table.html)
      i = table.next - 1
      continue
    }

    // ---------- 标题 ----------
    const heading = /^(#{1,6})\s+(.*)$/.exec(trimmed)
    if (heading) {
      flushList()
      flushQuote()
      const level = heading[1].length
      html.push(`<h${level} class="md-heading">${inline(escapeHtml(heading[2]))}</h${level}>`)
      continue
    }

    // ---------- 分割线 ----------
    if (/^(-{3,}|\*{3,}|_{3,})$/.test(trimmed)) {
      flushList()
      flushQuote()
      html.push('<hr class="md-hr"/>')
      continue
    }

    // ---------- 引用 ----------
    if (/^>\s?/.test(trimmed)) {
      flushList()
      quoteLines.push(trimmed.replace(/^>\s?/, ''))
      continue
    }
    flushQuote()

    // ---------- 无序列表 ----------
    const ulMatch = /^[-*+]\s+(.*)$/.exec(trimmed)
    if (ulMatch) {
      if (listType && listType !== 'ul') flushList()
      listType = 'ul'
      listItems.push(ulMatch[1])
      continue
    }

    // ---------- 有序列表 ----------
    const olMatch = /^\d+[.)]\s+(.*)$/.exec(trimmed)
    if (olMatch) {
      if (listType && listType !== 'ol') flushList()
      listType = 'ol'
      listItems.push(olMatch[1])
      continue
    }

    // ---------- 普通段落 ----------
    flushList()
    html.push(`<p class="md-p">${inline(escapeHtml(trimmed))}</p>`)
  }

  flushList()
  flushQuote()
  return html.join('')
}

const renderedHtml = computed(() => renderMarkdown(props.content))
</script>

<template>
  <!-- 内容均由本组件转义后生成，不存在外部注入的脚本 -->
  <div class="markdown-body" v-html="renderedHtml"></div>
</template>

<style scoped>
.markdown-body {
  font-size: 14px;
  line-height: 1.75;
  color: #303133;
  word-break: break-word;
}

.markdown-body :deep(.md-p) {
  margin: 0 0 8px;
}

.markdown-body :deep(.md-p:last-child) {
  margin-bottom: 0;
}

.markdown-body :deep(.md-heading) {
  margin: 12px 0 6px;
  font-size: 15px;
  font-weight: 600;
}

.markdown-body :deep(.md-list) {
  margin: 0 0 8px;
  padding-left: 20px;
}

.markdown-body :deep(.md-list li) {
  margin-bottom: 2px;
}

.markdown-body :deep(.md-quote) {
  margin: 8px 0;
  padding: 6px 12px;
  border-left: 3px solid #dcdfe6;
  background: #f7f8fa;
  color: #606266;
}

.markdown-body :deep(.md-hr) {
  margin: 12px 0;
  border: none;
  border-top: 1px solid var(--app-border);
}

.markdown-body :deep(.md-inline-code) {
  padding: 1px 5px;
  margin: 0 2px;
  font-family: Consolas, Monaco, 'Courier New', monospace;
  font-size: 12.5px;
  color: #c7254e;
  background: #f7f8fa;
  border: 1px solid #ebeef5;
  border-radius: 3px;
}

.markdown-body :deep(.md-code) {
  position: relative;
  margin: 8px 0;
  padding: 10px 12px;
  background: #282c34;
  border-radius: 4px;
  overflow: auto;
  max-height: 360px;
}

.markdown-body :deep(.md-code code) {
  font-family: Consolas, Monaco, 'Courier New', monospace;
  font-size: 12.5px;
  line-height: 1.6;
  color: #abb2bf;
  white-space: pre;
}

.markdown-body :deep(.md-code code[data-lang]:not([data-lang='']))::before {
  content: attr(data-lang);
  position: absolute;
  top: 4px;
  right: 8px;
  font-size: 11px;
  color: #636d83;
}

.markdown-body :deep(.md-table-wrap) {
  margin: 8px 0;
  overflow-x: auto;
}

.markdown-body :deep(.md-table) {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.markdown-body :deep(.md-table th),
.markdown-body :deep(.md-table td) {
  padding: 6px 10px;
  border: 1px solid var(--app-border);
  text-align: left;
}

.markdown-body :deep(.md-table th) {
  background: #fafafa;
  font-weight: 600;
}

.markdown-body :deep(a) {
  color: #409eff;
  text-decoration: none;
}

.markdown-body :deep(a:hover) {
  text-decoration: underline;
}
</style>
