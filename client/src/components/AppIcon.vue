<template>
  <svg :width="size" :height="size" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <path v-for="(path, index) in paths[name] || paths.file" :key="index" :d="path" />
  </svg>
</template>
<script setup>
defineProps({ name: { type: String, default: 'file' }, size: { type: [Number, String], default: 20 } })
const paths = {
  arrow: ['M5 12h14', 'm13 6 6 6-6 6'],
  search: ['M10.5 18a7.5 7.5 0 1 0 0-15 7.5 7.5 0 0 0 0 15', 'm16 16 5 5'],
  upload: ['M12 16V3', 'm7 8 5-5 5 5', 'M4 15v5h16v-5'],
  layers: ['m12 3 9 5-9 5-9-5 9-5', 'm3 12 9 5 9-5', 'm3 16 9 5 9-5'],
  file: ['M14 3H5v18h14V8l-5-5Z', 'M14 3v5h5', 'M8 12h8', 'M8 16h5'],
  list: ['M9 6h12', 'M9 12h12', 'M9 18h12', 'm3 6 1 1 2-3', 'm3 12 1 1 2-3', 'm3 18 1 1 2-3'],
  play: ['m9 5 11 7-11 7V5Z'],
  link: ['m10 13 4-4', 'M8 16 6 18a4 4 0 0 1-6-6l5-5a4 4 0 0 1 6 0', 'm16 8 2-2a4 4 0 0 0-6-6L7 5a4 4 0 0 0 0 6'],
  check: ['m5 12 4 4L19 6'],
  clock: ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18', 'M12 7v5l3 2'],
  plus: ['M12 5v14', 'M5 12h14']
}
</script>
