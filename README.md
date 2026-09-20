# dovideo — 证据约束的 Video Agent(Python 引擎)

对 [DOVideo-AI](https://github.com/Xiaoc7r/DOVideo-AI)(Java/Spring Boot)核心设计的**移植与拓展**:保留其经过面试与生产检验的工程思想,换成 AI 原生栈(Python + FastAPI + asyncio),并补上原版没有的 MCP / LangFuse / 金标评测三个工程化维度。具体取舍与已实现边界见 [项目背诵与面试手册](./docs/project-playbook.md)。

## 技术面试准备

本项目同时是 AI 应用 / 后端工程师面试的主项目。文档入口见 [docs/README.md](./docs/README.md)：先从技术面试任务书开始，再按当前 Python 实现的面试问答逐项对照代码和测试。DOVideo-AI 是场景与工程思路的公开参照；本仓库只包含本项目的 Python 实现与可验证材料。

## 核心思想(全部继承)

1. **有证据约束的 AgentLoop** — Planner → Executor → Critic 闭环,最多两轮;Critic 分两层:LLM 只做语义判断,**时间戳证据由纯代码核验**(归一化子串匹配),模型想编证据编不了;不通过时按 `requiredTimestamps` 钩子定向补证据、增量 replan
2. **确定性护栏链** — normalize → 结构钳制 → 证据核验 → 一致性钳制(LLM 谎报 passed 会被强制拉回)
3. **预算三闸门** — 轮次 / 时长 / Token(成本可选);模型调用超时 = min(模型超时, 剩余预算);熔断后 `BUDGET_EXHAUSTED` 终态不重试
4. **SYSTEM_POLICY 注入防火墙** — 视频衍生内容(ASR/OCR/目标/草稿)全部标记为不可信数据,其中指令一律忽略
5. **恢复性短路** — Executor 草稿先落盘再 Critic(重试从 Critic 续跑);终态 checkpoint 命中 0 次 LLM 调用;脏 checkpoint 自修复归零重跑
6. **时序多模态 VideoContext** — FFmpeg 60s 切片 ASR ∥ 场景检测选帧 + dHash 去重 + OCR,单路失败容忍,60s 窗口融合
7. **混合检索 + 全链降级** — 5min 分块摘要 + 关键词 + 向量(Qdrant;不可用回落本地余弦;embedding 不可用纯关键词;摘要失败降级原文),打分权重 0.6/0.25/0.15 与 0.55/0.25/0.20
8. **任务可靠性语义** — 提交幂等(`(content, goalDigest)` SETNX)、异常类型即重试语义(可重试/永久显式建模)、失败台账脱敏 + 死信 + 重放、内容级 ASR/OCR 复用(换目标不重烧)

## 快速开始

```bash
cd dovideo
uv venv --python 3.12 .venv && source .venv/bin/activate
uv pip install -e ".[dev]"

# 离线演示:无需 API Key / ffmpeg,跑通全链路(含护栏拦截与定向重检索)
python -m dovideo.demo

# 测试(60 个用例,全离线)
python -m pytest -q
```

配置真实模型:复制 `.env.example` 为 `.env`,填写 `SILICONFLOW_API_KEY`。

```python
# 最小用法
import asyncio
from dovideo.api.app import build_default_deps, create_app
from dovideo.jobs.worker import InlineJobRunner
from dovideo.core.analysis import AnalysisMode

deps = build_default_deps()          # 读取环境变量
deps.broadcast = lambda *a: None
runner = InlineJobRunner(deps)
outcome = asyncio.run(runner.submit("media-1", "总结这节课并出自测题", AnalysisMode.AUTO if False else AnalysisMode.GENERAL))
print(outcome.result.model_dump_json(indent=2))
```

## 目录

```
src/dovideo/
├── core/          # models / analysis / budget / telemetry / evidence(纯代码核验)/ llm / prompts(SYSTEM_POLICY)/ fake(离线)
├── context/       # VideoContext 流水线:asr(重试语义)· frames(场景选帧+dHash)· pipeline(双分支+窗口合并)
├── retrieval/     # chunking(5min 分块)· store(Qdrant+本地降级)· search(混合打分/定向重检索/证据搜索)
├── agent/         # loop(闭环+护栏)· router(模式路由,永不失败)
├── checkpoints/   # SQLite 真源 + 可选 Redis 热缓存;␟ 分隔 goalDigest;两段式修订
├── jobs/          # worker(幂等/重试/死信/内容级复用)
├── api/           # FastAPI:REST + SSE(复刻原契约;/health /analysis/* /media/*)
├── evaluation/    # 金标评测(structuredValid && claimSupport≥0.8 && keywordCoverage≥0.8)
├── mcp_server/    # MCP 工具:analyze_video / evidence_search / follow_up(需 pip install mcp)
└── observability/ # LangFuse 钩子(可选)
```

## HTTP 服务

```bash
uvicorn dovideo.api.app:create_app --factory --port 9101
# GET  /health
# POST /analysis/route            {"goal": "...}           → {mode, reason}(永不失败)
# POST /analysis/ai               {"mediaId","goal","mode"} → 202 受理 / 200 复用 / 409 重复
# GET  /analysis/analysis-status?mediaId&goal&mode
# GET  /analysis/analysis-events?mediaId&goal&mode   (SSE: event: task-status)
# GET  /analysis/agent-plan | /analysis/agent-trace
# GET  /analysis/evidence-search?mediaId&q=...
# POST /analysis/follow-up        {"mediaId","question"}
```

## 拓展点(相对原版)

- **离线优先**:FakeLLM + 注入式分支,无 Key 全链路可测可演示(原版必须真 Key)
- **asyncio 端到端**:原版 4 个有界线程池 → 单事件循环 + 显式重试语义
- **MCP Server**:视频分析能力直接进入 Claude Code 等客户端
- **金标评测 runner**: golden 用例回归,防 prompt 改动引发质量退化
- **模式可扩展**:新增模式 = 注册一份 `ModeProfile`(三段指令 + 必需 section keys),启动自检保证注册完整

## 与原版的刻意差异

RocketMQ → 进程内 runner(重投语义一致,可平移 ARQ);MySQL → SQLite(接口不变);MinIO → 本地路径 + `#timestampMs=` 锚点降级;Vue → Next.js(进行中,见蓝图 Phase 4)。

## 批量入库与金标评测(B 站合集实战)

`scripts/` 提供三个独立入口(均幂等可重跑,context/chunks 命中 checkpoint 不重烧 ASR/OCR):

```bash
# 1) 批量入库:目录内 mp4 → ASR∥OCR → 5min 分块 → Qdrant 双路入库 → Agent 总结
.venv/bin/python scripts/ingest_bilibili.py data/media/bilibili-mianshi --concurrency 2

# 2) 金标评测:13 期 × (面试官视角 goal + 5 关键词),三闸:结构合法 / 证据支持≥0.8 / 关键词覆盖≥0.8
.venv/bin/python scripts/eval_bilibili.py                 # 全量;报告落 evaluation/reports/
.venv/bin/python scripts/eval_bilibili.py --cases 03 06   # 只重跑失败用例

# 3) 检索验证:跨视频语义检索(Qdrant 全库) + 单视频 evidence_search 全链路
.venv/bin/python scripts/verify_kb.py
```

金标数据集:[evaluation/golden-bilibili-mianshi.json](evaluation/golden-bilibili-mianshi.json)
(B 站《计算机专业面试总结》合集 13 期,约 12 小时)。

实战校准的三条闸门经验(细节见各脚本注释):默认预算三闸门按"单次交互"设计,
长视频批量场景需放宽 `AGENT_MAX_DURATION_MS` / `AGENT_MAX_ESTIMATED_TOKENS` /
`LLM_TIMEOUT_SECONDS`;硅基流动对超长生成有 ~600s 服务端断连,高峰期(≈11 tok/s)
大草稿会反复熔断,离峰重试即可恢复(Planner 任务数随机,小规划自然通过)。
