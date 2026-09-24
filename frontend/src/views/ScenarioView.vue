<template>
  <div>
    <PageHead title="新建推送任务" sub="选择一个场景模板，快速创建你的推送任务" />

    <div class="grid c2" style="max-width:780px;margin-top:10px">
      <div
        class="scenario-card"
        :class="{ disabled: !s.available }"
        v-for="s in scenarios"
        :key="s.key"
        @click="handleClick(s)"
      >
        <div class="sc-icon">{{ s.icon }}</div>
        <div class="sc-title">
          {{ s.title }}
          <span v-if="s.badge" class="badge b-gray" style="margin-left:8px">{{ s.badge }}</span>
        </div>
        <div class="sc-desc">{{ s.desc }}</div>
      </div>
    </div>

    <div style="margin-top:22px;text-align:center;max-width:780px">
      <div class="lnk" @click="goExpert">
        自定义（专家模式）→
      </div>
      <div class="hint" style="margin-top:6px">
        专家模式提供完整的多数据源、多内容形式、按人分发等高级配置
      </div>
    </div>
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import PageHead from '../components/PageHead.vue'

const router = useRouter()

const scenarios = [
  {
    key: 'daily',
    title: '日报发群',
    desc: '每天定时把 SQL 查出来的数据生成摘要卡片，推送到企业微信群',
    icon: '📊',
    available: true,
    badge: null
  },
  {
    key: 'detail',
    title: '明细附件',
    desc: '把明细数据生成 Excel/图片附件，随消息一起发到群里',
    icon: '📎',
    available: false,
    badge: 'M2'
  },
  {
    key: 'monitor',
    title: '指标监控提醒',
    desc: '监控关键指标，超过阈值自动告警，支持升级策略',
    icon: '🔔',
    available: false,
    badge: 'M4b'
  },
  {
    key: 'personal',
    title: '按人分发',
    desc: '按人员维度切分数据，一对一推送到每个人的企业微信',
    icon: '👥',
    available: false,
    badge: 'M5'
  }
]

function handleClick(s) {
  if (!s.available) {
    ElMessage.info(`该场景随 ${s.badge} 交付，敬请期待`)
    return
  }
  router.push({ name: 'wizard3', query: { scen: s.key } })
}

function goExpert() {
  ElMessage.info('专家模式建设中（M2 完整交付）')
}
</script>

<style scoped>
.scenario-card {
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 22px 24px;
  cursor: pointer;
  transition: all .15s;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.scenario-card:hover:not(.disabled) {
  border-color: var(--primary);
  box-shadow: 0 4px 12px rgba(42,120,214,.12);
}
.scenario-card.disabled {
  opacity: .55;
  cursor: not-allowed;
}
.sc-icon {
  font-size: 28px;
  margin-bottom: 4px;
}
.sc-title {
  font-size: 16px;
  font-weight: 600;
}
.sc-desc {
  font-size: 13px;
  color: var(--text-2);
  line-height: 1.6;
}
</style>
