<template>
  <PageHead
    title="渠道管理"
    sub="FR-CH-01~13 · 五类渠道 · 凭据加密存储 · webhook 域名白名单管控（FR-CH-04）"
  >
    <template #actions>
      <button class="btn pri" @click="openCreate">＋ 新增渠道</button>
    </template>
  </PageHead>

  <!-- 渠道列表 -->
  <div class="card">
    <table class="tbl">
      <thead>
        <tr>
          <th>渠道</th>
          <th>类型</th>
          <th>目标(脱敏)</th>
          <th>限流</th>
          <th>状态</th>
          <th>操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="ch in channels" :key="ch.id">
          <td class="cell-t">
            {{ ch.name }}
            <span v-if="ch.testFlag" class="badge b-warn" style="margin-left:6px">测试渠道</span>
          </td>
          <td>{{ typeLabel(ch.type) }}</td>
          <td><span class="mono" style="font-size:12px">{{ ch.webhookMasked }}</span></td>
          <td>{{ ch.rateLimitPerMin }} 条/分钟</td>
          <td>
            <span
              class="badge"
              :class="ch.status === 'ENABLED' ? 'b-ok' : 'b-gray'"
            >{{ ch.status === 'ENABLED' ? '启用' : '停用' }}</span>
          </td>
          <td>
            <span class="lnk" @click="healthCheck(ch)">健康检查</span>
            <span class="lnk" style="margin-left:8px" @click="openEdit(ch)">编辑</span>
            <span
              class="lnk"
              style="margin-left:8px"
              :class="{ 'text-danger': ch.status === 'ENABLED' }"
              @click="toggleStatus(ch)"
            >{{ ch.status === 'ENABLED' ? '停用' : '启用' }}</span>
            <span class="lnk text-danger" style="margin-left:8px" @click="deleteCh(ch)">删除</span>
          </td>
        </tr>
        <tr v-if="channels.length === 0 && !loading">
          <td colspan="6" style="text-align:center;color:var(--text-3);padding:30px 0">
            暂无渠道，点击右上角「新增渠道」创建
          </td>
        </tr>
      </tbody>
    </table>
  </div>

  <!-- Webhook 域名白名单 -->
  <div class="card">
    <div class="card-title">
      <span>Webhook 域名白名单（FR-CH-04）</span>
      <div style="display:flex;gap:8px;align-items:center">
        <input
          v-model="newWhitelist"
          class="inp"
          placeholder="输入域名，如 qyapi.weixin.qq.com"
          style="width:280px"
          @keyup.enter="addWhitelist"
        />
        <button class="btn pri sm" @click="addWhitelist">添加</button>
      </div>
    </div>
    <div class="pill-nav">
      <span
        v-for="wl in webhookWhitelist"
        :key="wl.id"
        class="pill on"
        :title="'创建人：' + (wl.createdBy || '—')"
      >
        {{ wl.value }}
        <span
          class="wl-del"
          title="删除"
          @click.stop="deleteWhitelist(wl)"
        >×</span>
      </span>
      <span v-if="webhookWhitelist.length === 0" class="hint">暂无白名单域名</span>
    </div>
    <div class="hint mt8">目的：防止配置外部地址导致数据外流。保存渠道时后端强校验，前端仅作预提示。</div>
  </div>

  <!-- 邮件收件人白名单（M4b 占位） -->
  <div class="card">
    <div class="card-title">邮件收件人白名单（FR-CH-10 · M4b）</div>
    <div class="hint">邮件渠道将在 M4b 里程碑上线，届时在此管理收件人域名/地址白名单。</div>
  </div>

  <!-- 新增/编辑弹窗 -->
  <el-dialog
    v-model="dialogVisible"
    :title="isEdit ? '编辑渠道 · ' + form.name : '新增渠道'"
    width="640px"
    :close-on-click-modal="false"
  >
    <el-form :model="form" label-position="top">
      <el-form-item label="渠道类型" required>
        <el-select v-model="form.type" style="width:100%">
          <el-option label="企业微信群机器人（M1）" value="WEWORK_BOT" />
          <el-option label="钉钉群机器人（M4 · 后续里程碑）" value="DINGTALK_BOT" disabled />
          <el-option label="飞书群机器人（M4 · 后续里程碑）" value="FEISHU_BOT" disabled />
          <el-option label="邮件 SMTP（M4 · 后续里程碑）" value="EMAIL_SMTP" disabled />
          <el-option label="通用 Webhook（M5 · 后续里程碑）" value="GENERIC_WEBHOOK" disabled />
        </el-select>
      </el-form-item>
      <el-form-item label="名称" required>
        <el-input v-model="form.name" placeholder="如：销售一大群机器人" />
      </el-form-item>
      <el-form-item
        :label="isEdit ? 'Webhook 地址（需重新填写，凭据加密不回显）' : 'Webhook 地址'"
        required
      >
        <el-input
          v-model="form.webhook"
          placeholder="https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=…"
          @input="onWebhookInput"
        />
        <div v-if="webhookError" style="color:var(--danger);font-size:12px;margin-top:4px">
          {{ webhookError }}
        </div>
      </el-form-item>
      <div class="grid c2">
        <el-form-item label="限流阈值（条/分钟）">
          <el-input-number v-model="form.rateLimitPerMin" :min="1" :max="10000" style="width:100%" />
        </el-form-item>
        <el-form-item label="排队等待超时（秒）">
          <el-input-number v-model="form.waitTimeoutSec" :min="1" :max="3600" style="width:100%" />
        </el-form-item>
      </div>
      <div class="checkbox-line">
        <el-checkbox v-model="form.testFlag">
          标记为测试渠道（供测试发送选择，FR-TSK-02）
        </el-checkbox>
      </div>
      <div class="hint mt8">
        企微：支持 text/markdown/image/file · 图片≤2MB · 文件≤20MB · media_id 3天（缓存2.4天）
      </div>
    </el-form>
    <template #footer>
      <button class="btn" @click="dialogVisible = false">取消</button>
      <button class="btn pri" :disabled="saving" @click="handleSave">
        {{ saving ? '保存中...' : '保存' }}
      </button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { get, post, put, del } from '../api/http.js'
