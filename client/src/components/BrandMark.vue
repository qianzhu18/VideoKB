<template>
  <img
    class="brand-mark"
    :class="`brand-mark--${size}`"
    src="/brand/videokb-symbol.svg"
    alt=""
    aria-hidden="true"
    decoding="async"
  />
</template>

<script setup>
defineProps({ size: { type: String, default: 'nav' } })
</script>
