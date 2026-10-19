<template>
  <main id="home-content" class="product-home" tabindex="-1">
    <section class="home-hero" aria-labelledby="home-title">
      <div class="hero-copy">
        <p class="product-kicker">VideoKB <span>/</span> 可检索的视频知识库</p>
        <h1 id="home-title">让视频真正可理解，<br /><span>让每个答案有据可查。</span></h1>
        <p class="home-intro">把课程、会议和工作分享整理成知识库。跨视频找到讲解，查看引用与时间点，回到原片核对，再沿着问题继续追问。</p>
        <div class="hero-actions">
          <button class="product-primary hero-start" type="button" @click="$emit('enter', 'workspace')">
            {{ user ? '整理我的视频' : '开始整理视频' }} <AppIcon name="arrow" :size="17" />
          </button>
          <a class="hero-secondary" href="#how-it-works">了解使用方式</a>
        </div>
        <p class="hero-detail">当前支持跨视频搜索、单轮引用问答和原片定位；结论级证据校验与知识库多轮追问持续完善中。</p>
      </div>

      <figure class="home-preview" aria-label="VideoKB 答案与视频出处示例">
        <figcaption class="preview-caption-row">
          <span>示例片段</span>
          <span>内容为虚构，仅展示界面</span>
        </figcaption>
        <div class="evidence-example">
          <p class="preview-label">你可以这样问</p>
          <div class="preview-task-tabs" role="group" aria-label="切换问题示例">
            <button v-for="task in PRODUCT_TASKS" :key="task.id" type="button" :aria-pressed="selectedTask === task.id" @click="selectedTask = task.id">{{ task.label }}</button>
          </div>
          <p class="preview-question">{{ previewQuestion }}</p>
          <blockquote>{{ previewAnswer }}</blockquote>
          <p class="preview-label source-label">答案对应的原片</p>
          <div class="preview-citations">
            <button type="button" :aria-expanded="exampleOpen" @click="exampleOpen = !exampleOpen">
              <span class="citation-time">04:18</span>
              <span><strong>用户访谈复盘</strong><small>原文片段 · 示例资料</small></span>
              <AppIcon name="arrow" :size="15" />
            </button>
            <button type="button" :aria-expanded="exampleOpen" @click="exampleOpen = !exampleOpen">
              <span class="citation-time">12:36</span>
              <span><strong>产品增长课程</strong><small>原文片段 · 示例资料</small></span>
              <AppIcon name="arrow" :size="15" />
            </button>
          </div>
          <p v-if="exampleOpen" class="preview-disclosure">示例空间、视频和摘录均为虚构内容。实际结果来自当前账号可访问的资料。</p>
        </div>
        <button class="preview-own" type="button" @click="$emit('ask', currentTask.question)">在我的资料里提问 <AppIcon name="arrow" :size="15" /></button>
      </figure>
    </section>

    <section id="how-it-works" class="home-workflow" aria-labelledby="workflow-title">
      <div class="home-section-heading">
        <div><p class="section-overline">使用方式</p><h2 id="workflow-title">从视频，到能核对的出处。</h2></div>
        <p>处理状态清楚可见。准备好之后，问题和原文之间只隔一次搜索。</p>
      </div>
      <ol class="workflow-steps">
        <li><span class="workflow-number">1</span><div><h3>把资料放进来</h3><p>上传视频、粘贴公开链接，或批量导入。后台入库状态会单独显示。</p></div><button type="button" @click="$emit('enter', 'workspace')">导入资料 <AppIcon name="arrow" :size="15" /></button></li>
        <li><span class="workflow-number">2</span><div><h3>按问题找讲解</h3><p>在知识空间里跨视频检索；回答和原文片段分开呈现，方便核对。</p></div><button type="button" @click="$emit('enter', 'knowledge')">打开知识空间 <AppIcon name="arrow" :size="15" /></button></li>
        <li><span class="workflow-number">3</span><div><h3>回到视频那一刻</h3><p>打开出处查看时间位置。需要时，也可以让自己的 Agent 通过 MCP 只读检索。</p></div><button type="button" @click="$emit('enter', 'agent')">连接 Agent <AppIcon name="arrow" :size="15" /></button></li>
      </ol>
    </section>

    <section id="use-cases" class="home-tasks" aria-labelledby="tasks-title">
      <div class="task-section-copy"><p class="section-overline">从手头的问题开始</p><h2 id="tasks-title">先找到讲过的，<br />再决定怎么用。</h2><p>问题可以很具体。每条结果都带着资料来源，方便你检查上下文。</p></div>
      <ul class="home-task-list"><li v-for="task in PRODUCT_TASKS" :key="task.id"><button type="button" @click="$emit('ask', task.question)"><span><strong>{{ task.title }}</strong><small>{{ task.description }}</small></span><AppIcon name="arrow" :size="16" /></button></li></ul>
    </section>

    <section id="agent-usage" class="home-agent" aria-labelledby="agent-title">
      <div class="agent-copy"><p class="section-overline">可选连接</p><h2 id="agent-title">让你的 Agent<br />查得到，也说得清出处。</h2><p>VideoKB 提供 MCP 只读检索入口。访问范围跟随当前配置的上游账号；多个客户端目前不代表成员级权限隔离。</p><button class="text-action" type="button" @click="$emit('enter', 'agent')">查看连接方式 <AppIcon name="arrow" :size="15" /></button></div>
      <div class="agent-note"><p>连接后可以</p><ul><li>查看可用知识空间</li><li>搜索视频中的原文片段</li><li>沿时间戳回到来源视频</li></ul></div>
    </section>

    <section class="home-faq" aria-labelledby="faq-title"><div><p class="section-overline">使用前先了解</p><h2 id="faq-title">常见问题</h2></div><div><details><summary>适合哪些视频？</summary><p>优先用于有语音讲解、课件或屏幕文字的课程、分享与工作视频。动作演示和强视觉内容需要回到原片核对，文字提取不等于完整理解画面。</p></details><details><summary>上传后就可以提问了吗？</summary><p>上传完成后还有后台知识入库。等资料可检索后再提问；失败时可查看原因并重新解析，视频报告与知识入库是独立流程。</p></details><details><summary>Agent 会用我的账号登录吗？</summary><p>Agent 通过 MCP 客户端连接，读取范围跟随当前配置的上游账号。多个客户端令牌不代表已经实现团队成员隔离。</p></details><details><summary>可以生成学习卡、SOP 或团队共享吗？</summary><p>目前可检索证据、单轮问答，并按需分析和导出单视频报告。知识库级成果保存、团队共享与商业套餐仍在规划中。</p></details></div></section>

    <section class="home-cta"><p>从已经看过的视频开始。</p><button class="product-primary" type="button" @click="$emit('enter', 'workspace')">{{ user ? '回到我的视频' : '整理我的视频' }} <AppIcon name="arrow" :size="16" /></button></section>
    <footer class="home-footer"><a class="home-footer-brand" href="#home" @click.prevent="$emit('enter', 'home')"><BrandMark size="footer" /><span>Video<strong>KB</strong></span></a><span>找到依据，再继续工作。</span><a href="#how-it-works">使用方式 ↑</a></footer>
  </main>
