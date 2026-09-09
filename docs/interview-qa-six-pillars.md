# dovideo 面试拷打文档:六大亮点重构版

> 对齐 DOVideo-AI docs/interview-qa-six-pillars.md 的定位:每条简历亮点一章,只留能展开技术判断的问题。
> 与 v2 的分工:本文是背诵主战场(每题答案控制在面试口述 1 分钟内),v2 是细节弹药库。

## 项目总览

### Q1:请用两分钟介绍 dovideo

dovideo 是一个证据约束的 Video Agent,Python + FastAPI + asyncio 实现。用户给出视频和一个分析目标,系统分三步工作:先把视频整理成带时间戳的语音与关键帧文字证据(VideoContext);再按目标做分层摘要与混合检索,把相关证据打包进 24k 字符预算;最后由 Planner、Executor、Critic 三个受控角色闭环执行——每条结论必须绑定能回溯到原始视频时间点的证据,证据由纯代码核验而不是信任模型,不通过就定向补证据最多两轮。

它是对开源项目 DOVideo-AI 的核心工程思想的移植与拓展:保留其证据闭环、Checkpoint、混合检索、降级链四大设计,栈换成 Python,并补上 MCP Server、LangFuse 可观测、金标评测三个原版没有的维度,以及"无 API Key 全链路可测"的离线架构——60 个离线测试锁住全部关键行为。

### Q2:从提交目标到拿到结果,完整链路是什么?

提交:模式路由(永不失败)→ `(内容, goalDigest)` 幂等认领(重复 409)→ 202 受理。消费:checkpoint 复用检查 → VideoContext 双分支构建(ASR 60s 切片 ∥ 场景检测+dHash+OCR,单路失败容忍)→ 5 分钟分块(摘要+关键词+embedding,四级降级)→ 混合检索选段进 24k 预算 → AgentLoop 两轮闭环 → 结果与 checkpoint 落 SQLite。全程阶段事件经 SSE(`task-status`)推送,支持追问与证据检索。

### Q3:这个项目最难的地方是什么?

让"模型输出不可信"变成工程可约束。LLM 会伪造时间戳和原文,所以证据核验是纯代码(归一化子串匹配 + claim 强制相等 + 结论覆盖率);Critic 只保留语义层职责;反馈必须真实改变下一轮输入(补证据/修订计划),而不是复读。再加预算四闸门与三级 checkpoint 恢复,把一个概率性系统约束成行为可测的系统。

### Q4:主要数据为什么放在不同存储里?

按"事实边界"分:SQLite 是恢复真源(checkpoint+失败台账,丢了要重算烧钱);本地磁盘放媒体与关键帧帧(大对象不进 SQL);Redis 只做可选热缓存(写库后才写,丢了只慢不错);Qdrant 只做语义召回(挂了退本地余弦,不阻断)。原则:缓存丢失影响性能,不影响正确性;最终状态不只在缓存。

# 一、Planner-Executor-Critic 受控 Agent 工作流

## 定位、选型与完整循环

### Q1:为什么需要 Agent 工作流,为什么是这个三角色分工?

一次 LLM 调用三个不可控:幻觉、漏覆盖、结构随机,且失败只能整体重烧。拆成三角色各管一类问题:Planner 管覆盖(目标→1–5 个"仅靠证据可完成"的任务),Executor 管证据绑定(固定 schema + claim 逐字复制),Critic 管缺口(语义层审校 + 输出定向重检索钩子)。确定性程序管其余一切:计划校验、证据核验、钳制、预算、checkpoint、终止。

### Q2:请用一个具体目标讲清 AgentLoop 怎样执行

目标"梳理知识点并出自测题"(LEARNING 模式)。Planner 拆出 3 个任务;Executor 产出结论+证据+大纲/重点/自测题/易错点四个 sections;Critic 发现"缺自测题"并标记某证据时间戳不可核验——注意程序核验会发现 LLM 谎报的 passed 并强制拉回;系统按时间戳补邻近证据、按缺失修订计划;第二轮 Executor 只修被指出的问题;Critic 通过,写结果。全程每步落 checkpoint,挂掉从最近阶段续跑。

### Q3:Planner 和 Executor 分别受到哪些约束?

Planner:任务数 1–5、每条 ≤500 字、必须仅靠证据可完成;结构不合法自动 repair 一次,再失败判永久失败。Executor:每条结论至少一条真实证据、claim 逐字复制结论、时间戳落在原始片段、不得引入上下文外事实、模式必需 sections(LEARNING 是 outline/keypoints/quiz/pitfalls)必须齐全——缺了会被结构钳制拦截。

## Claim 校验、反馈重执行与终止

### Q4:Claim 级证据校验到底校验什么?

三层纯代码核验:证据真实性(timestampMs 落在 segment 区间 + source 兼容 + 归一化 content 是该段源文本子串)、claim 绑定(归一化后等于某条结论)、结论覆盖率(每条结论至少一条有效证据)。归一化 = 小写 + 删全部标点/符号/空白,兼容中英文标点差异。核心思想:**引用存在不代表结论成立,但结论成立必须能回到原文**——LLM 只保留语义层判断权。

