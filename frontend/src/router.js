import { createRouter, createWebHashHistory } from 'vue-router'
import { store } from './store.js'
import LoginView from './views/LoginView.vue'
import AppLayout from './layout/AppLayout.vue'
import PlaceholderView from './views/PlaceholderView.vue'

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
      { path: 'ds', name: 'ds', component: PlaceholderView, meta: { title: '数据源' } },
      { path: 'ch', name: 'ch', component: PlaceholderView, meta: { title: '推送渠道' } },
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
  if (to.meta.public) {
    next()
    return
  }
  if (!store.token) {
    next('/login')
    return
  }
  next()
})

export default router
