<template>
  <main class="knowledge-shell" aria-labelledby="knowledge-title">
    <section v-if="!user" class="knowledge-gate">
      <p class="eyebrow">知识与证据</p>
      <h1 id="knowledge-title" tabindex="-1">打开你的知识空间</h1>
      <p>空间、目录与视频来源由当前账号隔离。上传的视频会自动进入“未分类”，随后可在这里组织。</p>
      <button type="button" class="lime-button" @click="$emit('request-login')">登录以打开知识库</button>
    </section>

    <template v-else>
      <header class="knowledge-heading">
        <div>
          <p class="eyebrow">知识与证据</p>
          <h1 id="knowledge-title" tabindex="-1">知识空间</h1>
          <p>围绕主题整理视频与笔记。带着问题查证，再按时间回到原始讲解。</p>
        </div>
        <div class="knowledge-summary" aria-label="当前空间摘要">
          <span><b>{{ spaces.length }}</b> 空间</span>
          <span><b>{{ collections.length }}</b> 目录</span>
          <span><b>{{ sourceCount }}</b> 来源</span>
          <button type="button" class="lime-button" @click="$emit('request-import')">＋ 导入视频</button>
        </div>
      </header>

      <nav class="knowledge-journey" aria-label="知识空间使用步骤"><button type="button" @click="$emit('request-import')"><span>01</span> 导入资料</button><span aria-hidden="true">→</span><button type="button" @click="focusKnowledgeSearch"><span>02</span> 查找证据</button><span aria-hidden="true">→</span><button type="button" @click="openAgentConnection"><span>03</span> 连接 Agent</button></nav>
      <p v-if="error" class="knowledge-banner is-error" role="alert">{{ error }}</p>
      <p v-else-if="notice" class="knowledge-banner" role="status">{{ notice }}</p>

      <section class="knowledge-workbench" :aria-busy="loading">
        <aside class="knowledge-rail">
          <div class="rail-heading">
            <span>知识空间</span>
            <button type="button" class="icon-action" aria-label="新建知识空间" @click="toggleSpaceComposer">+</button>
          </div>

          <form v-if="spaceComposerOpen" class="rail-form" @submit.prevent="createSpace">
            <label>
              <span>空间名称</span>
              <input v-model="newSpaceName" maxlength="100" placeholder="例如：学习" autofocus />
            </label>
            <label>
              <span>一句描述，可选</span>
              <input v-model="newSpaceDescription" maxlength="500" placeholder="Java、分布式与面试复习" />
            </label>
            <div class="form-actions">
              <button type="button" @click="closeSpaceComposer">取消</button>
              <button class="text-action" :disabled="saving || !newSpaceName.trim()">创建</button>
            </div>
          </form>

          <nav class="space-list" aria-label="知识空间列表">
            <button
              v-for="space in spaces"
              :key="space.id"
              type="button"
              class="space-item drop-target"
              :class="{ active: space.id === selectedSpaceId, 'drop-hover': hoverDropKey === `space:${space.id}` }"
              :aria-label="`拖动内容源到 ${space.name} 归类`"
              @click="selectSpace(space.id)"
              @dragover.prevent="hoverDropKey = `space:${space.id}`"
              @dragleave="clearHover(`space:${space.id}`)"
              @drop.prevent="dropSource($event, space.id, null)"
            >
              <span class="space-mark">{{ space.systemDefault ? '·' : '#' }}</span>
              <span class="space-copy"><strong>{{ space.name }}</strong><small>{{ space.systemDefault ? '默认接入区' : (space.description || '自定义空间') }}</small></span>
            </button>
          </nav>

          <details class="advanced-import"><summary>导入本机目录（高级）</summary>
          <div class="rail-heading"><span>本地目录导入</span></div>
          <form class="rail-form compact-form" @submit.prevent="runIngest(true)">
            <label>
              <span>本机视频目录（需在授权根目录内）</span>
              <input v-model="ingestPath" placeholder="/path/to/videos" aria-label="导入目录" />
            </label>
            <div class="form-actions">
              <button type="submit" class="text-action" :disabled="ingestBusy || !ingestPath.trim() || !selectedSpaceId">
                {{ ingestBusy ? '处理中…' : '扫描预览' }}
              </button>
              <button type="button" class="text-action" :disabled="ingestBusy || !ingestPath.trim() || !selectedSpaceId || !ingestPlan" @click="applyIngest">
                导入
              </button>
            </div>
            <p v-if="ingestError" class="ingest-error" role="alert">{{ ingestError }}</p>
          </form>
          <div v-if="ingestPlan" class="ingest-plan">
            <p class="ingest-plan-head">
              {{ ingestPlan.dryRun ? '预览' : '已导入' }}：
              新建 {{ ingestPlan.createdCount }} · 变更 {{ ingestPlan.changedCount }} ·
              移动 {{ ingestPlan.movedCount }} · 删除 {{ ingestPlan.deletedCount }} ·
              不变 {{ ingestPlan.unchangedCount }} · 错误 {{ ingestPlan.errorCount }}
            </p>
            <ul>
              <li v-for="(action, index) in ingestActions" :key="index">
                <span :class="['ingest-action', `is-${action.action.toLowerCase()}`]">{{ action.action }}</span>
                <span class="ingest-path">{{ action.path.split('/').pop() }}</span>
                <small v-if="action.error">{{ action.error }}</small>
              </li>
            </ul>
          </div>

          <div class="rail-divider"></div>
          </details>
          <div class="rail-heading">
            <span>文字笔记</span>
            <button
              type="button"
              class="icon-action"
              :disabled="!selectedSpaceId"
              aria-label="上传口播稿或笔记"
              @click="scriptComposerOpen = !scriptComposerOpen"
            >+</button>
          </div>
          <form v-if="scriptComposerOpen" class="rail-form" @submit.prevent="createScript">
            <label>
              <span>标题（留空取首行）</span>
              <input v-model="newScriptTitle" maxlength="255" placeholder="例如：缓存击穿口播稿" />
            </label>
            <label>
              <span>粘贴 Markdown 或纯文本，空行分段</span>
              <textarea
                v-model="newScriptContent"
                rows="6"
                maxlength="200000"
                placeholder="口播稿 / 课程笔记；每个段落会成为独立的知识单元并进入跨视频检索"
                aria-label="脚本内容"
              ></textarea>
            </label>
            <div class="form-actions">
              <button type="button" @click="scriptComposerOpen = false">取消</button>
              <button class="text-action" :disabled="saving || !newScriptContent.trim()">
                {{ saving ? '索引中…' : '上传并索引' }}
              </button>
            </div>
          </form>

          <div class="rail-divider"></div>
          <details ref="agentConnectionPanel" class="agent-connection">
          <summary>连接 Agent <span class="connection-caption">MCP 只读接入</span></summary>
          <div class="rail-heading">
            <span>端点探测</span>
            <span class="mcp-dot" :class="mcpAlive === null ? 'unknown' : (mcpAlive ? 'on' : 'off')"
                  :title="mcpAlive === null ? '探测中' : (mcpAlive ? '端点有响应，仍需验证令牌与工具调用' : '无法从当前浏览器探测端点')"></span>
          </div>
          <div class="mcp-panel">
            <p class="mcp-hint">在支持 HTTP MCP 的客户端中添加以下配置，填入管理员提供的连接令牌。</p>
            <button type="button" class="mcp-endpoint" title="点击复制端点" @click="copyText(mcpEndpoint, '端点已复制')">
              {{ mcpEndpoint }}
            </button>
            <button type="button" class="mcp-config" title="点击复制客户端配置" @click="copyText(mcpClientConfig, '客户端配置已复制')">{{ mcpClientConfig }}</button>
            <p class="mcp-hint">接入后先列空间，再查资料与原文。端点有响应不代表已认证成功；当前所有客户端共用一个上游账号。</p>
          </div>

          <div class="rail-divider"></div>
          </details>
          <div class="rail-heading folder-heading">
            <span>目录</span>
            <button type="button" class="icon-action" :disabled="!selectedSpaceId" aria-label="新建目录" @click="openCollectionComposer(selectedCollectionId)">+</button>
          </div>

          <form v-if="collectionComposerOpen" class="rail-form compact-form" @submit.prevent="createCollection">
            <label>
              <span>{{ collectionParent ? `新建于 ${collectionParent.name}` : '新建根目录' }}</span>
              <input v-model="newCollectionName" maxlength="100" placeholder="目录名称" autofocus />
            </label>
            <div class="form-actions">
              <button type="button" @click="closeCollectionComposer">取消</button>
              <button class="text-action" :disabled="saving || !newCollectionName.trim()">创建</button>
            </div>
          </form>

          <div class="folder-list">
            <button
              type="button"
              class="folder-item root-item drop-target"
              :class="{ active: selectedCollectionId === null, 'drop-hover': hoverDropKey === 'collection:root' }"
              :disabled="!selectedSpaceId"
              @click="selectCollection(null)"
              @dragover.prevent="hoverDropKey = 'collection:root'"
              @dragleave="clearHover('collection:root')"
              @drop.prevent="dropSource($event, selectedSpaceId, null)"
            >
              <span>⌂</span><strong>空间根目录</strong>
            </button>
            <button
              v-for="collection in collections"
              :key="collection.id"
              type="button"
              class="folder-item drop-target"
              :class="{ active: collection.id === selectedCollectionId, 'drop-hover': hoverDropKey === `collection:${collection.id}` }"
              :style="{ '--depth': collectionDepth(collection) }"
              :title="collection.path"
              @click="selectCollection(collection.id)"
              @dragover.prevent="hoverDropKey = `collection:${collection.id}`"
              @dragleave="clearHover(`collection:${collection.id}`)"
              @drop.prevent="dropSource($event, selectedSpaceId, collection.id)"
            >
              <span>⌁</span><strong>{{ collection.name }}</strong>
              <small>{{ collection.path }}</small>
            </button>
          </div>
        </aside>

        <section class="source-pane">
          <div v-if="parseProgress.active" class="parse-progress" role="status">
            <div class="parse-progress-head">
              <strong>{{ parseProgress.label }}</strong>
              <span>{{ parseProgress.readyCount }}/{{ parseProgress.total }} 已入库</span>
              <span v-if="parseProgress.etaText" class="parse-eta">{{ parseProgress.etaText }}</span>
            </div>
            <div class="parse-progress-bar" aria-hidden="true">
              <span :style="{ width: `${Math.round((parseProgress.readyCount / Math.max(1, parseProgress.total)) * 100)}%` }"></span>
            </div>
          </div>
          <header class="source-header">
            <div>
              <p class="source-path">{{ selectedSpace?.name || '正在载入' }} <span>/</span> {{ selectedCollection?.name || '根目录' }}</p>
              <h2>{{ selectedCollection?.name || selectedSpace?.name || '知识来源' }}</h2>
            </div>
            <div class="header-tools">
              <form class="tag-filter" @submit.prevent="applyTagFilter">
                <input v-model="tagFilter" maxlength="64" placeholder="按标签筛选" aria-label="按标签筛选" />
                <button v-if="activeTag" type="button" class="tag-clear" aria-label="清除标签筛选" @click="clearTagFilter">×</button>
              </form>
              <button
                v-if="noJobCount > 0"
                type="button"
                class="subtle-button analyze-pending-button"
                :disabled="dispatchingPending"
                :title="'只补投从未开始解析的视频；失败过的视频请用卡片上的「重新解析」从存档直接补索引'"
                @click="analyzePending"
              >{{ dispatchingPending ? '派发中…' : `解析未入库视频（${noJobCount}）` }}</button>
              <button type="button" class="subtle-button" :disabled="loading" @click="refreshCurrent">刷新</button>
            </div>
          </header>

          <form class="cross-search" @submit.prevent="askKnowledge">
            <div class="cross-search-row">
              <input
                v-model="searchQuery"
                maxlength="500"
                ref="knowledgeSearchInput" placeholder="从这些资料中，你想找到什么？"
                aria-label="向知识空间提问"
              />
              <button type="submit" class="lime-button" :disabled="asking || searching || !searchQuery.trim()">
                {{ asking ? answerPhaseLabel : '提问并查看引用' }}
              </button>
              <button type="button" class="subtle-button evidence-only-button" :disabled="asking || searching || !searchQuery.trim()" @click="searchKnowledge">
                {{ searching ? '检索中…' : '仅搜证据' }}
              </button>
            </div>
            <p class="cross-search-hint">只查询当前空间与目录。回答附原文引用，点击时间回看；证据不足会说明缺口。</p>
            <p v-if="answerError || searchError" class="cross-search-error" role="alert">{{ answerError || searchError }}</p>
          </form>

          <div v-if="!searched && !answerResult && !asking" class="query-starters" aria-label="问题起点"><span>从一个任务开始</span><button v-for="task in PRODUCT_TASKS" :key="task.id" type="button" @click="searchQuery = task.question; focusKnowledgeSearch()">{{ task.label }}</button></div>
          <section v-if="asking && answerDraft" class="answer-results answer-results-draft" aria-live="polite">
            <header class="answer-results-head">
              <div class="answer-status">
                <span class="answer-status-mark">实时草稿</span>
                <span>{{ answerPhaseLabel }} · 引用核验完成后才会作为正式答案显示</span>
              </div>
            </header>
            <p class="answer-copy">{{ answerDraft }}<span class="streaming-caret" aria-hidden="true">▍</span></p>
          </section>

          <section v-if="answerResult" class="answer-results" :class="{ 'is-insufficient': answerResult.answerability !== 'SUPPORTED' }" aria-live="polite">
            <header class="answer-results-head">
              <div class="answer-status">
                <span class="answer-status-mark">{{ answerResult.answerability === 'SUPPORTED' ? '有证据支持' : answerResult.answerability === 'NOT_READY' ? '资料未就绪' : '证据不足' }}</span>
                <span v-if="answerResult.citations?.length">{{ answerResult.citations.length }} 条引用</span>
              </div>
              <button type="button" class="subtle-button" @click="answerResult = null">收起</button>
            </header>
            <p class="answer-copy">{{ answerResult.answer }}</p>
            <ul v-if="answerResult.citations?.length" class="answer-citation-list" aria-label="回答引用">
              <li v-for="(citation, index) in answerResult.citations" :key="citation.segmentId" class="answer-citation">
                <div class="answer-citation-meta">
                  <span class="citation-index">{{ String(index + 1).padStart(2, '0') }}</span>
                  <strong :title="citation.title">{{ citation.title || '未命名来源' }}</strong>
                  <button
                    v-if="citation.mediaId != null"
                    type="button"
                    class="citation-time"
                    :aria-label="`打开 ${citation.title} ${formatMs(citation.startMs)} 的视频证据`"
                    @click="$emit('open-evidence', { mediaId: citation.mediaId, timestampMs: citation.startMs })"
                  >{{ formatMs(citation.startMs) }}–{{ formatMs(citation.endMs) }} ↗</button>
                  <span v-else class="citation-source-type">文本来源</span>
                </div>
                <p v-if="citation.claim" class="citation-claim">{{ citation.claim }}</p>
                <blockquote>{{ citation.quote }}</blockquote>
              </li>
            </ul>
            <ul v-if="answerResult.warnings?.length" class="answer-warnings">
              <li v-for="warning in answerResult.warnings" :key="warning">{{ warning }}</li>
            </ul>
          </section>

          <section v-if="searched" class="search-results" aria-label="跨视频检索结果">
            <header class="search-results-head">
              <span>检索结果</span>
              <button type="button" class="subtle-button" @click="closeSearchResults">收起</button>
            </header>
            <p v-for="warning in searchWarnings" :key="warning" class="search-empty">{{ warning }}</p>
            <!-- 检索是空间级的：空空间里"无证据"是必然结果，必须提示用户切换空间而不是让他误判检索坏了 -->
            <p v-if="searchResults.length === 0 && searchWarnings.length === 0" class="search-empty">
              未检索到支持证据。{{ sources.length === 0 ? '当前空间还没有内容源——请检查左侧是否选错了空间（检索只在所选空间内进行）。' : '换一个更贴近视频原话的问法再试。' }}
            </p>
            <ul v-else class="search-hit-list">
              <li v-for="hit in searchResults" :key="hit.segmentId" class="search-hit">
                <div class="search-hit-meta">
                  <strong :title="hit.title">{{ hit.title }}</strong>
                  <button v-if="hit.mediaId" type="button" class="search-hit-time"
                    :aria-label="`回看 ${hit.title} ${formatMs(hit.startMs)}`"
                    @click="$emit('open-evidence', { mediaId: hit.mediaId, timestampMs: hit.startMs })"
                  >{{ formatMs(hit.startMs) }} – {{ formatMs(hit.endMs) }}</button>
                  <span v-else class="search-hit-time">{{ formatMs(hit.startMs) }} – {{ formatMs(hit.endMs) }}</span>
                  <span class="search-hit-kind">{{ hit.sourceType === 'SCRIPT' ? '脚本' : '视频' }}·{{ ({ vector: '语义', keyword: '关键词', bm25: '词法', hybrid: '混合检索' })[hit.matchType] || hit.matchType }}</span>
                </div>
                <p>{{ hit.transcript || hit.ocrText || hit.summary }}</p>
                <details v-if="hit.evidence?.length" class="original-evidence">
                  <summary>原文片段（{{ hit.evidence.length }}）</summary>
                  <div v-for="raw in hit.evidence" :key="raw.segmentId">
                    <button v-if="raw.mediaId" type="button" class="search-hit-time"
                      :aria-label="`回看原文 ${hit.title} ${formatMs(raw.startMs)}`"
                      @click="$emit('open-evidence', { mediaId: raw.mediaId, timestampMs: raw.startMs })"
                    >{{ formatMs(raw.startMs) }} – {{ formatMs(raw.endMs) }}</button>
                    <span v-else>{{ formatMs(raw.startMs) }} – {{ formatMs(raw.endMs) }}</span>
                    <p>{{ raw.transcript || raw.ocrText }}</p>
                  </div>
                </details>
              </li>
            </ul>
          </section>

          <div v-if="loading" class="knowledge-loading" role="status">正在读取知识资产...</div>
          <div v-else-if="sources.length === 0" class="source-empty">
            <p class="empty-index">000</p>
            <h3>从一段视频，开始积累知识</h3>
            <p>先导入视频，再查看入库状态。已有资料也可通过“移动”或“同时归入”整理到这里。</p>
            <button type="button" class="lime-button" @click="$emit('request-import')">导入我的视频</button>
          </div>
          <ul v-else class="source-list">
            <li
              v-for="source in sources"
              :key="source.id"
              class="source-row"
              :class="{ 'is-dragging': draggingSource?.id === source.id }"
              draggable="true"
              :title="`拖动以移动，按住 Option / Alt 拖动可同时归入另一目录（${source.title}）`"
              @dragstart="onSourceDragStart($event, source)"
              @dragend="onSourceDragEnd"
            >
              <div class="source-type">{{ source.sourceType === 'VIDEO' ? 'VID' : source.sourceType }}</div>
              <div class="source-copy">
                <h3 :title="source.title">{{ source.title }}</h3>
                <p>
                  <span :class="['status-chip', `status-${(liveStatus(source) || source.status).toLowerCase()}`]">{{ liveStatus(source) || source.status }}</span>
                  <span v-if="liveStage(source)">&nbsp;· {{ liveStage(source) }}</span>
                  <span>版本 {{ source.currentVersion }}</span>
                  <span>{{ formatDate(source.updatedAt) }}</span>
                </p>
                <p v-if="(source.tags?.length || tagEditorId === source.id)" class="source-tags">
                  <span v-for="tag in source.tags || []" :key="tag" class="tag-chip">
                    {{ tag }}
                    <button type="button" class="tag-remove" :aria-label="`移除标签 ${tag}`" :disabled="saving" @click="removeTag(source, tag)">×</button>
                  </span>
                  <button
                    v-if="tagEditorId !== source.id"
                    type="button"
                    class="tag-add"
                    :disabled="saving"
                    @click="toggleTagEditor(source)"
                  >+ 标签</button>
                </p>
                <form v-if="tagEditorId === source.id" class="tag-editor" @submit.prevent="commitTagEditor(source)">
                  <input
                    v-model="newTagDraft"
                    maxlength="64"
                    placeholder="输入标签，回车确认"
                    aria-label="新标签"
                    autofocus
                  />
                  <button type="button" class="subtle-button" @click="closeTagEditor">取消</button>
                  <button type="submit" class="subtle-button text-action" :disabled="saving || !newTagDraft.trim()">添加</button>
                </form>
                <div v-if="linksPanelId === source.id" class="links-panel" :aria-busy="linksBusy">
                  <div class="links-actions">
                    <button
                      type="button"
                      class="subtle-button"
                      :disabled="linksBusy"
                      @click="suggestLinks(source)"
                    >{{ linksBusy ? '匹配中…' : '生成关联建议' }}</button>
                    <span class="links-hint">按语义相似度推荐视频片段配对；建议≠事实，确认后才生效。</span>
                  </div>
                  <p v-if="linkNotice" class="links-notice" role="status">{{ linkNotice }}</p>
                  <p v-if="!linksBusy && links.length === 0" class="links-empty">暂无关联记录。</p>
                  <ul v-else class="link-list">
                    <li v-for="link in links" :key="link.id" class="link-row">
                      <div class="link-head">
                        <span :class="['status-chip', `link-${link.status.toLowerCase()}`]">{{ linkStatusText(link.status) }}</span>
                        <strong :title="link.targetTitle">{{ link.targetTitle }}</strong>
                        <span v-if="link.targetStartMs != null" class="link-time">{{ formatMs(link.targetStartMs) }} – {{ formatMs(link.targetEndMs) }}</span>
                        <span v-if="link.confidence != null" class="link-score">{{ (link.confidence * 100).toFixed(0) }}%</span>
                        <template v-if="link.status === 'SUGGESTED'">
                          <button type="button" class="subtle-button" :disabled="linksBusy" @click="confirmLink(link)">确认</button>
                          <button type="button" class="subtle-button" :disabled="linksBusy" @click="rejectLink(link)">拒绝</button>
                        </template>
                      </div>
                      <p class="link-pair">
                        <span class="link-side">稿</span>{{ link.sourceExcerpt }}
                        <span class="link-side">视频</span>{{ link.targetExcerpt }}
                      </p>
                    </li>
                  </ul>
                </div>
              </div>
              <button
                v-if="source.sourceType === 'SCRIPT'"
                type="button"
                class="source-move"
                @click="toggleLinksPanel(source)"
              >关联{{ linksPanelId === source.id ? ' ▴' : '' }}</button>
              <button
                v-if="ingestStateOf(source) === 'FAILED'"
                type="button"
                class="source-move"
                :disabled="recoveringId === source.id"
                @click="reindexSource(source)"
              >{{ recoveringId === source.id ? '恢复中…' : '重新解析' }}</button>
              <button type="button" class="source-move" @click="openMove(source)">移动</button>
              <button type="button" class="source-move" @click="openMove(source, 'reference')">同时归入…</button>
              <button v-if="source.placementId" type="button" class="source-move" @click="removeReference(source)">移除此处引用</button>
            </li>
          </ul>

          <form v-if="movingSource" class="move-tray" @submit.prevent="moveSource">
            <div>
              <p>{{ locationMode === 'reference' ? '同时归入另一目录（共享同一份内容）' : '移动当前目录中的引用' }}</p>
              <strong>{{ movingSource.title }}</strong>
            </div>
            <label>目标空间
              <select v-model="moveSpaceId" @change="loadMoveCollections">
                <option v-for="space in spaces" :key="space.id" :value="space.id">{{ space.name }}</option>
              </select>
            </label>
            <label>目标目录
              <select v-model="moveCollectionId">
                <option :value="null">空间根目录</option>
                <option v-for="collection in moveCollections" :key="collection.id" :value="collection.id">{{ collection.path }}</option>
              </select>
            </label>
            <div class="move-actions">
              <button type="button" @click="closeMove">取消</button>
              <button class="lime-button" :disabled="saving">{{ locationMode === 'reference' ? '添加引用' : '确认移动' }}</button>
            </div>
          </form>
        </section>
      </section>
    </template>
  </main>
