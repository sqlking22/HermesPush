/**
 * HTTP 统一错误契约：
 * - 所有 reject 均为 { code, message, errorCode, suggestion, raw } 对象
 * - code：业务错误 = data.code；HTTP 错误 = HTTP 状态码；网络错误 = -1
 * - message：人类可读错误文案
 * - errorCode：业务错误码（如 AUTH-001、SQL-004），非业务错误为 null
 * - suggestion：修复建议（后端 data.data.suggestion），非业务错误为 null
 * - raw：原始对象（业务错误为 data，HTTP 为 error.response，网络错误为 error），供调试用
 *
 * 成功响应直接返回 response.data（ApiResponse 完整对象，code=0）
 */
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { store, clearAuth } from '../store.js'
import router from '../router.js'

const http = axios.create({
  baseURL: '/api',
  timeout: 30000
})

function buildError({ code, message, errorCode = null, suggestion = null, raw = null }) {
  return { code, message, errorCode, suggestion, raw }
}

// 请求拦截器：附加 token
http.interceptors.request.use(config => {
  if (store.token) {
    config.headers['Authorization'] = store.token
  }
  return config
}, error => {
  return Promise.reject(buildError({
    code: -1,
    message: error.message || '请求配置错误',
    raw: error
  }))
})

// 响应拦截器
http.interceptors.response.use(response => {
  const data = response.data
  // 业务失败：code !== 0
  if (data && typeof data.code !== 'undefined' && data.code !== 0) {
    const errCode = (data.data && data.data.errorCode) || null
    const suggestion = (data.data && data.data.suggestion) || null
    const message = data.message || '操作失败'
    ElMessage.error((errCode ? errCode + ' ' : '') + message + (suggestion ? '｜' + suggestion : ''))
    return Promise.reject(buildError({
      code: data.code,
      message,
      errorCode: errCode,
      suggestion,
      raw: data
    }))
  }
  return data
}, error => {
  // HTTP 401：未登录，清状态跳登录
  if (error.response && error.response.status === 401) {
    clearAuth()
    if (router.currentRoute.value.path !== '/login') {
      router.push('/login')
    }
    return Promise.reject(buildError({
      code: 401,
      message: '登录已过期，请重新登录',
      raw: error.response
    }))
  }
  // 其他 HTTP 错误
  if (error.response) {
    const msg = error.response.statusText || ('HTTP ' + error.response.status + ' 错误')
    ElMessage.error(msg)
    return Promise.reject(buildError({
      code: error.response.status,
      message: msg,
      raw: error.response
    }))
  }
  // 网络错误（无 response）
  const msg = error.message || '网络请求失败，请检查网络连接'
  ElMessage.error(msg)
  return Promise.reject(buildError({
    code: -1,
    message: msg,
    raw: error
  }))
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
