<script setup lang="ts">defineProps<{ loading: boolean; error: string }>(); defineEmits<{ retry: [] }>()</script>
<template><div v-if="error" class="notice error" role="alert">{{ error }} <button class="text-button" @click="$emit('retry')">重试</button></div><div v-else-if="loading" class="loading" role="status">正在加载最新数据…</div></template>