### Q5:Critic 不通过后,为什么第二轮不是重复生成?

因为输入集合变了。rewrite-only(只有 feedback):上下文不变,带反馈重写,prompt 要求只修被指出的问题、保留已核验结论。需补证据(missingRequirements/unsupportedClaims/requiredTimestamps 非空):三路合并——requiredTimestamps 邻近段(margin=max(60s,段长))∪ critiqueQuery 重检索 ∪ 原选中集,去重后重新过 24k 预算。第二轮模型看到的世界和第一轮不同。

### Q6:Agent 怎样保存状态、终止和恢复?

三级 checkpoint:草稿级(Executor 后 Critic 前落盘,重试从 Critic 续跑)、终态级(命中直接返回 0 次模型调用,脏数据自修复归零)、内容级(context/chunks 跨目标复用)。终止条件四类:通过、轮次耗尽(带警告返回,不丢产物)、预算耗尽(独立终态不重试)、永久失败(死信台账)。证明优于单次 Prompt:行为层有测试(谎报被拦、反馈真实改变输入),指标层有金标评测框架(诚实说:用例集未沉淀,只有框架没有数据)。

# 二、ASR 与关键帧 OCR 构建多模态 VideoContext

### Q1:为什么纯 ASR 不够,VideoContext 具体是什么?

公式、代码、图表文字在画面上不在语音里。VideoContext 是 60 秒窗口的统一段结构 `{startMs, endMs, transcript, ocrTexts[], evidenceFrames[]}`,语音叙事和画面文字共享时间轴,后续检索与证据回跳都不再依赖底层模型格式。

### Q2:VideoContext 从视频开始怎样完整构建?

ASR 分支:ffmpeg 60s 切片 → 逐片 TeleSpeechASR,时间区间即切片区间。视觉分支:一条 ffmpeg 表达式选帧(首帧 + 场景变化>0.35 + 30s 保底)→ showinfo 的 pts_time 提取时间戳(丢失按 i×30s 兜底)→ dHash 汉明 ≤5 去重 → tesseract OCR。合并:60s 固定窗口键 `ts/window*window` 聚合。

### Q3:场景变化检测到底是怎么做到的?为什么不逐帧抽?

逐帧抽对小时级视频是存储和 OCR 的灾难。场景检测用 ffmpeg 内置 `scene` 滤镜:相邻帧差异分超过 0.35 认为切镜,取切镜后首帧;30 秒保底兜住静态画面(纯讲解无切镜场景);dHash 感知哈希再压掉近似帧。三层过滤后,帧数量与信息密度都可控。

### Q4:为什么 ASR 固定 60 秒切片、双分支怎么容错?

60 秒 = 时间对齐(分钟级证据区间)+ 失败隔离(单片失败只损失 60 秒)+ 接口稳定。容错:双分支 asyncio.gather 并行,切片级/帧级失败计数容忍,单路失败保留另一路;双路皆败抛 PipelineError 且必须携带 cause——否则上层把 API Key 配错当网络抖动反复重投整条流水线。

### Q5:音画不同步、OCR 识别错误怎么办?

时间轴以切片区间为准,窗口融合天然对齐到同一分钟粒度,不存在亚秒同步问题。OCR 错字影响检索命中率和证据子串匹配——归一化能容忍标点差异但不容忍错字,这是诚实边界:错字段的证据会被核验拦下并触发补证据,而不是进入最终产物。语义级冲突由 Critic 的"上下文不支持的结论"检查兜底。

# 三、分层摘要、Embedding 混合检索与上下文预算

### Q1:为什么不能把完整 VideoContext 交给模型?

超上下文、噪音淹没目标、Token 成本失控。分层:segment(证据原子)→ chunk(5 分钟检索单元:摘要≤200字+关键词+embedding)→ 预算内相关段(24k 字符,首条保留)。检索先于规划,Planner 看到的是相关证据而非全量噪音。

### Q2:五分钟分块和混合检索怎么做的?

分块:从 0 起步按 5 分钟切,收集窗口内 segment;每块 LLM 摘要 + 关键词;embedding = 摘要+关键词。检索:LLM 规划查询意图(语义查询/关键词/视觉词,失败降级标点切词);chunk 级打分 semantic×0.6 + keyword×0.25 + visual×0.15;Top3 chunk 展开 segment 级 chunk×0.55 + transcript×0.25 + visual×0.20。Qdrant point id = uuid5(mediaId:startMs:endMs),确定性保证幂等 upsert。

### Q3:先检索再 Planner,第一次检索不完整导致计划遗漏怎么办?

这正是 Critic 存在的理由:计划遗漏会在 Critic 的"目标覆盖检查"暴露,missingRequirements 驱动定向重检索补充证据、replan 修订计划——第一轮检索不需要完美,只需要让 Planner 有足够信息启动,缺口由闭环兜住。这是"检索-规划-校验"三者的分工,而不是要求任何单步完美。

