<template>
  <el-drawer
    :model-value="visible"
    @update:model-value="val => val || emit('close')"
    :title="drawerTitle"
    direction="rtl"
    size="760px"
    destroy-on-close
    @close="emit('close')"
  >
    <template #header>
      <span>执行详情 · <span class="mono">{{ execId }}</span><span v-if="displayTaskName"> · {{ displayTaskName }}</span></span>
    </template>

    <div v-if="loading" class="hint">加载中…</div>
    <div v-else-if="!detail" class="hint">加载失败</div>
    <div v-else>
      <!-- ① 执行概览 -->
      <div class="sec">
        <div class="sec-h">
          <span class="sec-t"><span class="nb">1</span>执行概览</span>
          <span class="sec-x">
            <span class="badge" :class="statusBadge(detail.exec.status)">{{ statusLabel(detail.exec.status) }}</span>
            <span class="badge b-gray" style="margin-left:6px">{{ triggerLabel(detail.exec.triggerType) }}</span>
          </span>
        </div>
        <div class="dl">
          <div class="it"><div class="k">任务</div><div class="v">{{ displayTaskName }}</div></div>
          <div class="it"><div class="k">任务版本</div><div class="v mono">v{{ detail.exec.taskVersionId || '—' }}</div></div>
          <div class="it"><div class="k">数据日期</div><div class="v">{{ detail.exec.bizDate || '—' }}</div></div>
          <div class="it"><div class="k">触发时间</div><div class="v">{{ formatDT(detail.exec.fireTime) }}</div></div>
          <div class="it"><div class="k">总耗时</div><div class="v">{{ formatMs(detail.exec.costMs) }}</div></div>
          <div class="it"><div class="k">数据行数</div><div class="v">{{ detail.exec.rowsTotal ?? '—' }}</div></div>
          <div class="it"><div class="k">执行节点</div><div class="v mono">{{ detail.exec.nodeId || '—' }}</div></div>
          <div class="it"><div class="k">生效参数</div><div class="v mono" style="font-size:12px">{{ effectiveParams }}</div></div>
        </div>
      </div>

      <!-- ② 失败原因与修复建议 -->
      <div v-if="detail.exec.errorCode" class="sec">
        <div class="sec-h">
          <span class="sec-t">
            <span class="nb" style="background:var(--danger-l);color:var(--danger-text)">!</span>
            失败原因与修复建议
          </span>
          <span class="sec-x mono">{{ detail.exec.errorCode }}</span>
        </div>
        <div class="err-card">
          <div class="code">{{ errInfo.userMessage }}</div>
          <div class="fix">修复建议：{{ errInfo.suggestion }}</div>
          <div v-if="detail.exec.errorMsg && !errInfo.matched" class="fix">
            原始错误：{{ detail.exec.errorMsg }}
          </div>
        </div>
      </div>

      <!-- ③ 阶段时间线 -->
      <div class="sec">
        <div class="sec-h">
          <span class="sec-t"><span class="nb">2</span>阶段时间线</span>
          <span class="sec-x">排队 → 查询 → 渲染 → 推送</span>
        </div>
        <div v-if="!detail.stageCosts" class="hint">无阶段数据</div>
        <div v-else>
          <div class="stage-bars">
            <div
              v-for="s in stageList"
              :key="s.key"
              :style="{ width: s.widthPct + '%', background: s.color, minWidth: s.ms > 0 ? '3px' : '0' }"
              :title="s.label + ' ' + formatMs(s.ms)"
              :aria-label="s.label + ' ' + formatMs(s.ms)"
            ></div>
          </div>
          <div class="stage-legend">
            <span v-for="s in stageList" :key="s.key">
              <i class="dot" :style="{ background: s.color }"></i>{{ s.label }} {{ formatMs(s.ms) }}
            </span>
          </div>
        </div>
      </div>

      <!-- ④ 推送内容（产物） -->
      <div class="sec">
        <div class="sec-h">
          <span class="sec-t"><span class="nb">3</span>推送内容（产物）</span>
          <span class="sec-x">保留期内可下载</span>
        </div>
        <table v-if="detail.artifacts && detail.artifacts.length" class="tbl">
          <thead>
            <tr>
              <th>内容</th>
              <th>类型</th>
              <th>引擎</th>
              <th>行数</th>
              <th>大小</th>
              <th>错误</th>
            </tr>
          </thead>
          <tbody>
            <template v-for="(a, i) in detail.artifacts" :key="a.artifactKey || i">
              <tr :class="{ 'cursor-expand': a.content }" @click="a.content && toggleArtifact(i)">
                <td class="cell-t">
                  {{ a.artifactKey }}
                  <span v-if="a.content" class="hint" style="margin-left:6px">{{ expandedArtifacts[i] ? '▲' : '▼' }}</span>
                </td>
                <td>{{ a.type }}</td>
                <td>{{ a.renderProvider || '—' }}</td>
                <td>{{ a.rowsCount ?? '—' }}</td>
                <td>{{ formatBytes(a.bytes) }}</td>
                <td>
                  <span v-if="a.errorCode" class="mono" style="color:var(--danger-text)">{{ a.errorCode }}</span>
                  <span v-else>—</span>
                </td>
              </tr>
              <tr v-if="a.content && expandedArtifacts[i]">
                <td colspan="6" style="padding:0">
                  <pre class="md-preview">{{ a.content }}</pre>
                </td>
              </tr>
            </template>
          </tbody>
        </table>
        <div v-else class="hint">暂无产物</div>
      </div>

      <!-- ⑤ 渠道推送明细 -->
      <div class="sec">
        <div class="sec-h">
          <span class="sec-t"><span class="nb">4</span>渠道推送明细</span>
          <span class="sec-x">幂等键：执行 + 渠道 + 内容 + 消息类型</span>
        </div>
        <table v-if="detail.pushes && detail.pushes.length" class="tbl">
          <thead>
            <tr>
              <th>渠道</th>
              <th>内容 → 消息</th>
              <th>状态</th>
              <th>重试</th>
              <th>错误</th>
              <th>时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(p, i) in detail.pushes" :key="i">
              <td>{{ p.channelName }}</td>
              <td class="mono" style="font-size:12px">{{ p.artifactKey || '—' }} → {{ p.msgType || '—' }}</td>
              <td><span class="badge" :class="statusBadge(p.status)">{{ statusLabel(p.status) }}</span></td>
              <td>{{ p.retryCount ?? 0 }}</td>
              <td>
                <span v-if="p.errorCode" class="mono" style="color:var(--danger-text);font-size:12px">
                  {{ p.errorCode }}
                  <span v-if="p.errorMsg"> · {{ p.errorMsg }}</span>
                </span>
                <span v-else>—</span>
              </td>
              <td>{{ formatDT(p.sentAt) }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="hint">暂无推送明细</div>
      </div>
    </div>
  </el-drawer>
</template>

<script setup>
import { ref, computed, watch } from 'vue'
import { ElDrawer } from 'element-plus'
import { get } from '../api/http.js'
import { resolveError } from '../utils/errorDict.js'
import { statusBadge, statusLabel, triggerLabel } from '../utils/execEnums.js'

const props = defineProps({
  visible: Boolean,
  execId: [String, Number],
  taskName: { type: String, default: '' }
})
const emit = defineEmits(['close'])

const loading = ref(false)
const detail = ref(null)
const expandedArtifacts = ref({})
let requestSeq = 0

const drawerTitle = computed(() => props.execId || '')

// 任务名优先级：props.taskName（列表传入）> detail.exec.taskName（未来后端可能补）> #id 兜底
const displayTaskName = computed(() => {
  return props.taskName || detail.value?.exec?.taskName || ('#' + (props.execId || ''))
})

const errInfo = computed(() => {
  if (!detail.value?.exec?.errorCode) return null
  return resolveError(detail.value.exec.errorCode, detail.value.exec.errorMsg)
})

const effectiveParams = computed(() => {
  if (!detail.value?.exec?.paramsJson) return '—'
  try {
    const obj = JSON.parse(detail.value.exec.paramsJson)
    return Object.entries(obj).map(([k, v]) => `${k}=${v}`).join(' · ')
  } catch {
    return detail.value.exec.paramsJson
  }
})

const stageList = computed(() => {
  if (!detail.value?.stageCosts) return []
  const sc = detail.value.stageCosts
  const raw = [
    { key: 'queue', label: '排队', ms: sc.queueMs || 0, color: '#898781' },
    { key: 'query', label: '查询', ms: sc.queryMs || 0, color: '#2a78d6' },
    { key: 'render', label: '渲染', ms: sc.renderMs || 0, color: '#eb6834' },
    { key: 'push', label: '推送', ms: sc.pushMs || 0, color: '#eda100' }
  ]
  const total = raw.reduce((s, x) => s + x.ms, 0)
  return raw.map(s => ({
    ...s,
    widthPct: total > 0 ? (s.ms / total) * 100 : 0
  }))
})

function toggleArtifact(i) {
  expandedArtifacts.value = {
    ...expandedArtifacts.value,
    [i]: !expandedArtifacts.value[i]
  }
}

function formatDT(v) {
  if (!v) return '—'
  // 后端返回 LocalDateTime 序列化为 "2026-09-21T09:00:00" 或类似
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
function formatBytes(b) {
  if (b == null) return '—'
  b = Number(b)
  if (b < 1024) return b + 'B'
  if (b < 1024 * 1024) return (b / 1024).toFixed(1) + 'KB'
  return (b / 1024 / 1024).toFixed(1) + 'MB'
}

async function loadDetail(id) {
  const seq = ++requestSeq
  loading.value = true
  detail.value = null
  expandedArtifacts.value = {}
  try {
    const res = await get(`/execs/${id}`)
    if (seq === requestSeq) {
      detail.value = res.data
    }
  } catch {
    // http 拦截器已提示
  } finally {
    if (seq === requestSeq) loading.value = false
  }
}

watch(() => [props.visible, props.execId], ([vis, id]) => {
  if (vis && id) {
    loadDetail(id)
  }
}, { immediate: true })
</script>

<style scoped>
.cursor-expand { cursor: pointer; }
pre.md-preview {
  margin: 10px;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 320px;
  overflow-y: auto;
}
.el-drawer :deep(.el-drawer__header) {
  margin-bottom: 0;
  padding: 16px 22px;
  border-bottom: 1px solid var(--border);
}
.el-drawer :deep(.el-drawer__body) {
  padding: 20px 22px;
}
</style>
