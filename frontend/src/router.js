import { createRouter, createWebHashHistory } from 'vue-router'
import { store } from './store.js'
import LoginView from './views/LoginView.vue'
import AppLayout from './layout/AppLayout.vue'
import PlaceholderView from './views/PlaceholderView.vue'
import ScenarioView from './views/ScenarioView.vue'
import Wizard3View from './views/Wizard3View.vue'
import TaskListView from './views/TaskListView.vue'
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
      { path: 'tasks', name: 'tasks', component: TaskListView, meta: { title: '推送任务' } },
      { path: 'scenarios', name: 'scenarios', component: ScenarioView, meta: { title: '新建推送任务' } },
      { path: 'wizard3', name: 'wizard3', component: Wizard3View, meta: { title: '新建推送任务' } },
      { path: 'ds', name: 'ds', component: DatasourceView, meta: { title: '数据源' } },
      { path: 'ch', name: 'ch', component: ChannelView, meta: { title: '推送渠道' } },
      { path: 'execs', name: 'execs', component: PlaceholderView, meta: { title: '执行记录' } },
      // 专家模式占位页面
      { path: 'monitor', name: 'monitor', component: PlaceholderView, meta: { title: '监控告警（M4b）' } },
      { path: 'distribution', name: 'distribution', component: PlaceholderView, meta: { title: '分发清单（M5）' } },
      { path: 'audit', name: 'audit', component: PlaceholderView, meta: { title: '审核工作台（M4a）' } },
      { path: 'audit-log', name: 'audit-log', component: PlaceholderView, meta: { title: '审计中心（M4a）' } },
      { path: 'settings', name: 'settings', component: PlaceholderView, meta: { title: '系统设置（M4a）' } }
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
  if (to.path === '/login' && hasToken) {
    next('/home')
    return
  }
  if (to.meta.public) {
    next()
    return
  }
  if (!hasToken) {
    next('/login')
    return
  }
  next()
})

export default router
