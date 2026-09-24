<template>
  <div class="page-head">
    <div>
      <div class="page-title">{{ pageTitle }}</div>
      <div class="page-sub">页面建设中（Task 22-24）</div>
    </div>
  </div>
  <div class="card" style="text-align:center;padding:60px 20px">
    <div style="font-size:48px;margin-bottom:16px">🚧</div>
    <div style="font-size:16px;color:var(--text-2)">该页面正在开发中</div>
    <div class="hint" style="margin-top:8px">后续任务将逐步实现完整功能</div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const pageTitle = computed(() => route.meta.title || '页面')
</script>
