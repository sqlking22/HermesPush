<template>
  <div>
    <PageHead title="推送任务" sub="管理所有推送任务，查看执行状态">
      <template #actions>
        <button class="btn pri" @click="goCreate">+ 新建任务</button>
      </template>
    </PageHead>

    <div class="toolbar">
      <select class="inp" v-model="filterStatus" @change="loadList(1)">
        <option value="">全部状态</option>
        <option value="DRAFT">草稿</option>
        <option value="ONLINE">已上线</option>
        <option value="PAUSED">已暂停</option>
        <option value="OFFLINE">已下线</option>
      </select>
      <input class="inp" v-model="keyword" placeholder="搜索任务名称/Key" @keyup.enter="loadList(1)" style="width:260px" />
      <button class="btn" @click="loadList(1)">搜索</button>
    </div>

    <div class="card" style="padding:0">
      <table class="tbl">
        <thead>
          <tr>
            <th style="width:30%">名称 / Key</th>
            <th>类型</th>
            <th>状态</th>
            <th>调度 / 下次触发</th>
            <th>负责人</th>
            <th style="width:220px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="t in tasks" :key="t.id">
            <td>
              <div class="cell-t">{{ t.name }}</div>
              <div class="cell-s">{{ t.taskKey }}</div>
            </td>
            <td>{{ typeLabel(t.taskType) }}</td>
            <td>
              <span class="badge" :class="statusBadgeClass(t.status)">{{ statusLabel(t.status) }}</span>
            </td>
            <td>
              <div class="cell-t mono" style="font-size:12px">{{ t.cronExpr }}</div>
              <div v-if="t.nextFire" class="cell-s">
                下次：{{ t.nextFire.replace('T', ' ') }}
                <span v-if="t.status === 'PAUSED'" class="badge b-warn" style="margin-left:4px">已暂停</span>
              </div>
              <div v-else class="cell-s">—</div>
            </td>
            <td>{{ t.owner }}</td>
            <td>
              <span class="lnk" @click="openDetail(t)">详情</span>
              <span class="lnk" style="margin-left:10px" @click="openTrigger(t)">手动触发</span>
              <span class="lnk" style="margin-left:10px" v-if="t.status === 'ONLINE'" @click="doPause(t)">暂停</span>
              <span class="lnk" style="margin-left:10px" v-if="t.status === 'PAUSED'" @click="doResume(t)">恢复</span>
              <span class="lnk" style="margin-left:10px" v-if="t.status === 'ONLINE' || t.status === 'PAUSED'" @click="doOffline(t)">下线</span>
              <span class="lnk" style="margin-left:10px" v-if="t.status === 'OFFLINE' || t.status === 'DRAFT'" @click="doPublish(t)">上线</span>
              <span class="lnk" style="margin-left:10px;color:var(--danger-text)" @click="doDelete(t)">删除</span>
            </td>
          </tr>
          <tr v-if="tasks.length === 0">
            <td colspan="6" style="text-align:center;padding:40px;color:var(--text-3)">
              暂无任务
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div style="display:flex;justify-content:flex-end;margin-top:14px;gap:8px">
      <button class="btn sm" :disabled="page <= 1" @click="loadList(page - 1)">上一页</button>
      <span style="line-height:2;color:var(--text-2);font-size:13px">第 {{ page }} 页 / 共 {{ totalPages }} 页</span>
      <button class="btn sm" :disabled="page >= totalPages" @click="loadList(page + 1)">下一页</button>
    </div>

    <!-- 详情抽屉 -->
    <div v-if="detailVisible" class="drawer-mask" @click="detailVisible = false"></div>
    <div v-if="detailVisible" class="drawer">
      <div class="drawer-h">
        <span>任务详情 - {{ detailTask?.name }}</span>
        <span class="x" @click="detailVisible = false">✕</span>
      </div>
      <div class="drawer-b" v-if="detailTask">
        <div class="sec">
          <div class="sec-h"><div class="sec-t">基本信息</div></div>
          <div class="dl">
            <div class="it"><div class="k">任务名称</div><div class="v">{{ detailTask.name }}</div></div>
            <div class="it"><div class="k">任务 Key</div><div class="v">{{ detailTask.taskKey }}</div></div>
            <div class="it"><div class="k">类型</div><div class="v">{{ typeLabel(detailTask.taskType) }}</div></div>
            <div class="it"><div class="k">状态</div><div class="v">
              <span class="badge" :class="statusBadgeClass(detailTask.status)">{{ statusLabel(detailTask.status) }}</span>
            </div></div>
            <div class="it"><div class="k">Cron 表达式</div><div class="v mono">{{ detailTask.cronExpr }}</div></div>
            <div class="it"><div class="k">负责人</div><div class="v">{{ detailTask.owner }}</div></div>
            <div class="it"><div class="k">当前版本</div><div class="v">v{{ detailTask.currentVersionNo }}</div></div>
            <div class="it"><div class="k">创建时间</div><div class="v">{{ formatDate(detailTask.createdAt) }}</div></div>
          </div>
        </div>

        <div class="sec">
          <div class="sec-h"><div class="sec-t">配置信息</div></div>
          <div class="hint" style="margin-bottom:8px">
            数据源：{{ detailDsNames.join(', ') }}<br>
            内容形式：{{ detailTask.config?.artifacts?.length || 0 }} 种<br>
            渠道绑定：{{ detailTask.config?.channelBindings?.length || 0 }} 个
          </div>
        </div>

        <div class="sec">
          <div class="sec-h"><div class="sec-t">版本历史</div></div>
          <div class="hint">版本管理（固定版本/回滚）将在 M2 交付</div>
        </div>

        <div class="sec">
          <div class="sec-h"><div class="sec-t">入站 API <span class="badge b-gray">M4b</span></div></div>
          <div class="hint">支持通过 API 手动触发任务执行，M4b 里程碑开放完整入站能力</div>
        </div>
      </div>
    </div>

    <!-- 手动触发弹窗 -->
    <div v-if="triggerVisible" class="mask" @click.self="triggerVisible = false">
      <div class="modal">
        <div class="modal-h">
          <span>手动触发 - {{ triggerTask?.name }}</span>
          <span class="x" @click="triggerVisible = false">✕</span>
        </div>
        <div class="modal-b">
          <label class="fl">业务日期</label>
          <input type="date" class="inp" v-model="triggerBizDate" style="width:240px" />
          <div class="hint">默认使用昨天的日期，可手动调整</div>
        </div>
        <div class="modal-f">
          <button class="btn" @click="triggerVisible = false">取消</button>
          <button class="btn pri" @click="confirmTrigger" :disabled="triggerLoading">
            {{ triggerLoading ? '触发中…' : '确认触发' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHead from '../components/PageHead.vue'
import { get, post, del } from '../api/http.js'

const router = useRouter()

const tasks = ref([])
const page = ref(1)
const size = ref(20)
const total = ref(0)
const filterStatus = ref('')
const keyword = ref('')
const loading = ref(false)

const detailVisible = ref(false)
const detailTask = ref(null)

const triggerVisible = ref(false)
const triggerTask = ref(null)
const triggerBizDate = ref('')
const triggerLoading = ref(false)

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / size.value)))

