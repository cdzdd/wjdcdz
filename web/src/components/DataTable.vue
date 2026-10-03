<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { cell, stateTone } from '../domain'
import type { Row, Column } from '../domain'
const props = withDefaults(defineProps<{ rows: Row[]; columns: Column[]; empty?: string }>(), { empty: '暂无记录' })
const page = ref(1)
const visible = computed(() => props.rows.slice((page.value - 1) * 12, page.value * 12))
watch(() => props.rows, () => { page.value = 1 })
</script>
<template><div class="data-table"><div class="table-scroll"><table><thead><tr><th v-for="column in columns" :key="column.key">{{ column.label }}</th><th v-if="$slots.actions">操作</th></tr></thead><tbody><tr v-for="(row, index) in visible" :key="row.id ?? index"><td v-for="column in columns" :key="column.key"><slot :name="'cell-' + column.key" :row="row"><span v-if="column.kind === 'state'" class="status" :class="stateTone(row[column.key])">{{ cell(row[column.key]) }}</span><span v-else>{{ cell(row[column.key], column.kind) }}</span></slot></td><td v-if="$slots.actions" class="table-actions"><slot name="actions" :row="row"/></td></tr></tbody></table></div><div v-if="!rows.length" class="empty"><span class="empty-mark">◇</span><p>{{ empty }}</p><slot name="empty"/></div><div v-if="rows.length > 12" class="pagination"><span>共 {{ rows.length }} 条</span><button class="text-button" :disabled="page === 1" @click="page--">上一页</button><span>{{ page }} / {{ Math.ceil(rows.length / 12) }}</span><button class="text-button" :disabled="page * 12 >= rows.length" @click="page++">下一页</button></div></div></template>