</template>

<script setup>
import { computed, ref } from 'vue'
import AppIcon from './AppIcon.vue'
import BrandMark from './BrandMark.vue'
import { PRODUCT_TASKS } from '../productNavigation'

defineProps({ user: { type: Object, default: null } })
defineEmits(['enter', 'ask'])

const selectedTask = ref('steps')
const exampleOpen = ref(false)
const currentTask = computed(() => PRODUCT_TASKS.find(task => task.id === selectedTask.value))
const previewQuestion = computed(() => ({
  learn: '用户反馈里，哪些问题最值得优先处理？',
  steps: '整理用户访谈时，先做哪几步？',
  compare: '这两种需求排序方式有什么取舍？',
  research: '关于用户留存，哪些观点有原文依据？'
})[selectedTask.value])
const previewAnswer = computed(() => ({
  learn: '先把重复出现的问题和具体场景分开，再对照来源视频里的例子，避免把个别反馈当成普遍结论。',
  steps: '先按主题归纳原话，再标记频次和用户场景，最后回到相关片段核对语境。',
  compare: '一种方法更快看到高频问题，另一种能保留影响大小。选择时要看样本量和当前决策目标。',
  research: '把共识和分歧分别列出，结论旁边保留来源。没有原文支持的部分不补成事实。'
})[selectedTask.value])
</script>

<style scoped src="../styles/home.css"></style>