</template>

<script setup>
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { apiRequest } from './api'
import { PRODUCT_TASKS } from './productNavigation'

const props = defineProps({ user: { type: Object, default: null }, queryIntent: { type: Object, default: null }, sectionIntent: { type: Object, default: null } })
defineEmits(['request-login', 'open-evidence', 'request-import'])
const knowledgeSearchInput = ref(null)
const agentConnectionPanel = ref(null)
function focusKnowledgeSearch() { knowledgeSearchInput.value?.focus(); knowledgeSearchInput.value?.scrollIntoView({ block: 'center' }) }
function openAgentConnection() { if (agentConnectionPanel.value) { agentConnectionPanel.value.open = true; agentConnectionPanel.value.scrollIntoView({ block: 'center' }) } }

const spaces = ref([])
const collections = ref([])
const sources = ref([])
const selectedSpaceId = ref(null)
const selectedCollectionId = ref(null)
const loading = ref(false)
const saving = ref(false)
const dispatchingPending = ref(false)
const pendingCount = computed(() => sources.value.filter(source => source.status === 'PENDING').length)
// 解析进度可视化：PENDING 源存在时轮询任务台账，把"未投递/排队/解析中/失败"如实映射到卡片。
const taskStates = ref({})
// 每个源的真实入库状态：以台账为准——没有台账记录=从未投递，失败必须带原因示人，
// 不允许把"没投递"和"失败"伪装成"排队"。
const ingestStateOf = source => {
  const task = taskStates.value[source.mediaId]
  // A failed rebuild keeps the published source READY, but its job failure remains visible.
  if (task?.state === 'PROCESSING') return 'PARSING'
  if (task?.state === 'QUEUED') return 'QUEUED'
  if (task?.state === 'FAILED') return 'FAILED'
  if (source.status === 'READY' || source.status === 'FAILED') return source.status
  if (source.status && source.status !== 'PENDING') return source.status
  return 'NO_JOB'
}
const noJobCount = computed(() => sources.value.filter(s => ingestStateOf(s) === 'NO_JOB').length)
const parseProgress = computed(() => {
  const total = sources.value.length
  const readyCount = sources.value.filter(s => s.status === 'READY').length
  const counting = state => sources.value.filter(s => ingestStateOf(s) === state).length
  const parsing = counting('PARSING')
  const queued = counting('QUEUED')
  const noJob = counting('NO_JOB')
  const failed = counting('FAILED')
  const parts = []
  if (parsing) parts.push(`解析中 ${parsing}`)
  if (queued) parts.push(`排队 ${queued}`)
  if (noJob) parts.push(`未投递 ${noJob}`)
  if (failed) parts.push(`失败 ${failed}`)
  return {
    active: parts.length > 0,
    total,
    readyCount,
    parsing,
    queued,
    noJob,
    failed,
    label: parts.join(' · '),
    // 不提供固定公式的 ETA：解析时长取决于视频长度与模型吞吐，编一个数字就是撒谎。
    etaText: '',
  }
})
const liveStatus = source => {
  const state = ingestStateOf(source)
  if (state === 'PENDING') return null
  return state === 'PARSING' ? 'ANALYZING' : state
}
const liveStage = source => {
  const task = taskStates.value[source.mediaId]
  if (task?.state === 'FAILED') return `${source.status === 'READY' ? '重建失败，原版本仍可检索' : '入库失败'}：${task.errorMessage || '可重新解析'}`
  if (task?.state === 'QUEUED') return task.errorMessage || '等待知识入库'
  if (task?.state === 'PROCESSING') return task.stage === 'INDEXING' ? '分块与向量化中' : '转写与画面识别中'
  if (ingestStateOf(source) === 'NO_JOB') return '尚未创建知识入库任务'
  return null
}
let progressTimer = null
let sawRunningTask = false
const stopProgressPolling = () => {
  if (progressTimer) { clearInterval(progressTimer); progressTimer = null }
  sawRunningTask = false
}
const startProgressPolling = () => {
  if (progressTimer) return
  progressTimer = setInterval(async () => {
    if (!selectedSpaceId.value || !sources.value.some(s => ['NO_JOB', 'QUEUED', 'PARSING'].includes(ingestStateOf(s)))) { stopProgressPolling(); return }
    try {
      const spaceId = selectedSpaceId.value
      const collectionId = selectedCollectionId.value
      const tag = tagFilter.value
      const [freshSources, tasks] = await Promise.all([
        request(`/knowledge/sources?${new URLSearchParams({ spaceId,
          ...(collectionId == null ? {} : { collectionId }), ...(tag ? { tag } : {}) })}`),
        request('/knowledge/ingest-jobs'),
      ])
      if (spaceId !== selectedSpaceId.value || collectionId !== selectedCollectionId.value || tag !== tagFilter.value) return
      sources.value = freshSources
      const mediaIds = new Set(freshSources.map(s => s.mediaId))
      taskStates.value = Object.fromEntries(
        (tasks || []).filter(t => mediaIds.has(t.mediaId)).map(t => [t.mediaId, t]))
      const running = Object.values(taskStates.value)
        .some(t => t.state === 'QUEUED' || t.state === 'PROCESSING')
      // 批次结束的判定是"台账里没有在跑的任务"，而不是"没有 PENDING 源"——
      // 失败源的 source.status 停留在 PENDING，旧判定会让失败提醒永远不触发。
      if (running) {
        sawRunningTask = true
      } else if (sawRunningTask) {
        stopProgressPolling()
        const failedSources = freshSources.filter(s => ingestStateOf(s) === 'FAILED')
        const unresolved = failedSources.length
        const readyNow = freshSources.filter(s => s.status === 'READY').length
        notice.value = unresolved > 0
          ? `解析批次结束：${readyNow} 个已入库，${unresolved} 个未能自动恢复（卡片上可重新解析）`
          : `解析批次结束：${readyNow}/${freshSources.length} 已入库，现在可以直接向这个知识空间提问了`
      }
    } catch {
      // 轮询失败静默：下一次 tick 会重试，不打断用户。
    }
  }, 8000)
}
watch(pendingCount, count => { if (count > 0) startProgressPolling() }, { immediate: true })
onUnmounted(() => {
  stopProgressPolling()
  activeAskController?.abort()
})
const error = ref('')
const notice = ref('')
const spaceComposerOpen = ref(false)
const newSpaceName = ref('')
const newSpaceDescription = ref('')
const collectionComposerOpen = ref(false)
const collectionParentId = ref(null)
const newCollectionName = ref('')
const movingSource = ref(null)
const locationMode = ref('move')
const moveSpaceId = ref(null)
// 访达式归类：源卡片可拖动，左侧空间/目录是 drop 目标。
const draggingSource = ref(null)
const hoverDropKey = ref(null)
const moveCollectionId = ref(null)
const moveCollections = ref([])
const tagFilter = ref('')
const tagEditorId = ref(null)
const newTagDraft = ref('')
const searchQuery = ref('')
const asking = ref(false)
const answerResult = ref(null)
const answerDraft = ref('')
const answerPhase = ref('retrieving')
const answerError = ref('')
let answerRequestId = 0
let activeAskController = null
const answerPhaseLabel = computed(() => ({
  retrieving: '检索证据中…',
  generating: '正在生成答案…',
  verifying: '正在核验引用…'
})[answerPhase.value] || '正在处理…')
const searching = ref(false)
const searched = ref(false)
const searchResults = ref([])
const searchError = ref('')
const searchWarnings = ref([])
let searchRequestId = 0
const ingestPath = ref('')
const ingestBusy = ref(false)
const ingestPlan = ref(null)
const ingestError = ref('')
const scriptComposerOpen = ref(false)
const newScriptTitle = ref('')
const newScriptContent = ref('')
const mcpAlive = ref(null)
const mcpEndpoint = computed(() => import.meta.env.VITE_MCP_ENDPOINT || `${location.protocol}//${location.hostname}:9091/mcp`)
const mcpClientConfig = computed(() =>
  JSON.stringify({ mcpServers: { 'dovideo-knowledge': { url: mcpEndpoint.value, headers: { Authorization: 'Bearer <令牌>' } } } }, null, 2))