import PageHead from '../components/PageHead.vue'

const channels = ref([])
const loading = ref(false)
const dialogVisible = ref(false)
const isEdit = ref(false)
const saving = ref(false)
const editId = ref(null)
const webhookWhitelist = ref([])
const newWhitelist = ref('')
const webhookError = ref('')

const typeMap = {
  WEWORK_BOT: '企业微信群机器人',
  DINGTALK_BOT: '钉钉群机器人',
  FEISHU_BOT: '飞书群机器人',
  EMAIL_SMTP: '邮件 SMTP',
  GENERIC_WEBHOOK: '通用 Webhook'
}

function typeLabel(type) {
  return typeMap[type] || type
}

const defaultForm = () => ({
  type: 'WEWORK_BOT',
  name: '',
  webhook: '',
  rateLimitPerMin: 20,
  waitTimeoutSec: 300,
  testFlag: false
})

const form = ref(defaultForm())

const whitelistHosts = computed(() =>
  webhookWhitelist.value.map(w => w.value)
)

async function loadChannels() {
  loading.value = true
  try {
    const res = await get('/channels')
    channels.value = res.data || []
  } catch (e) {
    // 拦截器已弹错
  } finally {
    loading.value = false
  }
}

async function loadWhitelist() {
  try {
    const res = await get('/whitelist', { type: 'WEBHOOK_HOST' })
    webhookWhitelist.value = res.data || []
  } catch (e) {
    // 拦截器已弹错
  }
}

function extractHost(url) {
  if (!url) return null
  try {
    const u = new URL(url)
    return u.hostname
  } catch (e) {
    return null
  }
}

function onWebhookInput() {
  const host = extractHost(form.value.webhook)
  if (!host) {
    webhookError.value = ''
    return
  }
  if (!whitelistHosts.value.includes(host)) {
    webhookError.value = '该域名不在白名单，请联系管理员添加'
  } else {
    webhookError.value = ''
  }
}

function openCreate() {
  isEdit.value = false
  editId.value = null
  form.value = defaultForm()
  webhookError.value = ''
  dialogVisible.value = true
}

function openEdit(ch) {
  isEdit.value = true
  editId.value = ch.id
  // 编辑时 webhook 不回显明文（安全原因），需重新填写以修改
  // 不填则后端保持原 webhook 不变（通过空字符串标识）
  form.value = {
    type: ch.type,
    name: ch.name,
    webhook: '',
    rateLimitPerMin: ch.rateLimitPerMin,
    waitTimeoutSec: ch.waitTimeoutSec,
    testFlag: ch.testFlag
  }
  webhookError.value = ''
  dialogVisible.value = true
}

