<template>
  <div>
    <PageHead title="执行记录" sub="全量落库，默认保留 180 天 · 产物按保留期可下载" />

    <div class="card">
      <div class="toolbar">
        <select class="inp" v-model="fStatus" @change="onFilterChange">
          <option value="">全部状态</option>
          <option v-for="s in statusOptions" :key="s.value" :value="s.value">{{ s.label }}</option>
        </select>
        <select class="inp" v-model="fTrigger" @change="onFilterChange">
          <option value="">全部触发方式</option>
          <option v-for="t in triggerOptions" :key="t" :value="t">{{ triggerLabel(t) }}</option>
        </select>
        <el-date-picker
          v-model="dateRange"
          type="datetimerange"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          value-format="YYYY-MM-DD HH:mm:ss"
          style="width:380px"
          @change="onFilterChange"
        />
        <button class="btn" @click="resetFilters">重置</button>
      </div>

      <table class="tbl">
        <thead>
          <tr>
            <th>执行ID</th>
            <th>任务</th>
            <th>触发</th>
            <th>状态</th>
            <th>触发时间</th>
            <th>bizDate</th>
            <th>行数</th>
            <th>耗时</th>
            <th>版本</th>
            <th style="width:160px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="e in list" :key="e.id">
            <td><span class="lnk mono" @click="openDetail(e)">{{ e.id }}</span></td>
            <td>{{ e.taskName }}</td>
            <td><span class="badge b-gray">{{ triggerLabel(e.triggerType) }}</span></td>
            <td><span class="badge" :class="statusBadge(e.status)">{{ statusLabel(e.status) }}</span></td>
            <td>{{ formatDT(e.fireTime) }}</td>
            <td>{{ e.bizDate || '—' }}</td>
            <td>{{ e.rowsTotal ?? '—' }}</td>
            <td>{{ formatMs(e.costMs) }}</td>
            <td class="mono">v{{ e.ver || '—' }}</td>
            <td>
              <span class="lnk" @click="openDetail(e)">详情</span>
              <span class="lnk" style="margin-left:10px" @click="doRerun(e)">重跑</span>
            </td>
          </tr>
          <tr v-if="!loading && list.length === 0">
            <td colspan="10" class="hint" style="text-align:center;padding:30px 0">暂无数据</td>
          </tr>
        </tbody>
      </table>

      <div style="display:flex;justify-content:flex-end;padding:14px 0 4px">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @current-change="loadList"
          @size-change="onSizeChange"
        />
      </div>
    </div>

    <ExecDetailDrawer
      :visible="drawerVisible"
      :exec-id="currentExecId"
      :task-name="currentTaskName"
      @close="closeDrawer"
    />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHead from '../components/PageHead.vue'
import ExecDetailDrawer from '../components/ExecDetailDrawer.vue'
import { get, post } from '../api/http.js'
import {
  STATUS_OPTIONS, TRIGGER_OPTIONS,
  statusBadge, statusLabel, triggerLabel
} from '../utils/execEnums.js'

const route = useRoute()
const router = useRouter()

const fStatus = ref('')
const fTrigger = ref('')
const dateRange = ref([])
const page = ref(1)
const size = ref(20)
const total = ref(0)
const list = ref([])
const loading = ref(false)

const drawerVisible = ref(false)
const currentExecId = ref(null)
const currentTaskName = ref('')
function formatDT(v) {
  if (!v) return '—'
  return String(v).replace('T', ' ')
}
function formatMs(ms) {
  if (ms == null) return '—'
  ms = Number(ms)
  if (ms < 1000) return ms + 'ms'
  if (ms < 60000) return (ms / 1000).toFixed(1) + 's'
  const m = Math.floor(ms / 60000)
  const s = ((ms % 60000) / 1000).toFixed(0)
  return m + 'm' + s + 's'
}

async function loadList(p) {
  if (p != null) page.value = p
  loading.value = true
  try {
    const params = {
      page: page.value,
      size: size.value
    }
    if (fStatus.value) params.status = fStatus.value
    if (fTrigger.value) params.triggerType = fTrigger.value
    if (dateRange.value && dateRange.value.length === 2) {
      params.from = dateRange.value[0]
      params.to = dateRange.value[1]
    }
    const res = await get('/execs', params)
    list.value = res.data.records || []
    total.value = res.data.total || 0
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

function onFilterChange() {
  loadList(1)
}
function onSizeChange() {
  loadList(1)
}
function resetFilters() {
  fStatus.value = ''
  fTrigger.value = ''
  dateRange.value = []
  loadList(1)
}

function openDetail(e) {
  currentExecId.value = e.id
  currentTaskName.value = e.taskName || ''
  drawerVisible.value = true
  // 同步 URL query，支持刷新后重开
  router.replace({ query: { ...route.query, openExecId: e.id } })
}
function closeDrawer() {
  drawerVisible.value = false
  // 移除 openExecId
  const q = { ...route.query }
  delete q.openExecId
  router.replace({ query: q })
}

async function doRerun(e) {
  try {
    await ElMessageBox.confirm(
      `确定重跑任务「${e.taskName}」吗？将以原数据日期和版本创建一条新执行记录。`,
      '确认重跑',
      { confirmButtonText: '重跑', cancelButtonText: '取消', type: 'info' }
    )
  } catch {
    return
  }
  try {
    const body = {}
    if (e.bizDate) body.bizDate = e.bizDate
    const res = await post(`/tasks/${e.taskId}/trigger`, body)
    const newExecId = res.data?.execId
    ElMessage.success('已触发，新执行 ID: ' + newExecId)
    loadList(page.value)
  } catch {
    // 拦截器已提示
  }
}

onMounted(async () => {
  // 支持 query 参数进入
  if (route.query.status) fStatus.value = route.query.status
  if (route.query.triggerType) fTrigger.value = route.query.triggerType
  if (route.query.from && route.query.to) {
    dateRange.value = [route.query.from, route.query.to]
  }
  await loadList(1)

  // openExecId → 自动打开详情
  if (route.query.openExecId) {
    currentExecId.value = route.query.openExecId
    // 尝试从当前列表中匹配 taskName（深链场景：若命中则标题直接显示）
    const hit = list.value.find(x => String(x.id) === String(route.query.openExecId))
    currentTaskName.value = hit ? hit.taskName || '' : ''
    drawerVisible.value = true
  }
})
</script>
