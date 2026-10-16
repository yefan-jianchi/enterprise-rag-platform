<script setup>
/**
 * 审计与成本：
 *   1. 调用日志表格（后端分页）
 *   2. 成本统计（汇总卡片 + 纯 CSS 柱状图 + 按模型汇总表）
 *
 * 柱状图未引入 echarts，直接用 CSS 高度百分比绘制，保持构建体积精简。
 */
import { computed, onMounted, ref } from 'vue'
import { listAuditLogs, getCostStatistics } from '@/api/admin'
import { formatDateTime, formatMoney, formatNumber } from '@/utils/format'

// ---------- 调用日志 ----------
const logLoading = ref(false)
const logList = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)

// ---------- 成本统计 ----------
const costLoading = ref(false)
const days = ref(7)
const costData = ref({
  totalTokens: 0,
  totalCost: 0,
  byDay: [],
  byModel: []
})

const dayOptions = [
  { label: '近 7 天', value: 7 },
  { label: '近 14 天', value: 14 },
  { label: '近 30 天', value: 30 }
]

/** 按天统计中的最大值，用于计算柱状图高度百分比 */
const maxDayTokens = computed(() => {
  const list = costData.value.byDay || []
  return list.reduce((max, item) => Math.max(max, Number(item.tokens) || 0), 0)
})

/** 柱状图数据：附带高度百分比，避免在模板里做复杂运算 */
const barList = computed(() => {
  const list = costData.value.byDay || []
  const max = maxDayTokens.value || 1
  return list.map((item) => ({
    date: item.date,
    tokens: Number(item.tokens) || 0,
    cost: Number(item.cost) || 0,
    height: Math.max(4, Math.round(((Number(item.tokens) || 0) / max) * 100))
  }))
})

/** 按模型汇总表数据 */
const modelRows = computed(() => costData.value.byModel || [])

onMounted(() => {
  loadLogs()
  loadCost()
})

/** 加载审计日志 */
async function loadLogs () {
  logLoading.value = true
  try {
    const data = await listAuditLogs(page.value, size.value)
    logList.value = (data && data.records) || []
    total.value = (data && data.total) || 0
  } catch (e) {
    logList.value = []
    total.value = 0
  } finally {
    logLoading.value = false
  }
}

/** 加载成本统计 */
async function loadCost () {
  costLoading.value = true
  try {
    const data = await getCostStatistics(days.value)
    costData.value = {
      totalTokens: (data && data.totalTokens) || 0,
      totalCost: (data && data.totalCost) || 0,
      byDay: (data && data.byDay) || [],
      byModel: (data && data.byModel) || []
    }
  } catch (e) {
    costData.value = { totalTokens: 0, totalCost: 0, byDay: [], byModel: [] }
  } finally {
    costLoading.value = false
  }
}

/** 分页：页码变化 */
function handlePageChange (val) {
  page.value = val
  loadLogs()
}

/** 分页：每页条数变化 */
function handleSizeChange (val) {
  size.value = val
  page.value = 1
  loadLogs()
}

/** 切换统计天数 */
function handleDaysChange () {
  loadCost()
}

/** 刷新全部 */
function handleRefresh () {
  loadLogs()
  loadCost()
}
</script>