async function handleSave() {
  if (!form.value.name || !form.value.type) {
    ElMessage.warning('请填写必填项')
    return
  }
  if (!form.value.webhook && !isEdit.value) {
    ElMessage.warning('请填写 Webhook 地址')
    return
  }
  if (!form.value.webhook) {
    ElMessage.warning('请填写 Webhook 地址')
    return
  }

  saving.value = true
  try {
    const configJson = JSON.stringify({ webhook: form.value.webhook })
    const payload = {
      name: form.value.name,
      type: form.value.type,
      configJson,
      rateLimitPerMin: form.value.rateLimitPerMin,
      waitTimeoutSec: form.value.waitTimeoutSec,
      testFlag: form.value.testFlag
    }
    if (isEdit.value) {
      await put('/channels/' + editId.value, payload)
      ElMessage.success('渠道已更新')
    } else {
      await post('/channels', payload)
      ElMessage.success('渠道已创建（凭据已加密）')
    }
    dialogVisible.value = false
    loadChannels()
  } catch (e) {
    // 拦截器已弹错
  } finally {
    saving.value = false
  }
}

async function healthCheck(ch) {
  try {
    const res = await post('/channels/' + ch.id + '/health-check')
    const r = res.data
    if (r.ok) {
      ElMessageBox.alert(
        `健康检查成功\n耗时：${r.costMs}ms`,
        '健康检查结果',
        { type: 'success', confirmButtonText: '好的' }
      )
    } else {
      ElMessageBox.alert(
        `健康检查失败\n错误码：${r.errorCode || '—'}\n原因：${r.userMessage || '未知错误'}\n耗时：${r.costMs}ms`,
        '健康检查结果',
        { type: 'error', confirmButtonText: '知道了' }
      )
    }
  } catch (e) {
    // 拦截器已弹错
  }
}

async function toggleStatus(ch) {
  const toEnable = ch.status !== 'ENABLED'
  const action = toEnable ? '启用' : '停用'
  try {
    await ElMessageBox.confirm(
      toEnable
        ? `确定要启用渠道「${ch.name}」吗？`
        : `确定要停用渠道「${ch.name}」吗？\n停用后所有引用该渠道的任务将无法推送。`,
      '确认' + action,
      { type: 'warning', confirmButtonText: action, cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await post('/channels/' + ch.id + '/status?enable=' + toEnable)
    ElMessage.success('已' + action)
    loadChannels()
  } catch (e) {
    // 拦截器已弹错
  }
}

async function deleteCh(ch) {
  try {
    await ElMessageBox.confirm(
      `确定要删除渠道「${ch.name}」吗？此操作不可恢复。`,
      '确认删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await del('/channels/' + ch.id)
    ElMessage.success('已删除')
    loadChannels()
  } catch (e) {
    // 拦截器已弹错
  }
}

async function addWhitelist() {
  const val = newWhitelist.value.trim()
  if (!val) {
    ElMessage.warning('请输入域名')
    return
  }
  try {
    await post('/whitelist', { type: 'WEBHOOK_HOST', value: val })
    ElMessage.success('已添加')
    newWhitelist.value = ''
    loadWhitelist()
  } catch (e) {
    // 拦截器已弹错
  }
}

async function deleteWhitelist(wl) {
  try {
    await ElMessageBox.confirm(
      `确定要删除白名单「${wl.value}」吗？`,
      '确认删除',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await del('/whitelist/' + wl.id)
    ElMessage.success('已删除')
    loadWhitelist()
  } catch (e) {
    // 拦截器已弹错；若被引用，后端 SYS_002 + 拦截器会弹提示
  }
}

onMounted(() => {
  loadChannels()
  loadWhitelist()
})
</script>

<style scoped>
.text-danger {
  color: var(--danger-text);
}
.text-danger:hover {
  text-decoration: underline;
}
.wl-del {
  margin-left: 6px;
  font-weight: 700;
  cursor: pointer;
  opacity: 0.7;
}
.wl-del:hover {
  opacity: 1;
  color: var(--danger-text);
}
.pill.on {
  padding-right: 10px;
}
</style>
