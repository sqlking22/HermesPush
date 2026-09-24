<template>
  <div id="app">
    <aside id="sidebar">
      <div class="logo">
        <span class="mark">H</span>HermesPush
      </div>
      <div class="nav-group">{{ store.mode === 'simple' ? '简单模式' : '专家模式' }}</div>
      <template v-for="item in navItems" :key="item.k">
        <div
          class="nav-item"
          :class="{ active: isActive(item.k) }"
          @click="go(item.k)"
        >
          <span class="ico">{{ item.i }}</span>{{ item.n }}
        </div>
      </template>
    </aside>
    <div id="main">
      <div id="topbar">
        <div class="crumb">
          HermesPush / <b>{{ currentTitle }}</b>
          <span v-if="store.mode === 'expert'" class="hint">（专家模式）</span>
        </div>
        <div class="top-right">
          <div class="seg">
            <span
              class="pill"
              :class="{ on: store.mode === 'simple' }"
              @click="switchMode('simple')"
            >简单模式</span>
            <span
              class="pill"
              :class="{ on: store.mode === 'expert' }"
              @click="switchMode('expert')"
            >专家模式</span>
          </div>
          <span style="color:var(--text-2);font-size:13px">{{ store.username }}</span>
          <div class="avatar" @click="handleLogout" title="退出登录">
            {{ avatarText }}
          </div>
        </div>
      </div>
      <div id="content">
        <router-view />
      </div>
      <div class="footer-note">HermesPush 智能数据推送平台</div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { store, setMode, clearAuth } from '../store.js'
import { post } from '../api/http.js'

const route = useRoute()
const router = useRouter()

const simpleNav = [
  { k: 'home', i: '▦', n: '今日' },
  { k: 'tasks', i: '☰', n: '推送任务' },
  { k: 'ds', i: '⛁', n: '数据源' },
  { k: 'scenarios', i: '＋', n: '新建任务' },
  { k: 'ch', i: '➤', n: '推送渠道' },
  { k: 'execs', i: '⟳', n: '执行记录' }
]

const expertNav = [
  { k: 'home', i: '▦', n: '今日概览' },
  { k: 'tasks', i: '☰', n: '任务列表' },
  { k: 'ds', i: '⛁', n: '数据源管理' },
  { k: 'ch', i: '➤', n: '渠道管理' },
  { k: 'execs', i: '⟳', n: '执行日志' },
  { k: 'monitor', i: '📊', n: '监控告警（M4b）' },
  { k: 'distribution', i: '👥', n: '分发清单（M5）' },
  { k: 'audit', i: '✓', n: '审核工作台（M4a）' },
  { k: 'audit-log', i: '📜', n: '审计中心（M4a）' },
  { k: 'settings', i: '⚙', n: '系统设置（M4a）' }
]

const navItems = computed(() => store.mode === 'simple' ? simpleNav : expertNav)

const currentTitle = computed(() => {
  const found = navItems.value.find(x => x.k === route.name)
  return found ? found.n : (route.meta.title || '')
})

const avatarText = computed(() => {
  return store.username ? store.username.charAt(0).toUpperCase() : 'U'
})

function isActive(key) {
  return route.name === key
}

function go(key) {
  router.push({ name: key })
}

function switchMode(mode) {
  if (mode !== 'simple' && mode !== 'expert') return
  if (store.mode === mode) return
  setMode(mode)
  // 切换后跳首页，避免停留在当前模式不存在的页面
  router.push({ name: 'home' })
}

async function handleLogout() {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '提示', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'info'
    })
  } catch {
    return
  }
  try {
    await post('/auth/logout')
  } catch (e) {
    // 忽略登出接口错误，仍清状态
  }
  clearAuth()
  router.push('/login')
}
</script>
