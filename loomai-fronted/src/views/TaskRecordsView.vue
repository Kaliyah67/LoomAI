<script setup>
/**
 * 任务结果查询页（学生演示项目）
 *
 * 编辑/合并共用本组件，通过 route.meta.action 区分类型（edit / merge）。
 * 表格展示该用户提交的任务：状态、输入图、指令、结果图、错误、提交时间。
 * 进页面自动加载一次，顶部「刷新」按钮手动重新查询。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { myTasks } from '../services/api'
import emptyDataIcon from '../assets/image/empty-data.svg'

const route = useRoute()

/** 任务列表 */
const tasksList = ref([])
/** 是否加载中 */
const isLoading = ref(false)
/** 当前查询类型：edit / merge */
const action = computed(() => route.meta.action)

/** 状态展示映射 */
const statusMap = {
  PENDING: { label: '待处理', type: 'info' },
  PROCESSING: { label: '处理中', type: 'warning' },
  SUCCESS: { label: '成功', type: 'success' },
  FAILED: { label: '失败', type: 'danger' },
}

/** 状态展示文案 */
const getStatusLabel = (status) => statusMap[status]?.label || status || '-'
/** 状态 tag 类型 */
const getStatusType = (status) => statusMap[status]?.type || 'info'

/** 结果图地址（优先持久化到 OSS 的 saveUrl） */
const resultImageUrl = (row) => row.saveUrl || row.resultUrl || ''

/** 时间戳格式化 */
const formatTime = (ts) => {
  if (!ts) return '-'
  const d = new Date(ts)
  if (Number.isNaN(d.getTime())) return '-'
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

/** 拉取任务列表 */
const fetchTasks = async () => {
  try {
    isLoading.value = true
    const { data } = await myTasks(action.value)
    tasksList.value = data.data || []
  } catch (err) {
    ElMessage.error(err?.message || '查询失败')
  } finally {
    isLoading.value = false
  }
}

onMounted(fetchTasks)

// 编辑/合并结果查询共用本组件，切换路由时组件被复用、onMounted 不再触发，
// 这里监听 action 变化，从「编辑结果查询」切到「合并结果查询」时自动重新拉取对应数据。
watch(action, fetchTasks)
</script>

<template>
  <div class="card ui-card">
    <!-- 顶部操作栏：刷新按钮 -->
    <div class="toolbar">
      <button type="button" class="refresh-btn" :disabled="isLoading" @click="fetchTasks">
        {{ isLoading ? '查询中…' : '刷新' }}
      </button>
    </div>

    <div v-if="tasksList.length" class="table-wrap">
      <el-table :data="tasksList" row-key="taskId" style="width: 100%" :loading="isLoading" class="tasks-table">
        <el-table-column type="index" label="序号" width="72" :index="(index) => index + 1" />
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)" effect="light" size="small">
              {{ getStatusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="图片1" min-width="160" align="center">
          <template #default="{ row }">
            <div v-if="row.inputImage" class="cell-img-wrap">
              <el-image
                :src="row.inputImage"
                :preview-src-list="[row.inputImage]"
                fit="cover"
                class="cell-thumb"
                preview-teleported
              />
            </div>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="图片2" min-width="160" align="center">
          <template #default="{ row }">
            <div v-if="row.inputImage2" class="cell-img-wrap">
              <el-image
                :src="row.inputImage2"
                :preview-src-list="[row.inputImage2]"
                fit="cover"
                class="cell-thumb"
                preview-teleported
              />
            </div>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="指令" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.instruction" class="instruction-text">{{ row.instruction }}</span>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="结果" min-width="160" align="center">
          <template #default="{ row }">
            <div v-if="row.status === 'SUCCESS' && resultImageUrl(row)" class="cell-img-wrap">
              <el-image
                :src="resultImageUrl(row)"
                :preview-src-list="[resultImageUrl(row)]"
                fit="cover"
                class="cell-thumb"
                preview-teleported
              />
            </div>
            <span v-else-if="row.status === 'FAILED'" class="error-text" :title="row.errorMessage">
              {{ row.errorMessage || '处理失败' }}
            </span>
            <span v-else class="muted">处理中</span>
          </template>
        </el-table-column>
        <el-table-column label="提交时间" width="170" align="center">
          <template #default="{ row }">
            <span class="time-text">{{ formatTime(row.createdAt) }}</span>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div v-else-if="!isLoading" class="empty-state">
      <img :src="emptyDataIcon" alt="" class="empty-state-img" />
      <p class="empty-state-text">暂无提交记录</p>
    </div>
  </div>
</template>

<style scoped>
.card {
  padding: 20px;
  display: flex;
  flex-direction: column;
  min-height: calc(100vh - 64px - 18px - 28px - 40px);
}
.toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 16px;
}
.refresh-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  height: 36px;
  padding: 0 20px;
  background: linear-gradient(90deg, #884BFF 0%, #7530FE 100%);
  color: #fff;
  border: none;
  border-radius: 6px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: opacity 0.2s;
}
.refresh-btn:hover:not(:disabled) {
  opacity: 0.92;
}
.refresh-btn:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}
.table-wrap {
  flex: 0 0 auto;
  background: #FFFFFF;
  box-shadow: 0px 4px 10px 0px rgba(0, 0, 0, 0.08);
  border-radius: 6px;
  overflow: hidden;
}
:deep(.tasks-table) {
  --el-table-border-color: transparent;
  --el-table-header-bg-color: #ECF0FD;
}
:deep(.tasks-table .el-table__header th),
:deep(.tasks-table .el-table__body td) {
  border: none;
}
:deep(.tasks-table .el-table__header th) {
  font-weight: 600;
  font-size: 14px;
  color: rgba(16, 24, 40, 0.85);
}
:deep(.tasks-table .el-table__body td) {
  font-size: 14px;
  vertical-align: middle;
  overflow: visible;
}
:deep(.tasks-table .el-table__body td .cell) {
  display: flex;
  align-items: center;
  overflow: visible;
}
:deep(.tasks-table .el-table__body td.is-center .cell) {
  justify-content: center;
}
.cell-img-wrap {
  position: relative;
  display: inline-block;
  width: 110px;
  height: 138px;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid #E5E7EB;
  background: #FFFFFF;
  transition: transform 0.2s ease, box-shadow 0.2s ease, border-radius 0.2s ease;
}
.cell-img-wrap:hover {
  transform: scale(1.05);
  box-shadow: 0 0 12px 2px rgba(0, 0, 0, 0.2);
  border-radius: 12px;
  border: 1px solid #E5E7EB;
}
.cell-thumb {
  width: 100%;
  height: 100%;
  display: block;
}
.cell-thumb :deep(.el-image__inner) {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.instruction-text {
  display: block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.error-text {
  color: #F56C6C;
  font-size: 13px;
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.time-text {
  color: rgba(16, 24, 40, 0.6);
  font-size: 13px;
}
.muted {
  color: var(--muted);
}
.empty-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 24px 0;
  min-height: 280px;
}
.empty-state-img {
  width: 315px;
  height: 169px;
  object-fit: contain;
  display: block;
}
.empty-state-text {
  margin: 0;
  color: var(--muted);
  font-size: 14px;
}
</style>
