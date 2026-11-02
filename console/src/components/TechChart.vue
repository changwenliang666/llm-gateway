<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from "vue";
import * as echarts from "echarts";

const props = defineProps({
  option: { type: Object, required: true }
});

const el = ref(null);
let chart;
let observer;

function render() {
  if (!chart) return;
  chart.setOption(props.option, true);
}

onMounted(() => {
  chart = echarts.init(el.value, null, { renderer: "canvas" });
  render();
  observer = new ResizeObserver(() => chart && chart.resize());
  observer.observe(el.value);
});

onBeforeUnmount(() => {
  observer?.disconnect();
  chart?.dispose();
  chart = null;
});

watch(() => props.option, render, { deep: true });
</script>

<template>
  <div ref="el" class="tech-chart"></div>
</template>
