<script setup lang="ts">
withDefaults(defineProps<{ fullScreen?: boolean; label?: string }>(), {
  fullScreen: false,
  label: '正在加载最新数据…'
})
</script>

<template>
  <div class="platform-loader" :class="{ 'platform-loader--screen': fullScreen }" role="status" aria-live="polite" aria-atomic="true">
    <div class="platform-loader__visual" aria-hidden="true">
      <span class="platform-loader__ring"/><span class="platform-loader__ring platform-loader__ring--inner"/>
      <span class="platform-loader__mark">恒</span>
    </div>
    <div class="platform-loader__copy">
      <span v-if="fullScreen" class="platform-loader__eyebrow">HENGSHENG · SIMULATION</span>
      <strong>{{ label }}</strong>
      <p v-if="fullScreen">从每一次选择，读懂投资的脉络。</p>
    </div>
    <div v-if="fullScreen" class="platform-loader__track" aria-hidden="true"><span/></div>
    <span v-if="fullScreen" class="platform-loader__note">教学模拟 · 虚拟资金</span>
  </div>
</template>