function probeMcpAdapter() {
  mcpAlive.value = null
  fetch(mcpEndpoint.value, { method: 'GET' })
    .then(() => { mcpAlive.value = true })   // 401/405 都说明适配器在响应
    .catch(() => { mcpAlive.value = false })
}

async function copyText(text, message) {
  try {
    await navigator.clipboard.writeText(text)
    notice.value = message
  } catch {
    notice.value = '复制失败，请手动选择复制'
  }
}

const linksPanelId = ref(null)
const links = ref([])
const linksBusy = ref(false)
const linkNotice = ref('')

const ingestActions = computed(() => {
  if (!ingestPlan.value?.plan) return []
  try {
    return JSON.parse(ingestPlan.value.plan).actions || []
  } catch {
    return []
  }
})

const selectedSpace = computed(() => spaces.value.find(space => space.id === selectedSpaceId.value) || null)
const selectedCollection = computed(() => collections.value.find(collection => collection.id === selectedCollectionId.value) || null)
const collectionParent = computed(() => collections.value.find(collection => collection.id === collectionParentId.value) || null)
const sourceCount = computed(() => sources.value.length)
const activeTag = computed(() => tagFilter.value.trim())

watch([() => props.user?.id, () => props.queryIntent?.id], async ([userId]) => {
  if (!userId || !props.queryIntent?.text) return
  await nextTick()
  searchQuery.value = props.queryIntent.text
}, { immediate: true, flush: 'post' })
watch(() => props.sectionIntent?.id, async () => {
  if (!props.user) return
  await nextTick()
  if (props.sectionIntent?.section === 'agent') openAgentConnection()
}, { immediate: true, flush: 'post' })

