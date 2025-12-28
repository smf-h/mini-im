<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    count?: number | string | null
    dot?: boolean
    max?: number
    tone?: 'danger' | 'primary' | 'neutral'
  }>(),
  {
    count: null,
    dot: false,
    max: 99,
    tone: 'danger',
  },
)

const normalizedCount = computed(() => {
  if (props.count == null) return 0
  if (typeof props.count === 'number') return Number.isFinite(props.count) ? Math.max(0, props.count) : 0
  const s = String(props.count).trim()
  if (!s) return 0
  const n = Number(s)
  return Number.isFinite(n) ? Math.max(0, Math.floor(n)) : 0
})

const text = computed(() => {
  const n = normalizedCount.value
  if (n <= 0) return ''
  if (n > props.max) return `${props.max}+`
  return String(n)
})

const show = computed(() => props.dot || normalizedCount.value > 0)
</script>

<template>
  <span v-if="show" class="uiBadge" :data-tone="tone" :data-dot="dot ? '1' : '0'">
    <span v-if="!dot" class="text">{{ text }}</span>
  </span>
</template>

<style scoped>
.uiBadge {
  flex: none;
  min-width: 18px;
  height: 18px;
  padding: 0 6px;
  border-radius: 999px;
  display: inline-grid;
  place-items: center;
  font-size: 12px;
  line-height: 18px;
  color: #ffffff;
  background: var(--danger);
  border: 1px solid rgba(0, 0, 0, 0.04);
}
.uiBadge[data-tone='primary'] {
  background: var(--primary);
}
.uiBadge[data-tone='neutral'] {
  background: rgba(17, 17, 17, 0.22);
}
.uiBadge[data-dot='1'] {
  min-width: 10px;
  width: 10px;
  height: 10px;
  padding: 0;
}
.text {
  transform: translateY(-0.2px);
}
</style>

