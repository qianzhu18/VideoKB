# VideoKB

**把一批视频变成一个可以提问、可以溯源、可以持续更新的知识库。**

上传视频（或粘贴脚本/笔记）→ 自动解析为带时间戳的多模态证据 → 跨视频检索 → 生成自然语言回答，回答必须绑定可回跳原始视频的证据引用，证据不足时明确拒答。

## 它解决什么问题

视频看完就忘、笔记散落各处、想引用某个片段要来回拖进度条。VideoKB 把「一批视频 + 你的脚本笔记」统一成一个知识资产：**所有内容切分为证据片段、建立向量与关键词双路索引，你像使用 Dify/Coze 知识库一样提问，它跨视频给出有据可查的回答**。时间戳不是卖点，是回答的护栏——每条结论都能定位到原始视频的某一秒。

## 核心能力

### 📚 跨视频知识库（产品核心）

- **知识空间 / 目录 / 标签 / 审计** — 多租户隔离，一切变更落 `knowledge_audit_logs`，证据行以 MySQL `knowledge_segments` 为权威真源（INDEXING → READY/FAILED 状态机）。
- **三策略召回 + RRF 融合** — vector / keyword / hybrid 三路检索，30 条 golden 集实测 hybrid Recall@5 **0.833**、MRR **0.75**，全面优于单通道。
- **证据约束回答** — 相关性低于阈值（0.45）时诚实拒答；回答中的引用必须能对齐到真实证据片段。
- **本地目录增量同步** — contentHash 差分（CREATED/CHANGED/MOVED/DELETED/UNCHANGED），只重建受影响来源，不重复烧 ASR/OCR。
- **Muku 批量导入** — 每行一条 B 站、YouTube 等视频链接，选择目标知识空间、下载并发和自定义解析目标；Muku 只负责获取视频，Java 服务继续完成转写、画面识别、RocketMQ 分析任务与 Qdrant 知识索引。批次状态和 Muku checkpoint 保存在 `MUKU_WORK_DIR`，可用原批次 ID 续跑。
- **视频 ↔ 脚本关联** — 脚本按段落切分入同一检索空间，语义配对建议经人工确认后成事实。

### 🎬 可靠的视频任务链路

- **分片上传 + 断点续传** — 前端 5 MB 分片，Redis 记录进度，MinIO 存储合并视频。
- **异步削峰** — RocketMQ 承载解析任务，提交即返回任务 ID；Redisson 按「内容指纹 + 分析目标」加锁，幂等防重。
- **成本护栏** — 用户级与全局令牌桶限流；模型调用指数退避重试；分析任务带轮次与预算上限。
- **失败可观测** — 失败任务入死信表，管理接口一键重放（从 checkpoint 续跑，实测重放 60s vs 首跑 8 分钟）。

### 🧩 时序多模态 VideoContext

- ASR 与关键帧 OCR 双分支并行，感知哈希去重，单路失败容忍；统一为带时间戳的 `VideoSegment`。
- 5 分钟语义分块（摘要 + 关键词 + bge-m3 1024 维向量），Qdrant 不可用时降级本地关键词与向量排序。

### 🔁 受证据约束的 AgentLoop

- Planner → Executor → Critic 闭环：结论必须绑定时间戳证据，Critic 不通过时定向补检索，最多两轮。
- 四种分析模式（通用/学习/审查/创作）自动路由；Qdrant/Embedding 故障全链路降级不阻断。

### 🔌 MCP 出口

独立 `mcp-server` 模块（Streamable HTTP :9091），三个只读工具 `list_knowledge_spaces` / `search_video_knowledge` / `get_video_evidence`，双令牌信任边界 + 调用审计——Claude Code、Cursor 等外部 Agent 可直接检索并引用带时间戳的视频证据。

## 技术栈

| 层次 | 技术 |
| :--- | :--- |
| 前端 | Vue 3 + Vite + SSE + Marked |
| 后端 | Java 21、Spring Boot 3.5.9、MyBatis-Plus、LangChain4j |
| 异步与缓存 | RocketMQ 5.3、Redis 7 + Redisson（分布式锁 / 限流 / 幂等） |
| 数据与存储 | MySQL 8、MinIO、Qdrant |
| 视频与 AI | FFmpeg、Tesseract、DeepSeek、TeleSpeechASR、BGE-M3 |

## 快速开始

```bash
./scripts/dev-up.sh   # 一键启动：MySQL + Redis + MinIO + Qdrant + RocketMQ + 后端 9090 + 前端 5173 + MCP 9091
```

1. 打开 http://127.0.0.1:5173 注册账号，上传视频，等待解析完成（状态 READY）；
2. 知识库 → 侧栏「脚本 / 笔记」粘贴口播稿，立即入库可检索；
3. 顶部跨视频提问（例如视频里讲过的概念），观察**视频与脚本双源证据**与毫秒时间戳；
4. （可选）给 AI 助手配置 MCP：`http://127.0.0.1:9091/mcp` + Bearer 令牌。

环境要求：JDK 21、Node 22、Docker Compose、FFmpeg、Tesseract（chi_sim + eng）。配置见 `.env.example`。

批量链接导入还要求在运行 Java 服务的同一环境中安装 Muku CLI，并在 `.env` 中设置 `MUKU_PATH`；`MUKU_WORK_DIR` 应指向持久化目录，以保留批次状态和下载断点。

## 验收与路线

业务需求与验收标准见 [agent.md](./agent.md)（单一事实源）。评测数据集与 runner 在 `eval/`，报告随验收轮次更新。

## License

[MIT](./LICENSE)