watch(() => props.user?.id, async userId => {
  resetState()
  if (userId) {
    probeMcpAdapter()
    await loadSpaces()
  }
}, { immediate: true })

function resetState() {
  answerRequestId += 1
  activeAskController?.abort()
  activeAskController = null
  asking.value = false
  spaces.value = []
  collections.value = []
  sources.value = []
  selectedSpaceId.value = null
  selectedCollectionId.value = null
  error.value = ''
  notice.value = ''
  answerResult.value = null
  answerDraft.value = ''
  answerPhase.value = 'retrieving'
  answerError.value = ''
  searchResults.value = []
  searched.value = false
  tagFilter.value = ''
  closeTagEditor()
  closeSpaceComposer()
  closeCollectionComposer()
  closeMove()
}

async function request(path, options) {
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), 45_000)
  let response
  try {
    response = await apiRequest(path, { ...options, signal: options?.signal || controller.signal })
  } catch (cause) {
    if (controller.signal.aborted && !options?.signal?.aborted) {
      throw new Error('请求超过 45 秒仍未完成，请检查后端连接后重试')
    }
    throw cause
  } finally {
    clearTimeout(timeout)
  }
  if (!response.ok) throw new Error((await response.text()) || '请求未完成')
  return response.json()
}

async function loadSpaces() {
  loading.value = true
  error.value = ''
  try {
    spaces.value = await request('/knowledge/spaces')
    const nextId = spaces.value.some(space => space.id === selectedSpaceId.value)
      ? selectedSpaceId.value
      : spaces.value[0]?.id ?? null
    selectedSpaceId.value = nextId
    await loadCurrentSpace()
  } catch (cause) {
    error.value = cause.message || '无法读取知识空间'
  } finally {
    loading.value = false
  }
}