const detailDsNames = computed(() => {
  if (!detailTask.value?.config?.datasets) return []
  return detailTask.value.config.datasets.map(d => d.key)
})

function typeLabel(t) {
  return { REPORT: '数据报表' }[t] || t
}

function statusLabel(s) {
  return { DRAFT: '草稿', ONLINE: '已上线', PAUSED: '已暂停', OFFLINE: '已下线', DELETED: '已删除' }[s] || s
}

function statusBadgeClass(s) {
  return {
    ONLINE: 'b-ok',
    PAUSED: 'b-warn',
    DRAFT: 'b-gray',
    OFFLINE: 'b-part',
    DELETED: 'b-fail'
  }[s] || 'b-gray'
}

function formatDate(d) {
  if (!d) return '—'
  return String(d).replace('T', ' ').slice(0, 19)
}

async function loadList(p) {
  page.value = p
  loading.value = true
  try {
    const res = await get('/tasks', {
      page: p,
      size: size.value,
      status: filterStatus.value,
      keyword: keyword.value
    })
    if (res.code === 0) {
      tasks.value = res.data.records || []
      total.value = res.data.total || 0
    }
  } catch (e) { /* 拦截器已处理 */ }
  finally {
    loading.value = false
  }
}

function goCreate() {
  router.push({ name: 'scenarios' })
}

async function openDetail(t) {
  try {
    const res = await get('/tasks/' + t.id)
    if (res.code === 0) {
      detailTask.value = res.data
      detailVisible.value = true
    }
  } catch (e) { /* ignore */ }
}

function openTrigger(t) {
  triggerTask.value = t
  // 默认昨天
  const d = new Date()
  d.setDate(d.getDate() - 1)
  triggerBizDate.value = d.toISOString().slice(0, 10)
  triggerVisible.value = true
}

async function confirmTrigger() {
  if (!triggerTask.value) return
  triggerLoading.value = true
  try {
    const res = await post('/tasks/' + triggerTask.value.id + '/trigger', {
      bizDate: triggerBizDate.value
    })
    if (res.code === 0) {
      const execId = res.data.execId
      ElMessage.success('已触发，执行 ID: ' + execId)
      triggerVisible.value = false
      // 跳执行记录
      router.push({ name: 'execs' })
    }
  } catch (e) { /* ignore */ }
  finally {
    triggerLoading.value = false
  }
}

async function doPublish(t) {
  try {
    await ElMessageBox.confirm(`确定将「${t.name}」上线吗？`, '确认上线', {
      confirmButtonText: '上线',
      cancelButtonText: '取消',
      type: 'info'
    })
  } catch { return }
  try {
    const res = await post('/tasks/' + t.id + '/publish')
    if (res.code === 0) {
      ElMessage.success('已上线')
      loadList(page.value)
    }
  } catch (e) { /* 拦截器已弹错 */ }
}

async function doOffline(t) {
  try {
    await ElMessageBox.confirm(`确定将「${t.name}」下线吗？`, '确认下线', {
      confirmButtonText: '下线',
      cancelButtonText: '取消',
      type: 'warning'
    })
  } catch { return }
  try {
    const res = await post('/tasks/' + t.id + '/offline')
    if (res.code === 0) {
      ElMessage.success('已下线')
      loadList(page.value)
    }
  } catch (e) { /* ignore */ }
}

async function doPause(t) {
  try {
    const res = await post('/tasks/' + t.id + '/pause')
    if (res.code === 0) {
      ElMessage.success('已暂停')
      loadList(page.value)
    }
  } catch (e) { /* ignore */ }
}

async function doResume(t) {
  try {
    const res = await post('/tasks/' + t.id + '/resume')
    if (res.code === 0) {
      ElMessage.success('已恢复')
      loadList(page.value)
    }
  } catch (e) { /* ignore */ }
}

async function doDelete(t) {
  try {
    await ElMessageBox.confirm(`确定删除「${t.name}」吗？此操作不可恢复。`, '确认删除', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'error'
    })
  } catch { return }
  try {
    const res = await del('/tasks/' + t.id)
    if (res.code === 0) {
      ElMessage.success('已删除')
      loadList(page.value)
    }
  } catch (e) { /* 拦截器已弹错 */ }
}

onMounted(() => {
  loadList(1)
})
</script>
