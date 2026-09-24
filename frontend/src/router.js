import { createRouter, createWebHashHistory } from 'vue-router'
import { store } from './store.js'
import LoginView from './views/LoginView.vue'
import AppLayout from './layout/AppLayout.vue'
import PlaceholderView from './views/PlaceholderView.vue'
import DatasourceView from './views/DatasourceView.vue'
import ChannelView from './views/ChannelView.vue'

const routes = [
  { path: '/login', name: 'login', component: LoginView, meta: { public: true } },
  {
    path: '/',
    component: AppLayout,
    redirect: '/home',
    children: [
      { path: 'home', name: 'home', component: PlaceholderView, meta: { title: '今日' } },
      { path: 'tasks', name: 'tasks', component: PlaceholderView, meta: { title: '推送任务' } },
      { path: 'scenarios', name: 'scenarios', component: PlaceholderView, meta: { title: '新建推送任务' } },
      { path: 'wizard3', name: 'wizard3', component: PlaceholderView, meta: { title: '新建推送任务' } },
      { path: 'ds', name: 'ds', component: DatasourceView, meta: { title: '数据源' } },
      { path: 'ch', name: 'ch', component: ChannelView, meta: { title: '推送渠道' } },
      { path: 'execs', name: 'execs', component: PlaceholderView, meta: { title: '执行记录' } },
      { path: 'expert-placeholder', name: 'expert-placeholder', component: PlaceholderView, meta: { title: '专家模式页面' } }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/home' }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  const hasToken = !!store.token
  // 已登录用户访问登录页 → 跳首页
  if (to.path === '/login' && hasToken) {
    next('/home')
    return
  }
  // 公开页直接放行
  if (to.meta.public) {
    next()
    return
  }
  // 无 token → 跳登录
  if (!hasToken) {
    next('/login')
    return
  }
  next()
})

export default router
