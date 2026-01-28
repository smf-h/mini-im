<script setup lang="ts">
// UiListItem：通用列表行容器，兼容 RouterLink 与普通容器两种渲染方式。
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
  gap: 10px;
  align-items: center;
  padding: 10px 12px;
  background: transparent;
  color: inherit;
  border-bottom: 1px solid var(--divider);
  transition: background 120ms ease;
}
.uiListItem:hover {
  background: rgba(0, 0, 0, 0.03);
}
.uiListItem.router-link-active {
  background: rgba(0, 0, 0, 0.05);
}
.uiListItem.router-link-active:hover {
  background: rgba(0, 0, 0, 0.06);
}
.uiListItem:focus-visible {
  outline: none;
  box-shadow: inset 0 0 0 2px rgba(7, 193, 96, 0.55);
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
