<template>
  <section class="muku-panel" aria-labelledby="muku-title">
    <header class="muku-panel-head">
      <div class="muku-heading">
        <span class="muku-index">02</span>
        <div>
          <p class="muku-eyebrow">MULTI-SOURCE INGEST</p>
          <h2 id="muku-title">批量导入视频</h2>
        </div>
      </div>
      <div class="muku-connection" :class="{ online: available, offline: !available }" role="status">
        <i></i>{{ checking ? '检查 Muku…' : available ? 'Muku 已连接' : 'Muku 未连接' }}
        <a href="https://github.com/qianzhu18/Muku" target="_blank" rel="noreferrer" aria-label="查看 Muku 项目">↗</a>
      </div>
    </header>

    <template v-if="user">
      <div class="muku-grid">
        <div class="muku-input-column">
          <label class="muku-field-label" for="muku-urls">视频链接 <span>每行一条，最多 100 条</span></label>
          <textarea id="muku-urls" v-model="urlsText" rows="7" placeholder="https://www.bilibili.com/video/BV...&#10;https://youtu.be/...&#10;支持 Muku 可下载的平台链接"></textarea>
          <div class="muku-input-foot"><span>{{ links.length }} 条有效链接</span><span>下载、断点续跑、自动入库</span></div>
          <div class="muku-settings">
            <label>目标知识空间
              <select v-model="selectedSpaceId" :disabled="!spaces.length">
                <option v-for="space in spaces" :key="space.id" :value="String(space.id)">{{ space.name }}</option>
              </select>
            </label>
            <label>下载并发
              <select v-model.number="jobs"><option :value="1">1</option><option :value="2">2</option><option :value="4">4</option><option :value="8">8</option></select>
            </label>
          </div>
        </div>

        <aside class="muku-options">
          <p class="muku-eyebrow">CUSTOM PARSING</p>
          <h3>告诉 VideoKB 重点整理什么</h3>
          <textarea v-model="analysisGoal" rows="5" maxlength="500" placeholder="例如：提取课程中的核心概念、Java 代码示例、常见错误和复习问题。"></textarea>
          <p class="muku-hint">此目标会用于每个视频的 Java Agent 分析；语音、画面和时间戳仍会进入 RAG 知识库。</p>
          <button class="muku-submit" type="button" :disabled="!available || !links.length || links.length > 100 || busy" @click="submitBatch()">
            <span>{{ busy ? '批次处理中…' : `开始解析 ${links.length || ''} 个视频` }}</span><b aria-hidden="true">→</b>
          </button>
          <p v-if="error" class="muku-error" role="alert">{{ error }}</p>
        </aside>
      </div>

      <section v-if="batch" class="muku-progress" aria-live="polite">
        <div class="muku-progress-head"><strong>{{ stageLabel(batch.stage || batch.state) }}</strong><span>{{ batch.message }}</span></div>
        <div class="muku-progress-track" role="progressbar" :aria-valuenow="progress" aria-valuemin="0" aria-valuemax="100"><span :style="{ width: `${progress}%` }"></span></div>
        <ul class="muku-items">
          <li v-for="item in batch.items || []" :key="item.sourceUrl">
            <span :title="item.title || item.sourceUrl">{{ item.title || item.sourceUrl }}</span>
            <b :class="itemTone(item)">{{ itemLabel(item) }}</b>
          </li>
        </ul>
        <button v-if="['FAILED', 'PARTIAL'].includes(batch.state)" class="muku-resume" type="button" @click="submitBatch(batch.batchId)">用原批次继续</button>
      </section>
    </template>
    <p v-else class="muku-login-hint">登录后可批量导入到你的 VideoKB 知识空间。</p>
  </section>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { apiRequest } from './api'

const props = defineProps({ user: { type: Object, default: null } })
const emit = defineEmits(['imported'])
const urlsText = ref('')
const analysisGoal = ref('')
const jobs = ref(2)
const spaces = ref([])
const selectedSpaceId = ref('')
const available = ref(false)
const checking = ref(false)
const busy = ref(false)
const error = ref('')
const batch = ref(null)
const links = computed(() => [...new Set(urlsText.value.split(/\r?\n/).map(value => value.trim()).filter(Boolean))])
const progress = computed(() => {
  const items = batch.value?.items || []
  if (!items.length) return 0
  const terminal = items.filter(item => ['SUBMITTED', 'FAILED'].includes(item.state)).length
  const downloaded = items.filter(item => item.downloadState === 'DOWNLOADED' && !['SUBMITTED', 'FAILED'].includes(item.state)).length
  if (['COMPLETED', 'PARTIAL', 'FAILED'].includes(batch.value?.state)) return 100
  return Math.min(96, Math.round(((terminal + downloaded * 0.4) / items.length) * 100))
})
let pollTimer = null

