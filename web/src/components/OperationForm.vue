<script setup lang="ts">
import { reactive, watch } from 'vue'
import { busy, perform } from '../state'
import type { Field, Row } from '../domain'
const props = defineProps<{ title: string; fields: Field[]; initial?: Row; submit: (value: Row) => Promise<unknown>; button?: string; description?: string }>()
const emit = defineEmits<{ saved: [] }>()
const form = reactive<Row>({})
watch(() => props.initial, () => { for (const key of Object.keys(form)) delete form[key]; Object.assign(form, props.initial || {}) }, { immediate: true, deep: true })
async function save() { await perform(() => props.submit({ ...form }), () => { emit('saved') }) }
</script>
<template><section class="panel"><div class="section-title"><h2>{{ title }}</h2></div><p v-if="description" class="muted">{{ description }}</p><form class="form-grid" @submit.prevent="save"><label v-for="field in fields" :key="field.key" :class="{ wide: field.type === 'textarea' }"><span>{{ field.label }}</span><select v-if="field.type === 'select'" v-model="form[field.key]" :required="field.required !== false"><option disabled value="">请选择</option><option v-for="option in field.options" :key="option.value" :value="option.value">{{ option.label }}</option></select><textarea v-else-if="field.type === 'textarea'" v-model="form[field.key]" :required="field.required !== false" rows="3"/><input v-else-if="field.type === 'checkbox'" v-model="form[field.key]" type="checkbox"/><input v-else v-model="form[field.key]" :type="field.type || 'text'" :required="field.required !== false" :autocomplete="field.type === 'password' ? 'new-password' : 'off'"/><small v-if="field.hint" class="muted">{{ field.hint }}</small></label><div class="wide"><button class="button primary" :disabled="busy > 0" type="submit">{{ busy ? '处理中…' : button || '保存' }}</button></div></form></section></template>
