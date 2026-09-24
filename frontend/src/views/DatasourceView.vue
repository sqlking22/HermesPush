<template>
  <PageHead
    title="数据源管理"
    sub="FR-DS-01~05 · 密码 AES-GCM 加密存储，接口不回显明文 · 必须使用只读账号"
  >
    <template #actions>
      <button class="btn pri" @click="openCreate">＋ 新增数据源</button>
    </template>
  </PageHead>

  <div class="card">
    <table class="tbl">
      <thead>
        <tr>
          <th>名称</th>
          <th>类型</th>
          <th>连接</th>
          <th>状态</th>
          <th>保护配置</th>
          <th>只读确认</th>
          <th>操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="ds in list" :key="ds.id">
          <td class="cell-t">{{ ds.name }}</td>
          <td>{{ ds.type }}</td>
          <td>
            <span class="mono" style="font-size:12px">{{ ds.jdbcUrl }}</span>
            <div class="hint">用户: {{ ds.username }} · 密码: ******</div>
          </td>
          <td>
            <span
              class="badge"
              :class="ds.status === 'ENABLED' ? 'b-ok' : 'b-gray'"
            >{{ ds.status === 'ENABLED' ? '启用' : '停用' }}</span>
          </td>
          <td>
            <span class="hint">
              超时{{ ds.queryTimeoutSec || '—' }}s · 上限{{ ds.maxRows || '—' }}行 · 池{{ ds.poolMax || '—' }}
            </span>
          </td>
          <td>
            <span
              class="badge"
              :class="ds.roConfirmed ? 'b-ok' : 'b-fail'"
            >{{ ds.roConfirmed ? '已确认' : '未确认' }}</span>
          </td>
          <td>
            <span class="lnk" @click="testConn(ds)">测试连接</span>
            <span class="lnk" @click="openEdit(ds)" style="margin-left:8px">编辑</span>
            <span
              class="lnk"
              :class="{ 'text-danger': ds.status === 'ENABLED' }"
              style="margin-left:8px"
              @click="toggleStatus(ds)"
            >{{ ds.status === 'ENABLED' ? '停用' : '启用' }}</span>
          </td>
        </tr>
        <tr v-if="list.length === 0 && !loading">
          <td colspan="7" style="text-align:center;color:var(--text-3);padding:30px 0">
            暂无数据源，点击右上角「新增数据源」创建
          </td>
        </tr>
      </tbody>
    </table>
  </div>

  <!-- 新增/编辑弹窗 -->
  <el-dialog
    v-model="dialogVisible"
    :title="isEdit ? '编辑数据源 · ' + form.name : '新增数据源'"
    width="640px"
    :close-on-click-modal="false"
  >
    <el-form :model="form" label-position="top" label-width="100px">
      <div class="grid c2">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如：主业务库(只读)" />
        </el-form-item>
        <el-form-item label="类型" required>
          <el-select v-model="form.type" style="width:100%">
            <el-option label="MySQL" value="MYSQL" />
            <el-option label="PostgreSQL" value="POSTGRESQL" />
            <el-option label="Oracle" value="ORACLE" />
          </el-select>
        </el-form-item>
      </div>
      <el-form-item label="JDBC URL" required>
        <el-input v-model="form.jdbcUrl" placeholder="jdbc:mysql://host:3306/db" />
      </el-form-item>
      <div class="grid c2">
        <el-form-item label="用户名" required>
          <el-input v-model="form.username" />
        </el-form-item>
        <el-form-item :label="isEdit ? '密码（留空=不修改）' : '密码'" :required="!isEdit">
          <el-input v-model="form.password" type="password" show-password :placeholder="isEdit ? '留空表示不修改' : '••••••••'" />
        </el-form-item>
        <el-form-item label="查询超时（秒）">
          <el-input-number v-model="form.queryTimeoutSec" :min="1" :max="3600" style="width:100%" />
        </el-form-item>
        <el-form-item label="行数上限">
          <el-input-number v-model="form.maxRows" :min="1" :max="1000000" style="width:100%" />
        </el-form-item>
        <el-form-item label="连接池上限">
          <el-input-number v-model="form.poolMax" :min="1" :max="100" style="width:100%" />
        </el-form-item>
        <el-form-item label="溢出策略">
          <el-select v-model="form.overflowPolicy" style="width:100%">
            <el-option label="默认" value="DEFAULT" />
            <el-option label="失败" value="FAIL" />
            <el-option label="等待" value="WAIT" />
          </el-select>
        </el-form-item>
      </div>
      <div style="background:var(--warn-l);border:1px solid #ecd9a8;border-radius:8px;padding:13px 15px;margin-top:8px">
        <span style="font-weight:700;color:var(--warn)">⚠ 只读账号强制要求（FR-DS-03）</span>
        <div style="margin-top:6px;color:var(--text-2);font-size:13px;line-height:1.7">
          平台执行的都是查询，但配置错误账号可能导致数据被意外修改。请为该数据源创建仅有 SELECT 权限的账号。
        </div>
      </div>
      <div class="checkbox-line">
        <el-checkbox v-model="form.roConfirmed">
          我已确认该账号无写权限（勾选记录写入配置审计）
        </el-checkbox>
      </div>
    </el-form>
    <template #footer>
      <button class="btn" @click="dialogVisible = false">取消</button>
      <button class="btn" @click="testInline">测试连接</button>
      <button class="btn pri" :disabled="!form.roConfirmed || saving" @click="handleSave">
        {{ saving ? '保存中...' : '保存' }}
      </button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { get, post, put } from '../api/http.js'
