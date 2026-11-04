<script setup lang="ts">
import { onMounted, onBeforeUnmount, watch, ref } from 'vue'
import { init, use } from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { EChartsType } from 'echarts/core'
import type { Row } from '../domain'
use([LineChart, GridComponent, TooltipComponent, CanvasRenderer])
const props = defineProps<{ history: Row[]; money?: boolean }>()
const element = ref<HTMLDivElement>()
let chart: EChartsType | undefined, observer: ResizeObserver | undefined
function draw() {
  chart?.setOption({ animation: false, color: ['#b3485f'], tooltip: { trigger: 'axis' }, grid: { left: 55, right: 20, top: 20, bottom: 40 }, xAxis: { type: 'category', data: props.history.map(r => r.date), axisLine: { lineStyle: { color: '#e9d4dd' } }, axisLabel: { color: '#7f646e' } }, yAxis: { type: 'value', scale: true, axisLabel: { color: '#7f646e' }, splitLine: { lineStyle: { color: '#f5e7ed' } } }, series: [{ type: 'line', smooth: true, symbol: 'circle', symbolSize: 5, data: props.history.map(r => Number(props.money ? r.incomePerTenThousand : r.nav)), areaStyle: { color: '#fae4ec' }, lineStyle: { width: 3 } }] }, true)
}
onMounted(() => { if (element.value) { chart = init(element.value); draw(); observer = new ResizeObserver(() => chart?.resize()); observer.observe(element.value) } })
watch(() => props.history, draw, { deep: true })
onBeforeUnmount(() => { observer?.disconnect(); chart?.dispose() })
</script>
<template><div v-if="!history.length" class="empty">尚未发布历史数据</div><div ref="element" class="chart" role="img" :aria-label="money ? '模拟万份收益历史曲线' : '模拟基金净值历史曲线'"/><p class="chart-note">数据来自平台模拟发布，历史表现不代表未来收益。</p></template>