async function loadCurrentSpace() {
  if (!selectedSpaceId.value) {
    collections.value = []
    sources.value = []
    return
  }
  const spaceId = selectedSpaceId.value
  const collectionId = selectedCollectionId.value
  const tag = tagFilter.value.trim()
  const sourcePath = new URLSearchParams({ spaceId: String(spaceId) })
  if (collectionId !== null) sourcePath.set('collectionId', String(collectionId))
  if (tag) sourcePath.set('tag', tag)
  const [loadedCollections, loadedSources] = await Promise.all([
    request(`/knowledge/spaces/${spaceId}/collections`),
    request(`/knowledge/sources?${sourcePath}`)
  ])
  if (spaceId !== selectedSpaceId.value || collectionId !== selectedCollectionId.value) return
  collections.value = loadedCollections
  sources.value = loadedSources
  autoCatchUp()
}

async function selectSpace(spaceId) {
  if (spaceId === selectedSpaceId.value) return
  selectedSpaceId.value = spaceId
  selectedCollectionId.value = null
  clearKnowledgeResponses()
  closeCollectionComposer()
  await refreshCurrent()
}

async function selectCollection(collectionId) {
  if (collectionId === selectedCollectionId.value) return
  selectedCollectionId.value = collectionId
  clearKnowledgeResponses()
  closeCollectionComposer()
  await refreshCurrent()
}