function storageKey() { return `videokb.muku.batch.${props.user?.id || 'anonymous'}` }

async function readJson(path, options) {
  const response = await apiRequest(path, options)
  if (!response.ok) throw new Error(await response.text() || `请求失败 (${response.status})`)
  return response.json()
}

async function initialize() {
  clearTimeout(pollTimer)
  batch.value = null
  error.value = ''
  available.value = false
  if (!props.user) return
  checking.value = true
  try {
    const [status, ownedSpaces] = await Promise.all([
      readJson('/media/muku/status'),
      readJson('/knowledge/spaces')
    ])
    available.value = Boolean(status.available)
    spaces.value = Array.isArray(ownedSpaces) ? ownedSpaces : []
    if (!spaces.value.some(space => String(space.id) === selectedSpaceId.value)) {
      selectedSpaceId.value = String(spaces.value.find(space => space.systemDefault)?.id || spaces.value[0]?.id || '')
    }
    const savedBatchId = localStorage.getItem(storageKey())
    if (savedBatchId) pollBatch(savedBatchId)
  } catch (cause) {
    error.value = cause.message || '无法读取 Muku 状态和知识空间'
  } finally {
    checking.value = false
  }
}

async function submitBatch(resumeId = '') {
  error.value = ''
  if (!available.value) { error.value = 'Muku CLI 未安装在运行 Java 服务的环境中。'; return }
  if (!links.value.length) { error.value = '请至少粘贴一条完整视频链接。'; return }
  if (links.value.length > 100) { error.value = '单批最多 100 条链接。'; return }
  busy.value = true
  try {
    const accepted = await readJson('/media/batch-import', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        links: links.value,
        spaceId: selectedSpaceId.value ? Number(selectedSpaceId.value) : null,
        analysisGoal: analysisGoal.value.trim(),
        jobs: jobs.value,
        ...(resumeId ? { batchId: resumeId } : {})
      })
    })
    batch.value = accepted
    localStorage.setItem(storageKey(), accepted.batchId)
    pollBatch(accepted.batchId)
  } catch (cause) {
    error.value = cause.message || '批量导入失败'
  } finally {
    busy.value = false
  }
}

async function pollBatch(batchId) {
  clearTimeout(pollTimer)
  try {
    const state = await readJson(`/media/batch-import/${encodeURIComponent(batchId)}`)
    batch.value = state
    if (state.state === 'COMPLETED') {
      localStorage.removeItem(storageKey())
      emit('imported')
      return
    }
    if (['PARTIAL', 'FAILED'].includes(state.state)) localStorage.setItem(storageKey(), state.batchId)
    pollTimer = setTimeout(() => pollBatch(batchId), 1400)
  } catch (cause) {
    if (!batch.value) error.value = cause.message || '无法读取批次状态'
  }
}

function stageLabel(stage) {
  return ({ QUEUED: '排队中', DOWNLOADING: 'Muku 下载中', IMPORTING: '注册并提交解析', COMPLETED: '批次结束', FAILED: '批次失败' })[stage] || stage
}
function itemLabel(item) {
  if (item.state === 'SUBMITTED') return '已提交解析'
  if (item.state === 'IMPORTING') return '正在入库'
  if (item.state === 'FAILED' || item.downloadState === 'FAILED') return item.error || '失败'
  if (item.downloadState === 'DOWNLOADED') return '下载完成'
  return '等待下载'
}
function itemTone(item) {
  if (item.state === 'FAILED' || item.downloadState === 'FAILED') return 'failed'
  if (item.state === 'SUBMITTED') return 'done'
  return 'pending'
}

watch(() => props.user?.id, initialize, { immediate: true })
onBeforeUnmount(() => clearTimeout(pollTimer))
</script>

