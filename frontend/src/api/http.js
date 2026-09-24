import axios from 'axios'
import { ElMessage } from 'element-plus'
import { store, clearAuth } from '../store.js'
import router from '../router.js'

const http = axios.create({
  baseURL: '/api',
  timeout: 30000
})

// 请求拦截器：附加 token
http.interceptors.request.use(config => {
  if (store.token) {
    config.headers['Authorization'] = store.token
  }
  return config
}, error => Promise.reject(error))

// 响应拦截器
http.interceptors.response.use(response => {
  const data = response.data
  // 业务失败：code !== 0
  if (data && typeof data.code !== 'undefined' && data.code !== 0) {
    const errCode = (data.data && data.data.errorCode) || ''
    const suggestion = (data.data && data.data.suggestion) || ''
    const message = data.message || '操作失败'
    ElMessage.error(errCode + ' ' + message + '｜' + suggestion)
    return Promise.reject(data)
  }
  return data
}, error => {
  // HTTP 401：未登录，清状态跳登录
  if (error.response && error.response.status === 401) {
    clearAuth()
    if (router.currentRoute.value.path !== '/login') {
      router.push('/login')
    }
    return Promise.reject(error)
  }
  // 网络错误或其他
  const msg = error.message || '网络请求失败'
  ElMessage.error(msg)
  return Promise.reject(error)
})

export default http

export function get(url, params) {
  return http.get(url, { params })
}

export function post(url, data) {
  return http.post(url, data)
}

export function put(url, data) {
  return http.put(url, data)
}

export function del(url, params) {
  return http.delete(url, { params })
}