function clearKnowledgeResponses() {
  searchRequestId++
  searching.value = false
  answerRequestId += 1
  activeAskController?.abort()
  activeAskController = null
  asking.value = false
  answerResult.value = null
  answerDraft.value = ''
  answerPhase.value = 'retrieving'
  answerError.value = ''
  searchResults.value = []
  searchError.value = ''
  searchWarnings.value = []
  searched.value = false
}

async function refreshCurrent() {
  if (!selectedSpaceId.value) return
  loading.value = true
  error.value = ''
  try {
    await loadCurrentSpace()
  } catch (cause) {
    error.value = cause.message || '无法读取当前目录'
  } finally {
    loading.value = false
  }
}

async function analyzePending() {
  if (!selectedSpaceId.value || dispatchingPending.value) return
  dispatchingPending.value = true
  error.value = ''
  try {
    const result = await request(`/knowledge/spaces/${selectedSpaceId.value}/analyze-pending`, {
      method: 'POST',
    })
    notice.value = result?.dispatched > 0
      ? `已派发 ${result.dispatched} 个视频进入解析队列（转写→索引，完成后卡片变 READY）`
      : '没有需要解析的视频'
    await refreshCurrent()
    if (result?.dispatched > 0) startProgressPolling()
  } catch (cause) {
    error.value = cause.message || '批量解析派发失败'
  } finally {
    dispatchingPending.value = false
  }
}

async function syncTaskStates() {
  const tasks = await request('/knowledge/ingest-jobs')
  const mediaIds = new Set(sources.value.map(s => s.mediaId))
  taskStates.value = Object.fromEntries(
    (tasks || []).filter(t => mediaIds.has(t.mediaId)).map(t => [t.mediaId, t]))
}

// 打开空间页自动兜底：从未投递的源幂等补投递，失败过的源从存档补索引。
// 用户不需要记得"先点一下按钮"——没开始和失败都必须自己浮出来、自己恢复。
// 每次进入空间都对账一次：限流拒绝、进程重启、页面早于服务就绪等任何原因
// 漏掉的投递，下次进来都会自愈，而不是一次失败就永远沉默（服务端幂等：
// 只补"无台账记录"的源，重复触发无害）。
async function autoCatchUp() {
  const spaceId = selectedSpaceId.value
  if (!spaceId) return
  try {
    await syncTaskStates()
    const noJob = sources.value.filter(s => ingestStateOf(s) === 'NO_JOB').length
    if (noJob > 0) {
      const result = await request(`/knowledge/spaces/${spaceId}/analyze-pending`, { method: 'POST' })
      if (result?.dispatched > 0) {
        notice.value = `检测到 ${result.dispatched} 个视频从未开始解析，已自动派发进入解析队列`
        startProgressPolling()
      }
    }
  } catch {
    // 自动兜底失败不打断浏览：汇总条会如实显示未投递/失败，下次进来再自愈。
  }
}

const recoveringId = ref(null)
async function reindexSource(source) {
  if (recoveringId.value) return
  recoveringId.value = source.id
  error.value = ''
  try {
    const queued = await request(`/knowledge/ingest-jobs/sources/${source.id}/retry`, { method: 'POST' })
    if (!queued) throw new Error('这份内容无法重新进入视频入库队列；文本来源请重新导入')
    notice.value = `“${source.title}”已重新进入知识入库队列；已有转写会复用`
    startProgressPolling()
    await refreshCurrent()
  } catch (cause) {
    error.value = cause.message || '创建知识入库任务失败'
  } finally {
    recoveringId.value = null
  }
}