import PageHead from '../components/PageHead.vue'

const list = ref([])
const loading = ref(false)
const dialogVisible = ref(false)
const isEdit = ref(false)
const saving = ref(false)
const editId = ref(null)

const defaultForm = () => ({
  name: '',
  type: 'MYSQL',
  jdbcUrl: '',
  username: '',
  password: '',
  roConfirmed: false,
  queryTimeoutSec: 60,
  maxRows: 50000,
  poolMax: 10,
  overflowPolicy: 'DEFAULT'
})

const form = ref(defaultForm())

async function loadList() {
  loading.value = true
  try {
    const res = await get('/datasources')
    list.value = res.data || []
  } catch (e) {
    // 拦截器已弹错
  } finally {
    loading.value = false
  }
}

function openCreate() {
  isEdit.value = false
  editId.value = null
  form.value = defaultForm()
  dialogVisible.value = true
}

function openEdit(ds) {
  isEdit.value = true
  editId.value = ds.id
  form.value = {
    name: ds.name,
    type: ds.type,
    jdbcUrl: ds.jdbcUrl,
    username: ds.username,
    password: '',
    roConfirmed: ds.roConfirmed,
    queryTimeoutSec: ds.queryTimeoutSec ?? 60,
    maxRows: ds.maxRows ?? 50000,
    poolMax: ds.poolMax ?? 10,
    overflowPolicy: ds.overflowPolicy || 'DEFAULT'
  }
  dialogVisible.value = true
}

async function handleSave() {
  if (!form.value.roConfirmed) return
  if (!form.value.name || !form.value.type || !form.value.jdbcUrl || !form.value.username) {
    ElMessage.warning('请填写必填项')
    return
  }
  if (!isEdit.value && !form.value.password) {
    ElMessage.warning('新建数据源密码必填')
    return
  }
  saving.value = true
  try {
    const payload = {
      name: form.value.name,
      type: form.value.type,
      jdbcUrl: form.value.jdbcUrl,
      username: form.value.username,
      roConfirmed: form.value.roConfirmed,
      maxRows: form.value.maxRows,
      queryTimeoutSec: form.value.queryTimeoutSec,
      poolMax: form.value.poolMax,
      overflowPolicy: form.value.overflowPolicy
    }
    if (form.value.password) {
      payload.password = form.value.password
    }
    if (isEdit.value) {
      await put('/datasources/' + editId.value, payload)
      ElMessage.success('数据源已更新')
    } else {
      await post('/datasources', payload)
      ElMessage.success('数据源已创建（密码已加密）')
    }
    dialogVisible.value = false
    loadList()
  } catch (e) {
    // 拦截器已弹错
  } finally {
    saving.value = false
  }
}

async function testConn(ds) {
  try {
    const res = await post('/datasources/' + ds.id + '/test')
    const r = res.data
    if (r.ok) {
      ElMessageBox.alert(
        `连接成功\n数据库版本：${r.dbVersion || '—'}\n耗时：${r.costMs}ms`,
        '测试连接结果',
        { type: 'success', confirmButtonText: '好的' }
      )
    } else {
      ElMessageBox.alert(
        `连接失败\n错误码：${r.errorCode || '—'}\n原因：${r.userMessage || '未知错误'}`,
        '测试连接结果',
        { type: 'error', confirmButtonText: '知道了' }
      )
    }
  } catch (e) {
    // 拦截器已弹错
  }
}

async function testInline() {
  if (!form.value.jdbcUrl || !form.value.username) {
    ElMessage.warning('请先填写 JDBC URL 和用户名')
    return
  }
  if (!form.value.password && !isEdit.value) {
    ElMessage.warning('请先填写密码')
    return
  }
  try {
    const res = await post('/datasources/test-inline', {
      jdbcUrl: form.value.jdbcUrl,
      username: form.value.username,
      password: form.value.password || ''
    })
    const r = res.data
    if (r.ok) {
      ElMessageBox.alert(
        `连接成功\n数据库版本：${r.dbVersion || '—'}\n耗时：${r.costMs}ms`,
        '测试连接结果',
        { type: 'success', confirmButtonText: '好的' }
      )
    } else {
      ElMessageBox.alert(
        `连接失败\n错误码：${r.errorCode || '—'}\n原因：${r.userMessage || '未知错误'}`,
        '测试连接结果',
        { type: 'error', confirmButtonText: '知道了' }
      )
    }
  } catch (e) {
    // 拦截器已弹错
  }
}

async function toggleStatus(ds) {
  const toEnable = ds.status !== 'ENABLED'
  const action = toEnable ? '启用' : '停用'
  try {
    await ElMessageBox.confirm(
      toEnable
        ? `确定要启用数据源「${ds.name}」吗？`
        : `确定要停用数据源「${ds.name}」吗？\n停用后所有引用该数据源的任务将无法执行。`,
      '确认' + action,
      { type: 'warning', confirmButtonText: action, cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await post('/datasources/' + ds.id + '/status?enable=' + toEnable)
    ElMessage.success('已' + action)
    loadList()
  } catch (e) {
    // 拦截器已弹错；若 SYS_002 表示被引用，后端返回的错误已弹出
  }
}

onMounted(() => {
  loadList()
})
</script>

<style scoped>
.text-danger {
  color: var(--danger-text);
}
.text-danger:hover {
  text-decoration: underline;
}
</style>
