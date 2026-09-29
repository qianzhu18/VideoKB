# Breaking Changes 影响与必要性说明（批处理可靠性第一批）

> 范围：`feat/batch-reliability-ledger` 分支承载的批处理可靠性第一批交付（任务状态机、`analysis_tasks` 台账、`GET /analysis/tasks` manifest、上传层 MD5 内容去重、消费并发上限）。
> 结论先行：**对外 HTTP API 无字段级破坏，新增一个只读端点；但上传接口存在一处行为级语义变更（同用户同内容去重），客户端"每次上传必得新 id"的隐含假设不再成立；数据库为纯增量新表；Java 内部签名变更全部在本仓库内适配；两项运行行为变更（消费并发、缓存 TTL）需部署时知晓。**

## 1. HTTP API 层（对外）

### 1.1 新增接口（非破坏，纯增量）

- `GET /analysis/tasks` — 当前用户的任务 manifest（状态、投递次数、最近阶段、失败原因），Bearer 鉴权，无凭证返回 401。

**破坏性：无。** 新路径，旧客户端不受影响。

### 1.2 上传接口的行为级语义变更 ⚠️ 本轮唯一实质破坏点

**涉及接口：** `POST /media/upload`、分片上传 `POST /media/complete-upload`、URL 导入（`POST /media/ingest-url`）、本地目录导入（`POST /knowledge/ingest/scan` 的应用阶段）。

**变更前：** 同一用户每次上传（即使文件字节完全相同）都会创建新的 `media_files` 行、上传新的 MinIO 对象、创建新的 knowledge source。

**变更后：** 同一用户上传相同内容（MD5 一致）时，**返回既有 media 行**（`id`、`filename`、`uploadTime` 均为首次上传的值），本次上传的冗余对象立即删除，不产生新行/新 source。响应体结构不变（仍是 `Result<MediaSummary>`，`code=0`）。

**影响分析：**
- 响应字段结构：不变。宽松解析的客户端无需改造。
- 依赖"每次上传必得新 mediaId"的客户端逻辑会受影响（例如把"上传成功"当作"创建了新资产"的埋点）。当前仓库内的调用方（前端上传面板、KnowledgeIngestService 增量导入）均按"幂等返回 media"语义工作，已验证不受影响。
- 分片上传的 `complete` 重试语义不变：`completedKey(uploadId)` 记录的是去重后的目标 mediaId，重试 `complete` 仍返回同一 media。
- 不同用户之间**不做**去重（查询按 `user_id + content_hash`），无跨用户可见性风险。

**必要性：** MASTER_TODO #2 的验收标准明确要求"MD5 内容去重"。此前仅分析层按内容哈希复用结果，存储（MinIO）、媒体行与 knowledge source 仍会随重复上传线性翻倍——去重是成本控制的第一道闸门，放在上传层能让所有下游（分析、索引、存储）同时受益。

**回退/绕过：** 如需同内容保留多副本，先删除既有 media 后重新上传，或使用 knowledge source 的 attach/移动能力组织同一 media 的多处归属。极端并发下（锁窗口与事务提交之间的毫秒级窗口）仍可能产生一行重复，由分析层内容级复用兜底，不影响正确性。

## 2. 数据库（Flyway V8）

- 新表 `analysis_tasks`（唯一键 `(media_id, goal_digest)`，与 Redis 幂等键任务身份同源）。
- **既有表零变更**；无列删除、无类型修改、无索引重建。

**破坏性：无（纯增量）。**
**回滚：** `DROP TABLE analysis_tasks;` 并删除 `flyway_schema_history` 中 version=8 的行（台账为 best-effort 审计数据，删除不影响业务功能，仅失去任务流转审计）。

## 3. Java 内部签名（编译级破坏，仓库内已适配）

| 变更 | 影响 | 必要性 |
| --- | --- | --- |
| `MediaService` 构造器新增 `RedissonClient` | Spring 自动装配；无手工构造方 | 去重锁需要分布式互斥 |
| `AnalysisDispatchService` 构造器新增 `AnalysisTaskService` | 同上 | 提交受理写台账（SUBMIT 事件） |
| `VideoAnalysisConsumer` 构造器新增 `AnalysisTaskService` | 同上 | 消费各分支落账（开始/复用/成功/重试/死信/毒消息） |
| `FailedAnalysisTaskService` 构造器新增 `AnalysisTaskService` | 同上 | 重放落账（REQUEUE 事件） |
| `AnalysisController` 构造器新增 `AnalysisTaskService` | 同上 | manifest 端点数据源 |

**必要性总结：** 均为台账功能的依赖注入扩散，全部调用方在本仓库内，无外部下游、无反射/序列化依赖这些构造器。

## 4. 配置与运行行为变更（部署须知）

### 4.1 消费并发从无界变为固定 4（`VideoAnalysisConsumer`）
- **变更前：** 消费者线程池未设置，按 rocketmq-spring 默认（min 20 / max 64）无界并发消费。
- **变更后：** `consumeThreadNumber = 4, consumeThreadMax = 4`（固定池，已按 2.3.0 反编译核实属性映射）。
- **影响：** 大批量提交时的消费吞吐上限下降（并行分析数 ≤4），第三方 ASR/LLM 调用速率随之受限——这正是"可控成本"的目的。与 `aiTaskExecutor`（核心 4）对齐。
- **必要性：** MASTER_TODO #2 验收"限流/成本可控"；无界并发在批量导入场景会同时打满线程池与第三方配额。

### 4.2 `media:md5:{mediaId}` 哈希缓存补 7 天 TTL
- **变更前：** 无 TTL，仅靠媒体删除时清理；行被外部清理时键永久残留。
- **变更后：** 7 天 TTL；miss 时回查 DB（`media_files.content_hash`），对调用方透明。

### 4.3 每次任务状态流转新增 MySQL 写（台账）
- 提交/消费开始/成功/复用/重试/死信/重放各产生 1 次 `SELECT + INSERT/UPDATE`（索引命中）。
- 写入 best-effort：DB 故障时仅告警，不阻断分析链路；Redis 幂等键仍是运行期权威。

### 4.4 上传路径新增 Redis 依赖（fail-open）
- 去重锁 `lock:media:dedup:{userId}:{md5}`（Redisson 看门狗租约）。Redis 不可用时降级为无锁查询，上传不受阻。