<template>
  <div class="audit-page">
    <div class="page-header">
      <div>
        <h2 class="page-title">审计与成本</h2>
        <p class="page-desc">记录每一次模型调用的审计日志，并统计 Token 消耗与调用成本</p>
      </div>
      <el-button :icon="'Refresh'" @click="handleRefresh">刷新</el-button>
    </div>

    <!-- 成本汇总卡片 -->
    <el-row :gutter="16" class="summary-row">
      <el-col :span="8">
        <el-card shadow="hover" class="summary-card">
          <div class="summary-label">累计 Token 消耗</div>
          <div class="summary-value">{{ formatNumber(costData.totalTokens) }}</div>
          <div class="summary-sub">统计区间：近 {{ days }} 天</div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="hover" class="summary-card">
          <div class="summary-label">累计调用成本</div>
          <div class="summary-value cost">￥{{ formatMoney(costData.totalCost) }}</div>
          <div class="summary-sub">按各模型单价折算</div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="hover" class="summary-card">
          <div class="summary-label">接入模型数（有调用）</div>
          <div class="summary-value">{{ modelRows.length }}</div>
          <div class="summary-sub">详见下方按模型汇总</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 成本趋势柱状图 -->
    <el-card shadow="never" class="chart-card">
      <template #header>
        <div class="card-header">
          <span class="card-title">Token 消耗趋势</span>
          <el-select v-model="days" size="small" class="days-select" @change="handleDaysChange">
            <el-option v-for="opt in dayOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </div>
      </template>

      <div v-loading="costLoading" class="chart-body">
        <el-empty v-if="!barList.length && !costLoading" description="暂无统计数据" :image-size="60" />
        <div v-else class="bar-chart">
          <div v-for="item in barList" :key="item.date" class="bar-column">
            <div class="bar-value">{{ formatNumber(item.tokens) }}</div>
            <div class="bar-track">
              <div class="bar-fill" :style="{ height: item.height + '%' }">
                <el-tooltip
                  :content="`${item.date}：${formatNumber(item.tokens)} tokens / ￥${formatMoney(item.cost)}`"
                  placement="top"
                >
                  <div class="bar-inner"></div>
                </el-tooltip>
              </div>
            </div>
            <div class="bar-label">{{ item.date ? item.date.slice(5) : '-' }}</div>
          </div>
        </div>
      </div>
    </el-card>

    <!-- 按模型汇总 -->
    <el-card shadow="never" class="model-card">
      <template #header>
        <span class="card-title">按模型汇总</span>
      </template>
      <el-table :data="modelRows" border stripe empty-text="暂无数据" style="width: 100%">
        <el-table-column prop="model" label="模型" min-width="180" show-overflow-tooltip />
        <el-table-column prop="tokens" label="Token 数" width="140" align="right">
          <template #default="{ row }">{{ formatNumber(row.tokens) }}</template>
        </el-table-column>
        <el-table-column prop="calls" label="调用次数" width="110" align="right">
          <template #default="{ row }">{{ formatNumber(row.calls) }}</template>
        </el-table-column>
        <el-table-column prop="cost" label="成本（元）" width="140" align="right">
          <template #default="{ row }">￥{{ formatMoney(row.cost) }}</template>
        </el-table-column>
        <el-table-column label="占比" min-width="160">
          <template #default="{ row }">
            <el-progress
              :percentage="costData.totalTokens ? Math.round((row.tokens / costData.totalTokens) * 100) : 0"
              :stroke-width="10"
            />
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 调用日志 -->
    <el-card shadow="never" class="log-card">
      <template #header>
        <div class="card-header">
          <span class="card-title">调用日志</span>
          <span class="card-sub">共 {{ formatNumber(total) }} 条记录</span>
        </div>
      </template>

      <el-table v-loading="logLoading" :data="logList" border stripe empty-text="暂无日志" style="width: 100%">
        <el-table-column prop="id" label="ID" width="80" align="center" />
        <el-table-column prop="username" label="操作人" width="120" show-overflow-tooltip />
        <el-table-column prop="action" label="操作" width="150" show-overflow-tooltip />
        <el-table-column prop="resource" label="资源" min-width="180" show-overflow-tooltip />
        <el-table-column prop="detail" label="详情" min-width="220" show-overflow-tooltip />
        <el-table-column prop="ip" label="IP" width="130" show-overflow-tooltip />
        <el-table-column prop="success" label="结果" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.success ? 'success' : 'danger'" size="small">
              {{ row.success ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
      </el-table>

      <div class="pager-right">
        <el-pagination
          :current-page="page"
          :page-size="size"
          :page-sizes="[10, 20, 50]"
          :total="total"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 16px;
}

.summary-row {
  margin-bottom: 16px;
}

.summary-card {
  border-radius: 6px;
}

.summary-label {
  font-size: 13px;
  color: var(--app-text-secondary);
}

.summary-value {
  margin: 6px 0;
  font-size: 24px;
  font-weight: 600;
  color: #303133;
}

.summary-value.cost {
  color: #f56c6c;
}

.summary-sub {
  font-size: 12px;
  color: #c0c4cc;
}

.chart-card,
.model-card,
.log-card {
  margin-bottom: 16px;
  border-radius: 6px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.card-title {
  font-size: 14px;
  font-weight: 600;
}

.card-sub {
  font-size: 12px;
  color: var(--app-text-secondary);
}

.days-select {
  width: 110px;
}

.chart-body {
  min-height: 200px;
}

/* 纯 CSS 柱状图 */
.bar-chart {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  height: 220px;
  padding: 0 4px;
  overflow-x: auto;
}

.bar-column {
  flex: 1;
  min-width: 36px;
  display: flex;
  flex-direction: column;
  align-items: center;
  height: 100%;
}

.bar-value {
  font-size: 11px;
  color: var(--app-text-secondary);
  margin-bottom: 4px;
  white-space: nowrap;
}

.bar-track {
  flex: 1;
  width: 100%;
  display: flex;
  align-items: flex-end;
  justify-content: center;
}

.bar-fill {
  width: 60%;
  max-width: 42px;
  background: linear-gradient(180deg, #79bbff 0%, #409eff 100%);
  border-radius: 3px 3px 0 0;
  transition: height 0.3s;
  cursor: pointer;
}

.bar-inner {
  width: 100%;
  height: 100%;
}

.bar-fill:hover {
  background: linear-gradient(180deg, #409eff 0%, #337ecc 100%);
}

.bar-label {
  margin-top: 6px;
  font-size: 11px;
  color: var(--app-text-secondary);
  white-space: nowrap;
}
</style>
