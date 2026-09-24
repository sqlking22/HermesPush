<template>
  <div id="login">
    <div class="login-box">
      <h2>HermesPush</h2>
      <div class="sub">智能数据推送平台</div>
      <input
        v-model="username"
        class="inp"
        placeholder="用户名（admin）"
        @keyup.enter="handleLogin"
      />
      <input
        v-model="password"
        type="password"
        class="inp"
        placeholder="密码（hermes@2026）"
        @keyup.enter="handleLogin"
      />
      <button class="btn pri" :disabled="loading" @click="handleLogin">
        {{ loading ? '登录中...' : '登 录' }}
      </button>
      <div class="hint" style="margin-top:12px">
        连续输错 5 次将锁定账户 15 分钟
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { post } from '../api/http.js'
import { setAuth } from '../store.js'

const router = useRouter()
const username = ref('')
const password = ref('')
const loading = ref(false)

async function handleLogin() {
  if (!username.value || !password.value) return
  loading.value = true
  try {
    const res = await post('/auth/login', {
      username: username.value,
      password: password.value
    })
    if (res && res.data) {
      setAuth({
        token: res.data.token,
        username: res.data.username,
        role: res.data.role
      })
      router.push('/home')
    }
  } catch (e) {
    // 错误提示由 http.js 拦截器统一处理
  } finally {
    loading.value = false
  }
}
</script>
