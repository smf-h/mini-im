<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink } from 'vue-router'

const props = withDefaults(
  defineProps<{
    to?: string | null
  }>(),
  {
    to: null,
  },
)

const tag = computed(() => (props.to ? RouterLink : 'div'))
</script>

<template>
  <component :is="tag" class="uiListItem" :to="to ?? undefined">
    <div v-if="$slots.left" class="left">
      <slot name="left" />
    </div>
    <div class="main">
      <slot />
    </div>
    <div v-if="$slots.right" class="right">
      <slot name="right" />
    </div>
  </component>
</template>

<style scoped>
.uiListItem {
  display: grid;
  grid-template-columns: auto 1fr auto;
  gap: 12px;
  align-items: center;
  padding: 12px 12px;
  background: var(--surface);
  color: inherit;
  border-bottom: 1px solid var(--divider);
  transition: background 120ms ease;
}
.uiListItem:hover {
  background: #f9f9f9;
}
.left,
.right {
  display: flex;
  align-items: center;
  gap: 10px;
}
.main {
  min-width: 0;
}
</style>

