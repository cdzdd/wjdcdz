<script setup lang="ts">
import { ref } from 'vue'
import { api } from '../api'
import { usePage,perform,busy } from '../state'
import type { Row } from '../domain'
import PageHeader from '../components/PageHeader.vue'
import LoadState from '../components/LoadState.vue'
const notifications=ref<Row[]>([]),announcements=ref<Row[]>([]),tab=ref('notifications')
const {loading,error,load}=usePage(async()=>{[notifications.value,announcements.value]=await Promise.all([api.get('/notifications'),api.get('/announcements')])})
async function read(row:Row){await perform(()=>api.patch('/notifications/'+row.id,{read:true}),load)}
</script>
<template><PageHeader title="消息与公告" subtitle="交易进度、任务结果及产品变动及时可查。"><button class="button" @click="load">刷新</button></PageHeader><LoadState :loading="loading" :error="error" @retry="load"/><div class="tabs"><button :class="{active:tab==='notifications'}" @click="tab='notifications'">站内消息</button><button :class="{active:tab==='announcements'}" @click="tab='announcements'">平台公告</button></div><section class="panel"><div v-for="row in (tab==='notifications'?notifications:announcements)" :key="row.id" class="message-item"><h3>{{ row.title }} <span v-if="tab==='notifications'&&!row.read&&!row.isRead" class="inline-tag">未读</span></h3><p>{{ row.message || row.body }}</p><div class="toolbar"><small class="muted">{{ String(row.createdAt || '').replace('T',' ') }}</small><button v-if="tab==='notifications'&&!row.read&&!row.isRead" class="text-button" :disabled="busy>0" @click="read(row)">标为已读</button></div></div><div v-if="!(tab==='notifications'?notifications:announcements).length" class="empty">暂无消息</div></section></template>
