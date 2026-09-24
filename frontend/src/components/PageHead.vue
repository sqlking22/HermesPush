<template>
  <div class="page-head">
    <div>
      <div class="page-title">{{ title }}</div>
      <div v-if="sub" class="page-sub">{{ sub }}</div>
    </div>
    <div class="acts">
      <slot name="actions"></slot>
    </div>
  </div>
</template>

<script setup>
defineProps({
  title: { type: String, required: true },
  sub: { type: String, default: '' }
})
</script>
