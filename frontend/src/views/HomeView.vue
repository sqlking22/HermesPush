<template>
  <div>
    <div class="page-head">
      <div>
        <div class="page-title">今日</div>
        <div class="page-sub">简单模式只展示日常高频功能；完整配置请点右上角"专家模式"</div>
      </div>
    </div>

    <!-- 四格统计条 -->
    <div class="stat-strip">
      <div class="cell"><div class="v">{{ summary.todayTotal }}</div><div class="l">今天已推送</div></div>
      <div class="cell"><div class="v" style="color:var(--success-text)">{{ summary.todaySuccess }}</div><div class="l">成功</div></div>
      <div class="cell">
        <div class="v" :style="{ color: summary.todayFailed > 0 ? 'var(--danger-text)' : 'var(--text-3)' }">
          {{ summary.todayFailed }}
        </div>
        <div class="l">有问题</div>
      </div>
      <div class="cell"><div class="v">{{ summary.todayRunning }}</div><div class="l">进行中</div></div>
    </div>

    <!-- 失败提示卡 -->
    <div
      v-if="summary.todayFailed > 0"
      class="card"
      style="border-left:4px solid var(--danger);background:linear-gradient(90deg,var(--danger-l),var(--card) 45%)"
    >
      <div class="spread">
        <div class="flex" style="align-items:flex-start">
          <span style="color:var(--danger-text);font-size:17px;line-height:1.4">⚠</span>
          <div>
            <b style="color:var(--danger-text)">{{ summary.todayFailed }} 个推送有问题，需要处理</b>
            <div class="hint mt8">
              <span v-for="(f, i) in failedExecs" :key="f.id">
                <span class="lnk" @click="jumpToExec(f.id)">{{ f.taskName }}</span>
                <span v-if="f.errorCode" class="mono">（{{ f.errorCode }}）</span>
                <span v-if="i < failedExecs.length - 1">、</span>
              </span>
              <span v-if="summary.todayFailed > failedExecs.length">…等</span>
            </div>
          </div>
        </div>
        <button class="btn sm" style="flex-shrink:0" @click="goExecsFailed">查看原因和建议 →</button>
      </div>
    </div>

    <div class="grid side">
      <!-- 接下来要推送的 -->
      <div class="card">
        <div class="card-title">接下来要推送的</div>
        <div v-if="summary.nextTriggers && summary.nextTriggers.length" class="tl">
          <div class="tl-item" v-for="(t, i) in summary.nextTriggers" :key="i">
            <b>{{ formatTime(t.fireTime) }}</b>　{{ t.taskName }}
          </div>
        </div>
        <div v-else class="hint">未来 1 小时无安排</div>
      </div>

      <!-- 快速开始 -->
      <div class="card">
        <div class="card-title">快速开始</div>
        <button class="btn pri" style="width:100%;justify-content:center;margin-bottom:10px" @click="goScenarios">
          ＋ 新建推送任务（从场景选，约 5 分钟）
        </button>
        <button class="btn" style="width:100%;justify-content:center;margin-bottom:10px" @click="goExecs">
          查看执行记录
        </button>
        <button class="btn" style="width:100%;justify-content:center" @click="switchExpert">
          编辑任务高级配置（专家模式）
        </button>
      </div>
    </div>

    <!-- 我的推送任务 -->
    <div class="card">
      <div class="card-title">
        <span>我的推送任务</span>
        <span class="lnk" @click="goTasks">全部任务 →</span>
      </div>
      <table class="tbl">
        <thead>
          <tr>
            <th style="width:34%">名称</th>
            <th>状态</th>
            <th>下次触发</th>
            <th style="width:120px">管理</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="t in myTasks" :key="t.id">
            <td>
              <div class="cell-t">{{ t.name }}</div>
              <div class="cell-s mono">{{ t.taskKey }}</div>
            </td>
            <td><span class="badge" :class="taskStatusBadge(t.status)">{{ taskStatusLabel(t.status) }}</span></td>
            <td>
              <span v-if="t.nextFire" class="mono" style="font-size:12px">{{ formatDT(t.nextFire) }}</span>
              <span v-else class="hint">—</span>
            </td>
            <td><span class="lnk" @click="goTasks">管理</span></td>
          </tr>
          <tr v-if="myTasks.length === 0">
            <td colspan="4" class="hint" style="text-align:center;padding:20px 0">暂无任务</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { setMode, store } from '../store.js'
import { get } from '../api/http.js'

const router = useRouter()

const summary = ref({
  todayTotal: 0,
  todaySuccess: 0,
  todayFailed: 0,
  todayRunning: 0,
  nextTriggers: []
})
const failedExecs = ref([])
const myTasks = ref([])

function taskStatusBadge(s) {
  switch (s) {
    case 'ONLINE': return 'b-ok'
    case 'PAUSED': return 'b-warn'
    case 'DRAFT':
    case 'OFFLINE': return 'b-gray'
    default: return 'b-gray'
  }
}
function taskStatusLabel(s) {
  const map = { ONLINE: '进行中', PAUSED: '已暂停', DRAFT: '草稿', OFFLINE: '已下线' }
  return map[s] || s || '—'
}

function formatDT(v) {
  if (!v) return ''
  return String(v).replace('T', ' ')
}
function formatTime(v) {
  if (!v) return ''
  const s = String(v).replace('T', ' ')
  // 取 HH:mm
  const m = s.match(/(\d{1,2}:\d{2})/)
  return m ? m[1] : s
}

function todayRange() {
  // M1 假设：浏览器时区 = 服务器时区（Asia/Shanghai），"今日"以此分界。
  // 已知限制：跨时区访问时今日范围可能偏移约 1 天，M4a 国际化时统一处理。
  const now = new Date()
  const y = now.getFullYear()
  const m = String(now.getMonth() + 1).padStart(2, '0')
  const d = String(now.getDate()).padStart(2, '0')
  const from = `${y}-${m}-${d} 00:00:00`
  const tomorrow = new Date(now)
  tomorrow.setDate(tomorrow.getDate() + 1)
  const ty = tomorrow.getFullYear()
  const tm = String(tomorrow.getMonth() + 1).padStart(2, '0')
  const td = String(tomorrow.getDate()).padStart(2, '0')
  const to = `${ty}-${tm}-${td} 00:00:00`
  return { from, to }
}

async function loadSummary() {
  try {
    const res = await get('/execs/today-summary')
    summary.value = res.data
  } catch {
    // 拦截器已提示
  }
}

async function loadFailedExecs() {
  try {
    const { from, to } = todayRange()
    const res = await get('/execs', { status: 'FAILED', from, to, page: 1, size: 10 })
    failedExecs.value = res.data.records || []
  } catch {
    // 忽略
  }
}

async function loadMyTasks() {
  try {
    const res = await get('/tasks', { size: 5, page: 1 })
    myTasks.value = res.data.records || []
  } catch {
    // 忽略
  }
}

function goExecs() {
  router.push({ name: 'execs' })
}
function goExecsFailed() {
  router.push({ name: 'execs', query: { status: 'FAILED' } })
}
function goScenarios() {
  router.push({ name: 'scenarios' })
}
function goTasks() {
  router.push({ name: 'tasks' })
}
function switchExpert() {
  if (store.mode !== 'expert') {
    setMode('expert')
  }
  router.push({ name: 'home' })
}
function jumpToExec(id) {
  router.push({ name: 'execs', query: { openExecId: id } })
}

onMounted(() => {
  loadSummary()
  loadFailedExecs()
  loadMyTasks()
})
</script>
