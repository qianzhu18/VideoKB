# 本地评测语料

`technical-learning-v1.json` 定义下一轮 VideoKB 验收使用的本地技术视频语料。它包含 13 条案例、主题、稳定媒体 ID、完整性指纹、期望关键词和通过阈值；视频、转写、数据库和评测报告都不提交到仓库。

本语料当前约 1.23 GB、11.15 小时。它是维护者的本地验收集，不是公开分发的数据集，也不应被解释为通用 benchmark 或模型排行榜。

## 使用方式

```bash
# 1. 确认本地媒体与 manifest 一致。
.venv/bin/python scripts/verify_corpus.py \
  --media-dir /path/to/videos

# 2. 批量入库。首次运行会调用外部 ASR、OCR 和模型服务。
.venv/bin/python scripts/ingest_corpus.py /path/to/videos \
  --id-prefix technical-learning --concurrency 1

# 3. 基于已有 checkpoint 跑金标评测。
.venv/bin/python scripts/eval_corpus.py
```

`eval_corpus.py` 的单案例通过条件是：结构完整、结论证据支持率不低于 `0.8`、关键词覆盖率不低于 `0.8`。整组通过率不低于 `0.8` 才能通过本轮金标门禁。

## 边界

- 校验器验证文件数量、文件大小、SHA-256 和时长，避免将不同媒体误当成同一版本语料。
- 金标评测覆盖受控 Agent 的结构、证据与主题覆盖，不覆盖跨视频 RAG 回答、引用精度、拒答率、延迟或成本。
- 任何评测报告都必须带上 manifest 名称、运行时间、模型配置与实际媒体版本，才能用于前后版本比较。
