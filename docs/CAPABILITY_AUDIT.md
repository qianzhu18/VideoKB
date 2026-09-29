# 知识库能力代码级核查清单（P0-1）

> 2026-09-27 由两轮源码探索 + 实机验证产出。三列口径：**已有**（代码+测试/实机证据）、**缺失**（未实现）、**虚假声明**（文档声称但代码不存在）。

| 能力声明 | 核查结论 | 证据 |
| --- | --- | --- |
| 知识空间/目录/来源/标签/审计 | 已有 | `KnowledgeSpace/Collection/Source/Tag/AuditLog` 实体 + 各 Controller；ownerUserId 隔离，实机注册/建空间通过 |
| knowledge_segments 状态机 | 已有 | `KnowledgeSourceVersion` INDEXING→READY/FAILED；13 期实机全 READY；01 期预算终止后 FAILED→reindex 恢复 READY |
| 三策略召回 + RRF 混合 | 已有 | `KnowledgeSearchService`：vector（Qdrant，min-score 0.45）/keyword（ILIKE 覆盖度）/hybrid（RRF K=60）；实机三种策略命中正常 |
| 0.45 拒答阈值 | **表述不准** | 不是分数阈值；拒答是分类性的：零命中→不调模型直接拒；生成后引用逐字校验不过→拒（`KnowledgeAnswerService`） |
| ask 自然语言回答 + 引用 | 已有（本轮真实验收） | `KnowledgeAnswerService` + `KnowledgeAnswerGenerator`；四类真实样例（`docs/acceptance/rag-samples.md`）：跨视频 5 引用两期 / 单期精确 ×2 / 域外拒答 |
| 引用逐字校验 | 已有（本轮修复） | normalize 大小写归一 + prompt 强制复制粘贴规则（保留 ASR 错字）；JVM 题由假拒答变 SUPPORTED 3 引用 |
| 本地目录增量同步（contentHash 差分） | 已有（真实验收） | `KnowledgeIngestService` + `IngestPlanner`；13 期首扫 13 CREATED、重扫 13/13 UNCHANGED 零重烧 |
| 媒体托管 MinIO + 指纹去重 | 已有 | `applyCreated` MD5→MinIO→externalPath；1.23GB 实机入库；上传层同用户同哈希去重（#2 批次交付） |
| MCP 三只读工具 | 已有（真实验收） | `McpDispatcher` + `DovideoApiClient`；E2E initialize/tools/list/三工具全绿 |
| MCP ask 工具 | 已有（本轮新增） | 第 4 工具 `ask_video_knowledge`；空 spaceId 解析默认空间；空账号确定性拒答；E2E 可回答+拒答双绿 |
| MCP 40100 自动重登录 | 已有（配置问题非代码） | `DovideoApiClient.call` 服务账号模式重试；此前卡点是静态 token 运行模式，切 `DOVIDEO_API_USERNAME/PASSWORD` 后实机通过 |
| 批处理可靠性（状态机/台账/去重/死信） | 已有 | `service/task/AnalysisTaskStateMachine` 等 + 122→127 测试；实机 `/analysis/tasks` 200/401 鉴权验证 |
| ASR 分片断点续跑 | **缺失** | MASTER_TODO #2 明确待办；01 期若中途重启将整单重跑（本轮未重启避开） |
| 死信消费/告警闭环 | 缺失 | 同上，待办 |
| B站/网盘来源接入 | 缺失 | P2；本轮语料走本地目录通道 |
| 自动发现增量运行器 | 缺失 | P2；增量靠手动/脚本触发 scan |
| Milvus | 缺失（有意） | 仅候选；双写/回灌/影子查询未开工 |
| 跨视频知识库 GUI | 已有（接线全，未走查） | `KnowledgeLibrary.vue` 答案/引用/时间戳/拒答/回跳全接线，无 TODO；人工验收待做 |
| 虚假声明 | 未发现 | README 声明逐条对照源码均有实现；唯一表述不准确的"0.45 拒答阈值"已在表中澄清 |

## 遗留问题（本轮发现，未阻塞主线）

1. `analysis_tasks` 台账 `CONSUME_START` 事件重复写入触发 Duplicate entry warn（容错不阻断，建议改 upsert）。
2. 长视频（52 分钟/21 万字）Executor 超 50000 token 预算终止——预算闸门按设计生效，但该量级视频的最终报告生成需要上下文压缩或更高预算档位。
3. `.env` 值含空格必须加引号（`source` 截断坑），SOP 已记录。
