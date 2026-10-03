<script setup lang="ts">
import { api } from '../api'
import type { Row } from '../domain'
import OperationForm from './OperationForm.vue'
defineProps<{ createOpen:boolean;resetOpen:boolean;target:Row }>()
const emit=defineEmits<{ 'update:createOpen':[boolean];'update:resetOpen':[boolean];saved:[] }>()
</script>
<template><el-dialog :model-value="createOpen" title="创建销售后台操作员" width="560px" @update:model-value="emit('update:createOpen',$event)"><OperationForm title="操作员账号" :initial="{username:'',displayName:'',password:''}" :fields="[{key:'username',label:'用户名'}, {key:'displayName',label:'显示名称'}, {key:'password',label:'初始密码',type:'password',hint:'至少10位，操作会写入审计'}]" :submit="value=>api.post('/admin/users',{...value,role:'OPERATOR'})" button="创建操作员" @saved="emit('update:createOpen',false);emit('saved')"/></el-dialog><el-dialog :model-value="resetOpen" :title="'重置密码 · '+(target.username||'')" width="540px" @update:model-value="emit('update:resetOpen',$event)"><OperationForm title="设置临时密码" :initial="{newPassword:''}" :fields="[{key:'newPassword',label:'新的临时密码',type:'password',hint:'至少10位。用户下次登录必须自行改密，操作会写入审计。'}]" :submit="value=>api.post('/admin/users/'+target.id+'/reset-password',value)" button="重置并要求用户改密" @saved="emit('update:resetOpen',false);emit('saved')"/></el-dialog></template>
