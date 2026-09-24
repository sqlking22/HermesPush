import { reactive } from 'vue'

const STORAGE_KEY = 'hermes-auth'

function loadFromStorage() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) return JSON.parse(raw)
  } catch (e) { /* ignore */ }
  return { token: '', username: '', role: '', mode: 'simple' }
}

const initial = loadFromStorage()

export const store = reactive({
  token: initial.token || '',
  username: initial.username || '',
  role: initial.role || '',
  mode: initial.mode || 'simple'
})

function persist() {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({
      token: store.token,
      username: store.username,
      role: store.role,
      mode: store.mode
    }))
  } catch (e) { /* ignore */ }
}

export function setAuth({ token, username, role }) {
  store.token = token || ''
  store.username = username || ''
  store.role = role || ''
  persist()
}

export function clearAuth() {
  store.token = ''
  store.username = ''
  store.role = ''
  persist()
}

export function setMode(mode) {
  store.mode = mode
  persist()
}