<style scoped>
.muku-panel { max-width: 1080px; margin: 28px auto 0; padding: 22px; border: 1px solid #30343a; border-radius: 12px; background: linear-gradient(135deg, rgba(18,20,24,.96), rgba(13,15,17,.96)); text-align: left; }
.muku-panel-head, .muku-heading, .muku-settings, .muku-input-foot, .muku-progress-head { display: flex; align-items: center; justify-content: space-between; gap: 14px; }
.muku-heading { justify-content: flex-start; gap: 13px; }
.muku-index { color: #c5f946; font: 11px ui-monospace, monospace; letter-spacing: .08em; }
.muku-eyebrow { margin: 0 0 4px; color: #899083; font: 9px ui-monospace, monospace; letter-spacing: .12em; }
h2, h3 { margin: 0; color: #e7e9e2; }
h2 { font-size: 16px; }
h3 { margin: 2px 0 12px; font-size: 13px; }
.muku-connection { display: flex; align-items: center; gap: 7px; color: #888e8b; font-size: 10px; }
.muku-connection i { width: 6px; height: 6px; border-radius: 50%; background: #666; }
.muku-connection.online i { background: #c5f946; box-shadow: 0 0 8px #c5f946; }
.muku-connection a { color: #c5f946; font-size: 14px; }
.muku-grid { display: grid; grid-template-columns: minmax(0, 1.2fr) minmax(280px, .8fr); gap: 22px; margin-top: 18px; }
.muku-field-label { display: flex; justify-content: space-between; gap: 10px; margin-bottom: 7px; color: #bcc0b9; font-size: 11px; }
.muku-field-label span, .muku-input-foot { color: #747a78; font-size: 9px; }
.muku-input-column textarea, .muku-options textarea { width: 100%; resize: vertical; border: 1px solid #34383d; border-radius: 5px; padding: 10px; color: #e3e5df; background: #0c0d0f; font: 11px/1.6 ui-monospace, monospace; }
.muku-input-column textarea:focus, .muku-options textarea:focus { outline: 1px solid #a4d232; border-color: #a4d232; }
.muku-input-foot { margin: 6px 0 12px; }
.muku-settings { justify-content: flex-start; gap: 12px; }
.muku-settings label { display: grid; gap: 5px; color: #8e9490; font-size: 9px; }
.muku-settings select { min-width: 118px; border: 1px solid #34383d; border-radius: 4px; padding: 7px 25px 7px 8px; color: #dfe2db; background: #101215; font-family: inherit; font-size: 10px; }
.muku-options { display: flex; flex-direction: column; min-width: 0; padding: 13px; border-left: 1px solid #2d3034; background: rgba(8,9,11,.36); }
.muku-options textarea { flex: 1; min-height: 96px; }
.muku-hint, .muku-help { margin: 8px 0 12px; color: #777d7a; font-size: 9px; line-height: 1.55; }
.muku-submit { display: flex; align-items: center; justify-content: space-between; min-height: 40px; padding: 0 13px; border: 0; border-radius: 4px; color: #11150a; background: #c5f946; cursor: pointer; font-weight: 750; font-size: 11px; }
.muku-submit:disabled { cursor: not-allowed; opacity: .4; }
.muku-submit b { font-size: 17px; }
.muku-error { margin: 8px 0 0; color: #ff958d; font-size: 10px; }
.muku-login-hint { margin: 17px 0 0; color: #858a87; font-size: 11px; }
.muku-progress { margin-top: 20px; padding-top: 15px; border-top: 1px solid #2b2e32; }
.muku-progress-head { justify-content: flex-start; color: #91978e; font-size: 10px; }
.muku-progress-head strong { color: #c5f946; font-size: 10px; white-space: nowrap; }
.muku-progress-track { height: 3px; margin: 10px 0; background: #282b2e; }
.muku-progress-track span { display: block; height: 100%; background: #c5f946; transition: width .3s ease; }
.muku-items { display: grid; gap: 6px; max-height: 160px; overflow: auto; margin: 0; padding: 0; list-style: none; }
.muku-items li { display: flex; align-items: center; justify-content: space-between; gap: 12px; color: #858b88; font-size: 9px; }
.muku-items li span { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.muku-items li b { flex: 0 0 auto; color: #c2c6c0; font-weight: 500; }
.muku-items li b.done { color: #c5f946; }
.muku-items li b.failed { color: #ff958d; }
.muku-resume { margin-top: 10px; padding: 7px 10px; border: 1px solid #596d2d; border-radius: 4px; background: #171c11; color: #c5f946; cursor: pointer; font-size: 10px; }
@media (max-width: 740px) { .muku-panel { padding: 16px; } .muku-grid { grid-template-columns: 1fr; gap: 14px; } .muku-options { border-left: 0; border-top: 1px solid #2d3034; padding: 14px 0 0; } .muku-progress-head { align-items: flex-start; flex-direction: column; gap: 4px; } }
</style>
