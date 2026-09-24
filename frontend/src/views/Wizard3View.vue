<template>
  <div>
    <PageHead :title="scenarioTitle" :sub="scenarioSub" />

    <div class="steps">
      <div
        v-for="(s, i) in steps"
        :key="i"
        class="step"
        :class="{ on: currentStep === i, done: currentStep > i }"
        @click="canGoTo(i) && goTo(i)"
      >
        <span class="num">{{ currentStep > i ? '✓' : i + 1 }}</span>
        <span class="lb">{{ s }}</span>
        <span v-if="i < steps.length - 1" class="bar"></span>
      </div>
    </div>

    <!-- Step 1: 查数据 -->
    <div v-if="currentStep === 0" class="card">
      <div class="sec">
        <div class="sec-h"><div class="sec-t"><span class="nb">1</span>选择数据源</div></div>
        <select class="inp" v-model="selectedDsId" style="width:320px">
          <option :value="null">请选择数据源</option>
          <option v-for="d in datasources" :key="d.id" :value="d.id">
            {{ d.name }}（{{ d.type }}）
          </option>
        </select>
        <div class="hint">仅显示已启用的数据源</div>
      </div>

      <div class="sec">
        <div class="sec-h">
          <div class="sec-t"><span class="nb">2</span>编写 SQL</div>
          <button class="btn sm" @click="previewData" :disabled="!selectedDsId || previewLoading">
            {{ previewLoading ? '查询中…' : '先看看查出来的数据' }}
          </button>
        </div>
        <SqlEditor v-model="sqlText" height="240px" />
        <div class="hint">
          提示：用 <code class="mono">#{bizDate}</code> 表示业务日期，系统会自动填充昨天的日期（可在触发时自定义）
        </div>
      </div>

      <div v-if="previewResult" class="sec">
        <div class="sec-h">
          <div class="sec-t">预览结果</div>
          <div class="sec-x">
            {{ previewResult.totalRows }} 行
            <span v-if="previewResult.truncated">（最多显示 200 行）</span>
            · {{ previewResult.costMs }}ms
          </div>
        </div>
        <div style="overflow-x:auto">
          <table class="tbl">
            <thead>
              <tr>
                <th v-for="col in previewResult.columns" :key="col.name">{{ col.name }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(row, ri) in previewResult.rows.slice(0, 50)" :key="ri">
                <td v-for="col in previewResult.columns" :key="col.name">{{ formatCell(row[col.name]) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="sec" style="display:flex;justify-content:flex-end;border-bottom:none">
        <button class="btn pri" @click="goStep(1)" :disabled="!canStep1Next">下一步</button>
      </div>
    </div>

    <!-- Step 2: 推送内容 -->
    <div v-if="currentStep === 1" class="card">
      <div class="sec">
        <div class="sec-h"><div class="sec-t"><span class="nb">1</span>选择推送内容形式</div></div>
        <div class="grid c3">
          <div class="art-card on">
            <div class="art-icon">📝</div>
            <div class="art-title">摘要卡片</div>
            <div class="art-desc">Markdown 消息卡片，适合关键指标速览</div>
            <div class="badge b-ok" style="margin-top:6px">已选</div>
          </div>
          <div class="art-card disabled">
            <div class="art-icon">🖼️</div>
            <div class="art-title">表格图片 <span class="badge b-gray" style="font-size:10px">M2</span></div>
            <div class="art-desc">把数据表格渲染成图片发送</div>
          </div>
          <div class="art-card disabled">
            <div class="art-icon">📊</div>
            <div class="art-title">Excel 附件 <span class="badge b-gray" style="font-size:10px">M3</span></div>
            <div class="art-desc">生成 Excel 文件作为附件推送</div>
          </div>
        </div>
      </div>

      <div class="sec">
        <div class="sec-h">
          <div class="sec-t"><span class="nb">2</span>编辑摘要模板</div>
          <button class="btn sm" @click="generateDefaultTemplate" :disabled="!canGenerateTemplate">
            生成默认模板
          </button>
        </div>
        <textarea class="inp" v-model="templateText" rows="10" style="font-family:Consolas,Menlo,monospace;font-size:12.5px"></textarea>
        <div class="hint">
          支持 FreeMarker 语法，使用 <code class="mono">${ds1.rows[0].列名}</code> 引用查询结果的第一行数据，<br>
          使用 <code class="mono">&lt;#list ds1.rows as r&gt;...&lt;/#list&gt;</code> 遍历多行
        </div>
        <div v-if="!canGenerateTemplate" class="hint" style="color:var(--warn-text);margin-top:6px">
          提示：请先回到上一步执行"预览数据"，系统会根据返回的列名自动生成模板
        </div>
      </div>

      <div class="sec">
        <div class="sec-h"><div class="sec-t"><span class="nb">3</span>可用字段</div></div>
        <div class="pill-nav">
          <span class="pill" v-for="col in availableColumns" :key="col" @click="insertField(col)">
            ${"${ds1.rows[0]." + col + "}"}
          </span>
        </div>
        <div class="hint">点击字段名插入到模板末尾</div>
      </div>

      <div class="sec" style="display:flex;justify-content:space-between;border-bottom:none">
        <button class="btn" @click="goStep(0)">上一步</button>
        <button class="btn pri" @click="goStep(2)">下一步</button>
      </div>
    </div>

    <!-- Step 3: 群和时间 -->
    <div v-if="currentStep === 2" class="card">
      <div class="sec">
        <div class="sec-h"><div class="sec-t"><span class="nb">1</span>选择推送渠道</div></div>
        <div class="grid c2" style="max-width:640px">
          <label v-for="ch in channels" :key="ch.id" class="ch-line">
            <input type="checkbox" :value="ch.id" v-model="selectedChannels" />
            <span class="ch-name">{{ ch.name }}</span>
            <span class="badge b-gray" style="margin-left:8px">{{ channelTypeName(ch.type) }}</span>
            <span v-if="ch.testFlag" class="badge b-warn" style="margin-left:6px">测试</span>
          </label>
        </div>
        <div class="hint" v-if="channels.length === 0">
          暂无可用渠道，<span class="lnk" @click="$router.push({name:'ch'})">去渠道管理创建</span>
        </div>
      </div>

      <div class="sec">
        <div class="sec-h"><div class="sec-t"><span class="nb">2</span>设置推送时间</div></div>
        <div class="pill-nav">
          <span
            v-for="p in timePresets"
            :key="p.cron"
            class="pill"
            :class="{ on: selectedCron === p.cron }"
            @click="selectPreset(p.cron)"
          >{{ p.label }}</span>
        </div>

        <div v-if="selectedCron === 'custom'" style="margin-top:12px">
          <label class="fl">Cron 表达式（6 位，秒分时日月周）</label>
          <input class="inp" v-model="customCron" style="width:360px;font-family:Consolas,Menlo,monospace" @input="checkCron" />
          <div v-if="cronPreview.next.length" class="hint" style="margin-top:6px">
            未来 5 次触发时间：
            <div v-for="(t, i) in cronPreview.next.slice(0, 5)" :key="i" class="mono">{{ t }}</div>
          </div>
          <div v-else-if="customCron && !cronPreview.valid" class="hint" style="color:var(--danger-text);margin-top:6px">
            Cron 表达式无效
          </div>
        </div>
      </div>

      <div class="sec">
        <div class="sec-h"><div class="sec-t"><span class="nb">3</span>任务名称</div></div>
        <input class="inp" v-model="taskName" style="width:360px" placeholder="给任务起个名字" />
        <div class="hint">任务 Key 会自动生成</div>
      </div>

      <div class="sec" style="display:flex;justify-content:space-between;border-bottom:none">
        <button class="btn" @click="goStep(1)">上一步</button>
        <button class="btn pri" @click="finishConfig" :disabled="!canFinish">完成配置</button>
      </div>
    </div>

    <!-- 完成页 -->
    <div v-if="currentStep === 3" class="card">
      <div style="text-align:center;margin-bottom:20px">
        <div style="font-size:48px;margin-bottom:8px">🎉</div>
        <div style="font-size:18px;font-weight:600">配置完成</div>
      </div>

      <div class="sec">
        <div class="sec-h"><div class="sec-t">配置摘要</div></div>
        <div class="md-preview" style="line-height:2">{{ summaryText }}</div>
      </div>

      <div class="sec" style="display:flex;gap:12px;justify-content:center;border-bottom:none;flex-wrap:wrap">
        <button class="btn pri" @click="runTrial" :disabled="trialLoading">
          {{ trialLoading ? '试运行中…' : '先试运行' }}
        </button>
        <button class="btn" @click="testSend" :disabled="testLoading || !hasTestChannel">
          {{ testLoading ? '测试中…' : '发到测试群看看效果' }}
        </button>
        <button class="btn" @click="goPublish" :disabled="publishLoading">
          {{ publishLoading ? '上线中…' : '上线' }}
        </button>
      </div>

      <div v-if="publishHint" class="err-card" style="margin-top:0">
        <div class="code">上线被拒绝</div>
        <div class="fix">{{ publishHint }}</div>
      </div>

      <!-- 试运行/测试发送结果 -->
      <div v-if="execResult" class="sec">
        <div class="sec-h">
          <div class="sec-t">
            执行结果
            <span class="badge" :class="statusBadgeClass">{{ statusLabel }}</span>
          </div>
          <span class="lnk" @click="goExecDetail">查看详情 →</span>
        </div>
        <div v-if="execResult.artifacts && execResult.artifacts.length" class="mt8">
          <div class="hint" style="margin-bottom:6px">内容预览：</div>
          <pre class="md-preview" style="white-space:pre-wrap;max-height:360px;overflow-y:auto">{{ firstArtifactContent }}</pre>
        </div>
      </div>

      <div class="sec" style="display:flex;justify-content:space-between;border-bottom:none">
        <button class="btn" @click="goStep(2)">返回修改</button>
        <button class="btn pri" @click="goTaskList">去任务列表</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import PageHead from '../components/PageHead.vue'
import SqlEditor from '../components/SqlEditor.vue'
import { get, post, put } from '../api/http.js'

const route = useRoute()
const router = useRouter()

const steps = ['查数据', '推送内容', '群和时间']
const currentStep = ref(0)
const taskId = ref(null)
const lockVersion = ref(0)

const scenarioKey = computed(() => route.query.scen || 'daily')
const scenarioTitle = computed(() => {
  const map = { daily: '日报发群' }
  return map[scenarioKey.value] || '新建推送任务'
})
const scenarioSub = computed(() => {
  const map = { daily: '每天定时把 SQL 查询结果生成摘要卡片，推送到企业微信群' }
  return map[scenarioKey.value] || ''
})

// Step 1
const datasources = ref([])
const selectedDsId = ref(null)
const sqlText = ref('')
const previewResult = ref(null)
const previewLoading = ref(false)

// Step 2
const templateText = ref('')
const availableColumns = ref([])

// Step 3
const channels = ref([])
const selectedChannels = ref([])
const selectedCron = ref('0 0 9 * * ?')
const customCron = ref('')
const cronPreview = ref({ valid: false, next: [] })
const taskName = ref('')

// 完成页
const trialLoading = ref(false)
const testLoading = ref(false)
const publishLoading = ref(false)
const publishHint = ref('')
const execResult = ref(null)
let pollTimer = null
let pollStop = false

const timePresets = [
  { label: '每天 09:00', cron: '0 0 9 * * ?' },
  { label: '每天 08:30', cron: '0 30 8 * * ?' },
  { label: '工作日 18:00', cron: '0 0 18 ? * MON-FRI' },
  { label: '每周一 09:00', cron: '0 0 9 ? * MON' },
  { label: '自定义', cron: 'custom' }
]

const canStep1Next = computed(() => {
  return selectedDsId.value && sqlText.value.trim().length > 0
})

const canFinish = computed(() => {
  return selectedChannels.value.length > 0 && finalCron.value && taskName.value.trim() && templateText.value.trim().length > 0
})

const finalCron = computed(() => {
  if (selectedCron.value === 'custom') {
    return cronPreview.value.valid ? customCron.value.trim() : ''
  }
  return selectedCron.value
})

const hasTestChannel = computed(() => {
  return channels.value.some(c => c.testFlag && selectedChannels.value.includes(c.id)) ||
         channels.value.some(c => c.testFlag)
})

const statusLabel = computed(() => {
  if (!execResult.value) return ''
  const s = execResult.value.exec?.status || ''
  return { PENDING: '排队中', RUNNING: '执行中', SUCCESS: '成功', FAILED: '失败', CANCELLED: '已取消' }[s] || s
})

const statusBadgeClass = computed(() => {
  if (!execResult.value) return 'b-gray'
  const s = execResult.value.exec?.status || ''
  return { SUCCESS: 'b-ok', FAILED: 'b-fail', RUNNING: 'b-run', PENDING: 'b-gray' }[s] || 'b-gray'
})

const firstArtifactContent = computed(() => {
  if (!execResult.value?.artifacts?.length) return ''
  return execResult.value.artifacts[0].content || ''
})

const summaryText = computed(() => {
  const ds = datasources.value.find(d => d.id === selectedDsId.value)
  const dsName = ds ? ds.name : '所选数据源'
  const chNames = selectedChannels.value
    .map(id => channels.value.find(c => c.id === id)?.name)
    .filter(Boolean).join('、')
  const cronLabel = timePresets.find(p => p.cron === selectedCron.value)?.label || customCron.value

  return `${cronLabel}，从「${dsName}」查询昨天的数据，生成摘要卡片，发到「${chNames}」。\n` +
    `查不到数据时这次不推送；发送失败自动重试 3 次。`
})

function formatCell(v) {
  if (v === null || v === undefined) return 'NULL'
  return String(v)
}

function channelTypeName(type) {
  return { WEWORK_BOT: '企业微信群机器人' }[type] || type
}

function canGoTo(i) {
  return i < currentStep.value
}

function goTo(i) {
  if (canGoTo(i)) currentStep.value = i
}

function goStep(i) {
  if (i === 2) {
    // 从 step2 到 step3：预填充列名
    if (previewResult.value && previewResult.value.columns) {
      availableColumns.value = previewResult.value.columns.map(c => c.name)
    }
  }
  currentStep.value = i
}

const canGenerateTemplate = computed(() => {
  return availableColumns.value.length > 0
})

function buildDefaultTemplate(cols) {
  if (!cols || cols.length === 0) return ''
  const titleLine = '### 昨日数据速报'
  const fieldLines = cols.slice(0, 5).map(c => `- **${c}**：\${ds1.rows[0].${c}}`).join('\n')
  const listSample = '\n\n完整明细：\n<#list ds1.rows as r>\n- ' + cols[0] + ': ${r.' + cols[0] + '}\n</#list>'
  return titleLine + '\n\n' + fieldLines + listSample
}

function generateDefaultTemplate() {
  if (availableColumns.value.length === 0) return
  templateText.value = buildDefaultTemplate(availableColumns.value)
}

function insertField(field) {
  const text = '${ds1.rows[0].' + field + '}'
  templateText.value += text
}

function selectPreset(cron) {
  selectedCron.value = cron
  if (cron !== 'custom') {
    customCron.value = ''
    cronPreview.value = { valid: false, next: [] }
  }
}

async function checkCron() {
  if (!customCron.value.trim()) {
    cronPreview.value = { valid: false, next: [] }
    return
  }
  try {
    const res = await get('/cron/preview', { expr: customCron.value.trim() })
    if (res.code === 0) {
      cronPreview.value = res.data
    }
  } catch (e) { /* ignore */ }
}

async function previewData() {
  if (!selectedDsId.value) return
  previewLoading.value = true
  try {
    const res = await post('/datasources/' + selectedDsId.value + '/preview', {
      sql: sqlText.value,
      params: {}
    })
    if (res.code === 0) {
      previewResult.value = res.data
      availableColumns.value = res.data.columns.map(c => c.name)
      // 自动生成模板
      if (!templateText.value && res.data.columns.length > 0) {
        const cols = res.data.columns.map(c => c.name)
        templateText.value = buildDefaultTemplate(cols)
      }
    }
  } catch (e) {
    // 拦截器已弹错
  } finally {
    previewLoading.value = false
  }
}

async function finishConfig() {
  if (!canFinish.value) return
  publishHint.value = ''
  execResult.value = null

  try {
    const config = buildConfig()
    if (taskId.value) {
      // 更新已有草稿
      const res = await put('/tasks/' + taskId.value, {
        config,
        remark: '从简单模式向导更新',
        lockVersion: lockVersion.value
      })
      if (res.code === 0) {
        lockVersion.value += 1
        ElMessage.success('配置已更新')
        currentStep.value = 3
      }
    } else {
      // 创建新任务
      const key = 'wizard-' + Date.now()
      const res = await post('/tasks', {
        name: taskName.value,
        taskKey: key,
        config,
        remark: '从简单模式向导创建'
      })
      if (res.code === 0) {
        taskId.value = res.data.taskId
        lockVersion.value = 1
        ElMessage.success('任务草稿已创建')
        currentStep.value = 3
      }
    }
  } catch (e) {
    // 拦截器已弹错
  }
}

function buildConfig() {
  return {
    datasets: [
      {
        key: 'ds1',
        datasourceId: selectedDsId.value,
        sql: sqlText.value,
        params: []
      }
    ],
    artifacts: [
      {
        key: 'main',
        type: 'MARKDOWN',
        inlineTemplate: templateText.value,
        overflowStrategy: 'TRUNCATE',
        maxBytes: 4096
      }
    ],
    channelBindings: selectedChannels.value.map(id => ({
      channelId: id,
      artifactKeys: ['main'],
      msgType: 'markdown'
    })),
    schedule: {
      cron: finalCron.value,
      bizOffsetDays: -1,
      timeoutMinutes: 10,
      maxRetry: 3,
      jitterEnabled: false
    }
  }
}

async function runTrial() {
  if (!taskId.value) {
    ElMessage.warning('请先完成配置')
    return
  }
  trialLoading.value = true
  execResult.value = null
  publishHint.value = ''
  try {
    const res = await post('/tasks/' + taskId.value + '/trial')
    if (res.code === 0) {
      pollExec(res.data.execId)
    }
  } catch (e) {
    trialLoading.value = false
  }
}

async function testSend() {
  if (!taskId.value) return
  const testChannels = channels.value.filter(c => c.testFlag)
  if (testChannels.length === 0) {
    ElMessage.warning('暂无测试渠道，请先在渠道管理创建一个测试渠道')
    return
  }
  let channelId = null
  if (testChannels.length === 1) {
    channelId = testChannels[0].id
  } else {
    // 让用户选
    try {
      const { value } = await ElMessageBox.prompt('选择测试渠道（输入渠道 ID）：', '测试发送', {
        confirmButtonText: '确认',
        cancelButtonText: '取消',
        inputValue: String(testChannels[0].id)
      })
      channelId = parseInt(value)
    } catch { return }
  }

  testLoading.value = true
  execResult.value = null
  try {
    const res = await post('/tasks/' + taskId.value + '/test-send', { channelId })
    if (res.code === 0) {
      pollExec(res.data.execId)
    }
  } catch (e) {
    testLoading.value = false
  }
}

async function goPublish() {
  if (!taskId.value) return
  publishLoading.value = true
  publishHint.value = ''
  try {
    const res = await post('/tasks/' + taskId.value + '/publish')
    if (res.code === 0) {
      ElMessage.success('任务已上线')
    }
  } catch (e) {
    if (e.errorCode && e.message) {
      publishHint.value = e.message + '（' + e.errorCode + '）'
    } else {
      publishHint.value = '上线失败，请先完成试运行'
    }
  } finally {
    publishLoading.value = false
  }
}

function pollExec(execId) {
  pollStop = false
  const start = Date.now()
  const interval = 2000
  const timeout = 60000

  function check() {
    if (pollStop) return
    if (Date.now() - start > timeout) {
      trialLoading.value = false
      testLoading.value = false
      ElMessage.warning('执行超时，请稍后在执行记录查看')
      return
    }
    get('/execs/' + execId).then(res => {
      if (res.code === 0) {
        execResult.value = res.data
        const status = res.data.exec?.status
        if (status === 'SUCCESS' || status === 'FAILED' || status === 'CANCELLED') {
          trialLoading.value = false
          testLoading.value = false
          return
        }
      }
      pollTimer = setTimeout(check, interval)
    }).catch(() => {
      pollTimer = setTimeout(check, interval)
    })
  }
  check()
}

function goExecDetail() {
  if (execResult.value?.exec?.id) {
    router.push({ name: 'execs' })
  }
}

function goTaskList() {
  router.push({ name: 'tasks' })
}

async function loadDatasources() {
  try {
    const res = await get('/datasources')
    if (res.code === 0) {
      datasources.value = res.data.filter(d => d.status === 'ENABLED')
    }
  } catch (e) { /* ignore */ }
}

async function loadChannels() {
  try {
    const res = await get('/channels')
    if (res.code === 0) {
      channels.value = res.data.filter(c => c.status === 'ACTIVE' && c.type === 'WEWORK_BOT')
    }
  } catch (e) { /* ignore */ }
}

onMounted(async () => {
  await Promise.all([loadDatasources(), loadChannels()])
  // 预填场景 SQL 模板
  if (scenarioKey.value === 'daily' && !sqlText.value) {
    sqlText.value = '-- 日报模板：查询昨天的业务数据\n' +
      '-- #{bizDate} 会自动替换为昨天的日期（系统自动填充）\n' +
      'SELECT\n' +
      '  COUNT(*) AS total_count,\n' +
      '  SUM(amount) AS total_amount\n' +
      'FROM your_table\n' +
      'WHERE dt = #{bizDate}'
  }
  if (!taskName.value) {
    taskName.value = '每日数据速报-' + new Date().toISOString().slice(0, 10)
  }
})

onUnmounted(() => {
  pollStop = true
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
})
</script>

<style scoped>
.art-card {
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 16px;
  text-align: center;
  cursor: pointer;
  transition: all .15s;
}
.art-card.on {
  border-color: var(--primary);
  background: var(--primary-l);
}
.art-card.disabled {
  opacity: .55;
  cursor: not-allowed;
}
.art-icon {
  font-size: 28px;
  margin-bottom: 6px;
}
.art-title {
  font-weight: 600;
  margin-bottom: 4px;
}
.art-desc {
  font-size: 12px;
  color: var(--text-2);
}
.ch-line {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border: 1px solid var(--border);
  border-radius: 8px;
  cursor: pointer;
  font-size: 13px;
}
.ch-line:hover {
  border-color: var(--primary);
}
.ch-name {
  flex: 1;
}
</style>
