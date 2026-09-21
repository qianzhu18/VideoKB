# VideoKB

VideoKB 是一个本地优先的视频分析与检索工具。它把转写、画面文字和时间戳整理为可检索的证据，并在生成结论时保留回看原视频所需的定位信息。

项目当前处于 `0.2.0` 开发阶段。核心链路可离线测试，真实媒体、模型和向量库的联调仍在持续完善。

## 能做什么

- 分析本地视频，合并 ASR 与 OCR 为带时间轴的 `VideoContext`。
- 按用户目标检索相关片段，由 Planner、Executor、Critic 组成受控分析流程。
- 对结论做结构与时间戳证据核验，证据不足时保留不确定性。
- 保存解析上下文、草稿和结果 checkpoint，任务重试时避免重复处理已完成阶段。
- 在 Qdrant 可用时进行向量检索，不可用时回退本地向量或关键词检索。
- 通过可选 MCP 服务暴露分析、证据搜索和追问工具。

## 设计重点

长视频处理的难点不只是把视频送给模型。VideoKB 将外部调用、检索和生成拆开处理：媒体内容先作为不可信数据进入证据层，生成结果再由程序核验结构和证据引用。预算、重试、降级和 checkpoint 都围绕这个流程设计。

```text
本地视频
  └─ ASR 与 OCR 并行提取
      └─ 时序 VideoContext
          └─ 分块、索引与定向检索
              └─ Planner → Executor → Critic
                  └─ 带时间戳证据的结构化结果
```

## 快速开始

环境要求：Python 3.11+。真实视频处理还需要 `ffmpeg` 和 `tesseract`，真实模型调用需要配置 `SILICONFLOW_API_KEY`。

```bash
git clone https://github.com/qianzhu18/VideoKB.git
cd VideoKB

uv venv --python 3.12 .venv
source .venv/bin/activate
uv pip install -e ".[dev]"

# 不依赖 API Key 或媒体文件的离线演示
python -m dovideo.demo

# 离线回归测试
python -m pytest -q
```

复制 `.env.example` 为 `.env` 后再填写模型和可选 Qdrant 配置。仓库不包含视频、数据库、模型凭证或其他运行时产物。

## 本地服务

```bash
uvicorn dovideo.api.app:create_app --factory --port 9101
```

服务提供健康检查、异步分析、SSE 状态流、证据搜索和追问接口。完整路由见 `src/dovideo/api/`。MCP 适配器是可选模块，需要安装额外依赖并完成本地 E2E 验证后再接入客户端。

## 评测

项目提供结构合法性、结论证据支持率和关键词覆盖率的金标评测框架。`evaluation/technical-learning-v1.json` 是下一轮本地验收使用的 13 条视频清单与预期检查项，媒体文件本身不随仓库分发。

```bash
# 先将自己的本地视频入库。目录中的 mp4 文件按名称序号排序。
.venv/bin/python scripts/ingest_corpus.py /path/to/videos \
  --id-prefix technical-learning --concurrency 1

# 再运行金标评测，报告会写入 evaluation/reports/。
.venv/bin/python scripts/eval_corpus.py
```

评测集的使用条件、数据边界和验收步骤见 [evaluation/README.md](./evaluation/README.md)。当前评测框架不能替代真实 RAG 问答、引用精度和成本评测，这些内容列在 [下一轮验收计划](./docs/NEXT_PERSONAL_ACCEPTANCE.md)。

## 项目结构

```text
src/dovideo/
├── agent/          # 受控分析循环和模式路由
├── api/            # FastAPI、REST 与 SSE
├── checkpoints/    # SQLite 真源和可选 Redis 热缓存
├── context/        # ASR、选帧、OCR 与时间轴融合
├── core/           # 模型、预算、证据核验、提示词和离线替身
├── evaluation/     # 金标评测器
├── jobs/           # 幂等、重试、失败记录与重放
├── mcp_server/     # 可选 MCP 工具服务
├── retrieval/      # 分块、向量存储与混合检索
└── observability/  # 可选 LangFuse 钩子
```

## 文档与路线图

- [工程文档导航](./docs/README.md)
- [下一轮个人验收计划](./docs/NEXT_PERSONAL_ACCEPTANCE.md)
- [评测说明](./evaluation/README.md)
- [变更记录](./CHANGELOG.md)

仓库也保留了一组面向技术讨论和项目复盘的维护者笔记。它们只说明设计取舍和可验证边界，不构成对功能成熟度、性能或生产可用性的承诺。