### Q4:摘要漏信息、TopK 没命中或向量服务失败怎么办?

四级降级,每级独立计数、互不阻断:摘要 LLM 挂 → 原文头 500 字;意图 LLM 挂 → 标点切词;embedding 挂 → 空向量纯关键词;Qdrant 挂 → 本地余弦。命中问题由定向重检索兜底。设计目标:检索层任何故障只降低相关性,不阻断分析。

# 四、阶段级 Checkpoint 与内容级复用

### Q1:Checkpoint 与缓存、幂等键各解决什么?

缓存(Redis)管速度,丢了只慢;checkpoint(SQLite)管恢复,丢了重算烧钱;幂等键 `(content, goalDigest)` 管并发与重复提交(SETNX 语义,重复 409)。三者写序有讲究:先库后缓存,幂等键失败路径必须释放,否则重试全被误判重复。

### Q2:checkpoint 键体系与恢复粒度?

内容级 `media:context|chunks`(跨目标复用,最贵的 ASR/OCR 只烧一次)+ 目标级 `goal:{digest}:*`(digest=sha256(goal) 或 sha256(mode+␟+goal),U+2419 防拼接碰撞)。恢复三级:草稿(Executor 后落盘,Critic 续跑)、终态(命中 0 次调用,脏则自修复归零)、阶段(28 阶段状态机可观测)。

### Q3:不同阶段失败后怎样恢复?

ASR/LLM 抖动 → 可重试异常 → runner 重投(1/2/4s 退避,3 次投递),重投后 checkpoint 命中直接续跑;永久失败(4xx/结构彻底失败)→ 死信台账(脱敏)可重放;预算耗尽 → 终态不重试。恢复的最小单位是"最近一个成功阶段",不重算已花钱的产物。

# 五、预算三闸门、幂等与失败台账

### Q1:四类终止预算怎样生效?

轮次(默认 2,管修正循环)、时长(默认 120s,管墙钟)、Token(默认 50000,启发式:非 ASCII 1 字符 1 + ASCII 4 字符 1)、成本(可选,启用必须配单价,构造 fail-fast)。关键实现:模型调用超时 = min(模型超时, 剩余预算)——慢供应商无法吃穿总预算;检查在阶段边界;耗尽抛 BudgetExhaustedError → `BUDGET_EXHAUSTED` 终态,不重试(重试只会继续超)。

### Q2:为什么"异常类型即重试语义"?

靠异常消息字符串区分可重试性是脆弱的(文案一改就坏)。显式类型 Retriable*/Permanent* 让每一层的行为可推断:应用内 3 次退避(1/2/4s)、runner 3 次投递、ASR 全失败必须带 cause。这是把分布式系统的失败分类从约定升级为类型系统。

### Q3:失败台账与重放怎么做?

SQLite 表记录 mediaId/digest/阶段/错误类型/消息,消息入库前脱敏(bearer/api_key/sk- 掩码、截 1000 字);管理接口列出 + 按 id 重放(重放前重设幂等键)。对应原版 RocketMQ 死信队列+管理端,语义一致、载体更轻。

# 六、MCP、LangFuse、金标评测与离线架构

### Q1:MCP Server 与 Function Calling 的区别?

Function Calling 是模型在单次对话内选择函数;MCP 是跨应用能力总线:工具实现一次(analyze_video/evidence_search/follow_up),任何 MCP 宿主(Claude Code 等)都能用。对项目意味着视频 Agent 从"一个 Web 应用"升级为"可被任意 AI 客户端编排的能力"。

### Q2:金标评测防什么?

防 prompt/护栏改动引发质量回归。用例 = 上下文+目标+期望关键词;通过 = structuredValid && claimEvidenceSupportRate≥0.8 && keywordCoverage≥0.8;runner 走与生产完全相同的任务路径。诚实边界:框架+测试已就绪,用例集未沉淀,没有可引用的质量数据。

### Q3:为什么整个系统可以无 Key 离线运行?

所有外部交互收口为可注入接口:LLM 协议、ASR/帧分支构建器、向量库、预算时钟。FakeLLM 按任务标记路由脚本化响应并可注入故障(抖动/永久/谎报 passed)。60 个测试 0.3s 跑完,覆盖真实故障模式;演示当场可跑。这是"可测性是一等设计目标"的具体形态,也是面试现场最大的差异化。

### Q4:和原项目(DOVideo-AI)的关系怎么讲?

场景与核心工程思想(证据闭环/VideoContext/混合检索/Checkpoint/降级链)致敬并移植自开源项目 DOVideo-AI,我对它做过约 40 个核心类的精读。我的工作是:栈迁移(Spring/RocketMQ/Redisson → FastAPI/asyncio/redis-py,可靠性语义等价)、护栏系统化(纯函数+测试锁定)、补齐三个原版没有的维度(MCP/LangFuse/评测)和离线可测架构。面试话术:"我选它做参照系,是因为它的设计经过过真实面试与使用检验;我的增量在工程化与可验证性。"
