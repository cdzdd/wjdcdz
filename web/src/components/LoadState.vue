<script setup lang="ts">
import LoadingIndicator from './LoadingIndicator.vue'
defineProps<{ loading: boolean; error: string }>()
defineEmits<{ retry: [] }>()
</script>
<template><div v-if="error" class="notice error" role="alert">{{ error }} <button class="text-button" @click="$emit('retry')">重试</button></div><LoadingIndicator v-else-if="loading"/></template>
