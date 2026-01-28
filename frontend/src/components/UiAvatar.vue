<script setup lang="ts">
// UiAvatar：微信风格头像组件，优先展示图片，失败则展示首字母占位。
import { computed, ref } from 'vue'

const props = withDefaults(
  defineProps<{
    text?: string | null
    src?: string | null
    seed?: string | null
    size?: number
  }>(),
  {
    text: null,
    src: null,
    seed: null,
    size: 44,
  },
)

const failed = ref(false)

function hash32(s: string) {
  let h = 2166136261
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i)
    h = Math.imul(h, 16777619)
  }
  return h >>> 0
}

const palette = [
  '#07C160',
  '#22C55E',
  '#10B981',
  '#06B6D4',
  '#3B82F6',
  '#A855F7',
  '#F97316',
  '#F59E0B',
]

const label = computed(() => {
  const raw = (props.text ?? props.seed ?? '').trim()
  if (!raw) return '?'
  return raw.slice(0, 1).toUpperCase()
})

const bg = computed(() => {
  const key = (props.seed ?? props.text ?? label.value).trim()
  const idx = hash32(key) % palette.length
  return palette[idx]!
})

const style = computed(() => {
  const size = Math.max(28, Number(props.size || 44))
  return {
    width: `${size}px`,
    height: `${size}px`,
    fontSize: `${Math.round(size * 0.42)}px`,
    background: `linear-gradient(135deg, ${bg.value}, rgba(255,255,255,0.35))`,
  }
})
</script>

<template>
  <div class="uiAvatar" :style="style" :title="(text ?? seed ?? '').toString()">
    <img v-if="src && !failed" class="img" :src="src" alt="" @error="failed = true" />
    <div v-else class="fallback" aria-hidden="true">{{ label }}</div>
  </div>
</template>

<style scoped>
.uiAvatar {
  border-radius: var(--radius-md);
  border: 1px solid rgba(0, 0, 0, 0.06);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.06);
  display: grid;
  place-items: center;
  overflow: hidden;
  color: rgba(255, 255, 255, 0.95);
  user-select: none;
}
.img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.fallback {
  font-weight: 800;
  letter-spacing: 0.4px;
}
</style>