function toggleSpaceComposer() {
  spaceComposerOpen.value ? closeSpaceComposer() : (spaceComposerOpen.value = true)
}

function closeSpaceComposer() {
  spaceComposerOpen.value = false
  newSpaceName.value = ''
  newSpaceDescription.value = ''
}

async function createSpace() {
  if (!newSpaceName.value.trim()) return
  saving.value = true
  error.value = ''
  try {
    const created = await request('/knowledge/spaces', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: newSpaceName.value.trim(), description: newSpaceDescription.value.trim() })
    })
    // Reflect the successful write immediately. A later list refresh must not make
    // the user think the create failed or leave them in the old space.
    spaces.value = [created, ...spaces.value.filter(space => space.id !== created.id)]
    selectedSpaceId.value = created.id
    selectedCollectionId.value = null
    closeSpaceComposer()
    notice.value = `已创建知识空间“${created.name}”，现在可以直接导入和整理视频`
    await refreshCurrent()
  } catch (cause) {
    error.value = cause.message || '创建知识空间失败'
  } finally {
    saving.value = false
  }
}

function openCollectionComposer(parentId) {
  collectionParentId.value = parentId
  collectionComposerOpen.value = true
}

function closeCollectionComposer() {
  collectionComposerOpen.value = false
  collectionParentId.value = null
  newCollectionName.value = ''
}

async function createCollection() {
  if (!selectedSpaceId.value || !newCollectionName.value.trim()) return
  saving.value = true
  error.value = ''
  try {
    const created = await request(`/knowledge/spaces/${selectedSpaceId.value}/collections`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ parentId: collectionParentId.value, name: newCollectionName.value.trim() })
    })
    closeCollectionComposer()
    notice.value = `已创建目录“${created.name}”`
    await refreshCurrent()
  } catch (cause) {
    error.value = cause.message || '创建目录失败'
  } finally {
    saving.value = false
  }
}

function openMove(source, mode = 'move') {
  locationMode.value = mode
  movingSource.value = source
  moveSpaceId.value = source.spaceId
  moveCollectionId.value = source.collectionId
  loadMoveCollections()
}

function closeMove() {
  movingSource.value = null
  moveSpaceId.value = null
  moveCollectionId.value = null
  moveCollections.value = []
}

async function loadMoveCollections() {
  if (!moveSpaceId.value) return
  try {
    moveCollections.value = await request(`/knowledge/spaces/${moveSpaceId.value}/collections`)
    if (!moveCollections.value.some(collection => collection.id === moveCollectionId.value)) moveCollectionId.value = null
  } catch (cause) {
    error.value = cause.message || '无法读取目标目录'
  }
}

async function moveSource() {
  if (!movingSource.value || !moveSpaceId.value) return
  saving.value = true
  error.value = ''
  try {
    const source = movingSource.value
    const add = locationMode.value === 'reference'
    const path = add ? `/knowledge/sources/${source.id}/placements`
      : source.placementId ? `/knowledge/sources/${source.id}/placements/${source.placementId}`
      : `/knowledge/sources/${source.id}/location`
    await request(path, {
      method: add ? 'POST' : 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ spaceId: moveSpaceId.value, collectionId: moveCollectionId.value })
    })
    notice.value = add ? `“${source.title}”已同时归入目标目录，共享原有索引` : `已移动“${source.title}”的当前引用`
    closeMove()
    await refreshCurrent()
  } catch (cause) {
    error.value = cause.message || '移动内容源失败'
  } finally {
    saving.value = false
  }
}

// --- 拖拽归类：与「移动」按钮共用同一个 location 端点，只是入口变成手势 ---

function onSourceDragStart(event, source) {
  draggingSource.value = source
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'copyMove'
    event.dataTransfer.setData('text/plain', String(source.id))
  }
}

function onSourceDragEnd() {
  draggingSource.value = null
  hoverDropKey.value = null
}

function clearHover(key) {
  if (hoverDropKey.value === key) hoverDropKey.value = null
}

async function dropSource(event, spaceId, collectionId) {
  const source = draggingSource.value
  hoverDropKey.value = null
  draggingSource.value = null
  if (!source || !spaceId || saving.value) return
  const samePlace = source.spaceId === spaceId
    && (source.collectionId ?? null) === (collectionId ?? null)
  if (samePlace) {
    notice.value = `“${source.title}”已经在这个位置了`
    return
  }
  saving.value = true
  error.value = ''
  try {
    const add = event.altKey
    const path = add ? `/knowledge/sources/${source.id}/placements`
      : source.placementId ? `/knowledge/sources/${source.id}/placements/${source.placementId}`
      : `/knowledge/sources/${source.id}/location`
    await request(path, {
      method: add ? 'POST' : 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ spaceId, collectionId: collectionId ?? null })
    })
    notice.value = add ? `已将“${source.title}”同时归入目标位置` : `已移动“${source.title}”的当前引用`
    await refreshCurrent()
  } catch (cause) {
    error.value = cause.message || '拖拽归类失败'
  } finally {
    saving.value = false
  }
}

async function removeReference(source) {
  if (saving.value) return
  saving.value = true
  error.value = ''
  try {
    await request(`/knowledge/sources/${source.id}/placements/${source.placementId}`, { method: 'DELETE' })
    notice.value = `已移除“${source.title}”在此处的引用；其他目录仍保留`
    await refreshCurrent()
  } catch (cause) { error.value = cause.message || '移除引用失败，请保留至少一个归属' }
  finally { saving.value = false }
}

function applyTagFilter() {
  tagFilter.value = tagFilter.value.trim()
  refreshCurrent()
}

async function searchKnowledge() {
  const query = searchQuery.value.trim()
  if (!query || !selectedSpaceId.value) return
  const requestId = ++searchRequestId
  const scopeSpace = selectedSpaceId.value
  const scopeFolder = selectedCollectionId.value
  searching.value = true
  searchError.value = ''
  searchWarnings.value = []
  answerError.value = ''
  answerResult.value = null
  try {
    const result = await request('/knowledge/search/details', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        spaceId: scopeSpace,
        collectionId: scopeFolder,
        query,
        topK: 8
      })
    })
    if (requestId !== searchRequestId) return
    searchResults.value = result.hits
    searchWarnings.value = result.warnings || []
    searched.value = true
  } catch (cause) {
    if (requestId === searchRequestId) searchError.value = cause.message || '跨视频检索失败'
  } finally {
    if (requestId === searchRequestId) searching.value = false
  }
}

