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
  if (!chart || !element.value) return
  const fontFamily = getComputedStyle(element.value).fontFamily
  const axisLabel = { color: '#69798c', fontFamily, fontSize: 13 }
  chart.setOption({
    animation: false,
    color: ['#163b69'],
    tooltip: { trigger: 'axis', textStyle: { fontFamily, fontSize: 15 } },
    grid: { left: 55, right: 20, top: 20, bottom: 40 },
    xAxis: {
      type: 'category', data: props.history.map(row => row.date),
      axisLine: { lineStyle: { color: '#d8e1ec' } }, axisLabel,
    },
    yAxis: { type: 'value', scale: true, axisLabel, splitLine: { lineStyle: { color: '#e9eef4' } } },
    series: [{
      type: 'line', smooth: true, symbol: 'circle', symbolSize: 5,
      data: props.history.map(row => Number(props.money ? row.incomePerTenThousand : row.nav)),
      areaStyle: { color: '#e2ecf8' }, lineStyle: { width: 3 },
    }],
  }, true)
}
onMounted(() => { if (element.value) { chart = init(element.value); draw(); observer = new ResizeObserver(() => chart?.resize()); observer.observe(element.value) } })
watch(() => props.history, draw, { deep: true })
onBeforeUnmount(() => { observer?.disconnect(); chart?.dispose() })
</script>
<template><div v-if="!history.length" class="empty">尚未发布历史数据</div><div ref="element" class="chart" role="img" :aria-label="money ? '模拟万份收益历史曲线' : '模拟基金净值历史曲线'"/><p class="chart-note">数据来自平台模拟发布，历史表现不代表未来收益。</p></template>
