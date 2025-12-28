<script setup lang="ts">
type Option = { value: string; label: string }

const props = defineProps<{
  modelValue: string
  options: Option[]
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', v: string): void
}>()
</script>

<template>
  <div class="seg" role="tablist">
    <button
      v-for="o in options"
      :key="o.value"
      class="segItem"
      :data-active="o.value === modelValue ? '1' : '0'"
      type="button"
      role="tab"
      @click="emit('update:modelValue', o.value)"
    >
      {{ o.label }}
    </button>
  </div>
</template>

<style scoped>
.seg {
  display: inline-flex;
  gap: 4px;
  padding: 4px;
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.04);
  border: 1px solid rgba(0, 0, 0, 0.04);
}
.segItem {
  border: 0;
  background: transparent;
  color: var(--text-2);
  padding: 6px 12px;
  border-radius: 999px;
  cursor: pointer;
  transition: transform 120ms ease, box-shadow 120ms ease, background 120ms ease, color 120ms ease;
}
.segItem:hover {
  background: rgba(255, 255, 255, 0.65);
}
.segItem[data-active='1'] {
  background: rgba(255, 255, 255, 0.95);
  color: var(--text);
  box-shadow: var(--shadow-card);
}
.segItem:active {
  transform: scale(0.99);
}
</style>

