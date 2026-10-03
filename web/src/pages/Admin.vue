<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { user } from '../state'
import Overview from './admin/Overview.vue'
import Customers from './admin/Customers.vue'
import Products from './admin/Products.vue'
import Trading from './admin/Trading.vue'
import Benefits from './admin/Benefits.vue'
import System from './admin/System.vue'
const route=useRoute()
const components={overview:Overview,customers:Customers,products:Products,trading:Trading,benefits:Benefits,system:System}
const current=computed(()=>components[String(route.params.section || 'overview') as keyof typeof components] || Overview)
</script>
<template><div v-if="!['ADMIN','OPERATOR'].includes(user?.role)" class="notice error">此页面仅供销售后台人员使用。 <RouterLink to="/">返回资产总览</RouterLink></div><component :is="current" v-else/></template>