async function askKnowledge() {
  const query = searchQuery.value.trim()
  if (!query || !selectedSpaceId.value) return
  asking.value = true
  const requestId = ++answerRequestId
  const controller = new AbortController()
  activeAskController = controller
  answerError.value = ''
  searchError.value = ''
  searchWarnings.value = []
  answerResult.value = null
  answerDraft.value = ''
  answerPhase.value = 'retrieving'
  searched.value = false
  try {
    const response = await apiRequest('/knowledge/ask/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      signal: controller.signal,
      body: JSON.stringify({
        spaceId: selectedSpaceId.value,
        collectionId: selectedCollectionId.value,
        query,
        topK: 8,
        strategy: 'hybrid'
      })
    })
    if (!response.ok) throw new Error((await response.text()) || '知识库回答失败，请稍后重试')
    if (!response.body) throw new Error('当前浏览器不支持流式回答')
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    let finished = false
    const consumeEvent = rawEvent => {
      let eventName = 'message'
      const dataLines = []
      for (const line of rawEvent.split(/\r?\n/)) {
        if (line.startsWith('event:')) eventName = line.slice(6).trim()
        else if (line.startsWith('data:')) dataLines.push(line.slice(5).trimStart())
      }
      if (!dataLines.length) return
      const payload = JSON.parse(dataLines.join('\n'))
      if (eventName === 'phase') answerPhase.value = payload.phase || answerPhase.value
      if (eventName === 'token') answerDraft.value += payload.text || ''
      if (eventName === 'complete') {
        answerResult.value = payload
        answerDraft.value = ''
        finished = true
      }
      if (eventName === 'error') throw new Error(payload.message || '知识库回答失败，请稍后重试')
    }
    while (true) {
      const { value, done } = await reader.read()
      buffer += decoder.decode(value || new Uint8Array(), { stream: !done })
      const events = buffer.split(/\r?\n\r?\n/)
      buffer = events.pop() || ''
      for (const event of events) consumeEvent(event)
      if (done) break
    }
    if (buffer.trim()) consumeEvent(buffer)
    if (!finished) throw new Error('流式回答意外结束，请重试')
  } catch (cause) {
    if (cause?.name !== 'AbortError' && requestId === answerRequestId) {
      answerError.value = cause.message || '知识库回答失败，请稍后重试'
    }
  } finally {
    if (requestId === answerRequestId) {
      asking.value = false
      activeAskController = null
    }
  }
}

function closeSearchResults() {
  searched.value = false
  searchResults.value = []
}

async function runIngest(dryRun) {
  const rootPath = ingestPath.value.trim()
  if (!rootPath || !selectedSpaceId.value) return
  ingestBusy.value = true
  ingestError.value = ''
  try {
    ingestPlan.value = await request('/knowledge/ingest/scan', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        rootPath,
        spaceId: selectedSpaceId.value,
        collectionId: selectedCollectionId.value,
        dryRun
      })
    })
    if (!dryRun) {
      notice.value = '目录导入已应用'
      await refreshCurrent()
    }
  } catch (cause) {
    ingestError.value = cause.message || '目录导入失败'
  } finally {
    ingestBusy.value = false
  }
}

async function applyIngest() {
  await runIngest(false)
}

async function createScript() {
  if (!newScriptContent.value.trim()) return
  saving.value = true
  error.value = ''
  notice.value = ''
  try {
    const created = await request('/knowledge/sources/script', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        title: newScriptTitle.value,
        content: newScriptContent.value,
        spaceId: selectedSpaceId.value
      })
    })
    scriptComposerOpen.value = false
    newScriptTitle.value = ''
    newScriptContent.value = ''
    notice.value = `脚本「${created.title}」已索引（${created.status}），现在可跨视频检索。`
    await loadCurrentSpace()
  } catch (cause) {
    error.value = cause.message || '脚本上传失败'
  } finally {
    saving.value = false
  }
}

async function toggleLinksPanel(source) {
  if (linksPanelId.value === source.id) {
    linksPanelId.value = null
    return
  }
  linksPanelId.value = source.id
  links.value = []
  linkNotice.value = ''
  await loadLinks(source)
}

async function loadLinks(source) {
  linksBusy.value = true
  try {
    links.value = await request(`/knowledge/sources/${source.id}/links`)
  } catch (cause) {
    linkNotice.value = cause.message || '关联记录读取失败'
  } finally {
    linksBusy.value = false
  }
}

async function suggestLinks(source) {
  linksBusy.value = true
  linkNotice.value = ''
  try {
    const created = await request(`/knowledge/sources/${source.id}/links/suggest`, { method: 'POST' })
    linkNotice.value = created > 0
      ? `已生成 ${created} 条建议，请逐条核对后确认。`
      : '没有新的可建议配对（内容不相似或均已处理）。'
    await loadLinks(source)
  } catch (cause) {
    linkNotice.value = cause.message || '关联建议生成失败'
    linksBusy.value = false
  }
}

async function confirmLink(link) {
  linksBusy.value = true
  try {
    await request(`/knowledge/links/${link.id}/confirm`, { method: 'POST' })
    link.status = 'CONFIRMED'
  } catch (cause) {
    linkNotice.value = cause.message || '确认失败'
  } finally {
    linksBusy.value = false
  }
}

async function rejectLink(link) {
  linksBusy.value = true
  try {
    await request(`/knowledge/links/${link.id}/reject`, { method: 'POST' })
    link.status = 'REJECTED'
  } catch (cause) {
    linkNotice.value = cause.message || '拒绝失败'
  } finally {
    linksBusy.value = false
  }
}

function linkStatusText(status) {
  return { SUGGESTED: '建议', CONFIRMED: '已确认', REJECTED: '已拒绝' }[status] || status
}

function formatMs(value) {
  const totalSeconds = Math.max(0, Math.floor((value || 0) / 1000))
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = String(totalSeconds % 60).padStart(2, '0')
  return `${minutes}:${seconds}`
}

async function clearTagFilter() {
  if (!tagFilter.value) return
  tagFilter.value = ''
  await refreshCurrent()
}

function toggleTagEditor(source) {
  tagEditorId.value = source.id
  newTagDraft.value = ''
}

function closeTagEditor() {
  tagEditorId.value = null
  newTagDraft.value = ''
}

async function commitTagEditor(source) {
  const tag = newTagDraft.value.trim()
  if (!tag) return
  await saveTags(source, [...(source.tags || []), tag])
  closeTagEditor()
}

async function removeTag(source, tag) {
  await saveTags(source, (source.tags || []).filter(candidate => candidate !== tag))
}

async function saveTags(source, tags) {
  saving.value = true
  error.value = ''
  try {
    const updated = await request(`/knowledge/sources/${source.id}/tags`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ tags })
    })
    sources.value = sources.value.map(candidate => (candidate.id === updated.id ? updated : candidate))
  } catch (cause) {
    error.value = cause.message || '保存标签失败'
  } finally {
    saving.value = false
  }
}

function collectionDepth(collection) {
  return Math.max(0, (collection.path || '').split('/').filter(Boolean).length - 1)
}

function formatDate(value) {
  if (!value) return '等待索引'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '等待索引' : date.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' })
}
</script>

<style scoped src="./styles/knowledge.css"></style>
