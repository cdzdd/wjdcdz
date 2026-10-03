<script setup lang="ts">defineProps<{ title: string; subtitle?: string }>()</script>
<template><div class="page-header"><div><p class="eyebrow">HENGSHENG / SIMULATION</p><h1>{{ title }}</h1><p v-if="subtitle" class="muted">{{ subtitle }}</p></div><div class="header-actions"><slot/></div></div></template>
