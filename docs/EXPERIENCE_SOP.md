# 视频知识库·完整体验 SOP

> 目标：任何人（包括未来的你自己）10 分钟内从零体验完整的跨视频检索知识库——Web 界面问答 + 外部 AI 客户端经 MCP 接入两条路。
> 本 SOP 的每一步都于 2026-09-28 在本机实机走查通过（截图见 `docs/acceptance/`）。

## 前置状态（一次性，通常已完成）

| 项 | 检查方式 | 未就绪时的动作 |
| --- | --- | --- |
| 基础设施 docker 栈 | `docker compose --env-file .env ps` 六个容器 Up | `./scripts/dev-up.sh` |
| 后端 9090 | `curl http://127.0.0.1:9090/health` → mysql/redis UP | `cd server && ./mvnw spring-boot:run`（记得 `unset SERVER_PORT` 或用 dev-up） |
| 前端 5173 | `curl http://localhost:5173/` → 200 | `cd client && npm run dev`；**用 http://localhost:5173 访问**（Vite 默认只绑 IPv6 的 ::1，用 127.0.0.1 会"无法连接后端"） |
| MCP 适配器 9091 | POST /mcp ping → 200 | `.env` 需有 `MCP_CLIENT_TOKENS`，重启 dev-up |
| 语料 | 登录后知识库可见 13 个 READY 视频 | `python3 scripts/import_corpus.py --seed evaluation/seeds/bilibili-mianshi-v1.json --root <媒体目录> --skip-asr --skip-import`（仅挂载）或完整跑 |
| 账号 | `mcp_service`（`.env` 的 `DOVIDEO_API_USERNAME/PASSWORD`） | `docs/MCP_SOP.md` §1 有注册命令 |

## 路线 A · Web 界面问答（5 分钟）

1. 打开 **http://localhost:5173**（务必 localhost），右上角「登录 / 注册」→ 用 `mcp_service` 账号登录。
2. 顶部切「**知识库**」→ 左侧选「**技术面试知识库**」→ 目录点「**B站面试八股合集**」→ 应看到 13 个 READY 来源（12 期 checkpoint 零重烧导入 + 01 期真 ASR）。
3. 在「向知识空间提问」输入 **跨视频问题**（演示问题清单见下），点「提问」→ 等待 20–60 秒（进度显示"整理证据中…"）。
4. 核对回答区五要素：
   - 徽章「**有证据支持**」（或域外问题显示「证据不足」）；
   - 回答正文（Markdown，标明观点来自哪个视频）；
   - 「N 条引用」：每条含来源视频文件名 + **秒级时间戳 + ↗** + claim + **逐字 quote**（ASR 原文，错字保留如 "relix"）；
   - 警告区（正常无内容）。
5. **点击引用的时间戳按钮（如 `6:00–7:00 ↗`）** → 自动切回视频工作台、打开该视频播放器并定位到引用窗口（实测 381s 落在 6:00–7:00 内）自动播放。
6. 输入**域外问题**（如 React Hooks）→ 应显示「证据不足」徽章 + 「当前知识库中没有找到足以支持这个回答的证据。」+「未检索到候选证据，未调用生成模型。」
7. （可选）「仅搜证据」按钮 = 纯检索模式，直接看跨视频命中列表（mediaId+时间戳+matchType+score）。

**演示问题清单**（实测效果）：

| 类型 | 问题 | 预期 |
| --- | --- | --- |
| 跨视频·项目结合 | 做外卖或点评这类项目时，Redis 缓存一般怎么用？ | 有证据支持，引用命中苍穹外卖+黑马点评两期 |
| 跨视频·单期精确 | JVM 有哪些常见的垃圾回收器？ | 引用命中 JVM 期 1920s 附近（串行/并行/CMS） |
| 概念精确 | MySQL 的索引为什么用 B+ 树而不是 B 树？ | 引用命中 MySQL 期，quote 保留 ASR 原文 |
| 域外·拒答 | React Hooks 的使用规则是什么？ | 证据不足 + 未调用生成模型 |
| 域外·拒答 | Kubernetes 的调度器是怎么工作的？ | 证据不足 |

## 路线 B · 外部 AI 客户端经 MCP（5 分钟）

> 完整运维细节在 `docs/MCP_SOP.md`；这里只给体验路径。

1. **Claude Code**（最简）：
   ```bash
   TOKEN=$(grep '^MCP_CLIENT_TOKENS=' .env | cut -d= -f2 | tr -d '"')
   claude mcp add --transport http dovideo-knowledge http://127.0.0.1:9091/mcp \
     --header "Authorization: Bearer $TOKEN"
   ```
   之后在 Claude Code 里直接问："我的视频知识库里，消息队列是怎么保证消息不丢的？"
2. **Cursor / Cherry Studio / 任意 JSON 配置客户端**：知识库页面左侧「MCP 出口」有一键复制的配置块（`http://<host>:9091/mcp` + Bearer 令牌），把 `<令牌>` 换成 `.env` 里 `MCP_CLIENT_TOKENS` 的值。
3. **任意 HTTP 进程 / 脚本**：
   ```bash
   scripts/mcp_sop_check.sh            # 一键验收：握手→四工具→ask→拒答全绿
   python3 scripts/mcp_e2e_client.py --token "$TOKEN"   # 逐步走协议
   ```
4. 四个工具：`list_knowledge_spaces` / `search_video_knowledge` / **`ask_video_knowledge`**（跨视频问答，带时间戳引用与拒答）/ `get_video_evidence`。

## 已达标的质量水位（评测背书）

评测 runner：`python3 eval/knowledge_eval.py --username mcp_service --password '<.env>' --golden eval/golden-v2.json --mode both`

| 指标（2026-09-28，31 条 golden） | 数字 | 门禁 |
| --- | --- | --- |
| 检索 Recall@5（hybrid，片段级时间窗口径） | **0.96** | ≥0.6 ✅ |
| 检索 MRR（hybrid） | **0.89** | — |
| 拒答正确率（ask，双向判定） | **6/6** | =1.0 ✅ |
| 引用有效率（评测侧独立逐字复核） | **65/65 = 1.0** | ≥0.8 ✅ |
| 关键词覆盖 | 0.64 | ≥0.5 ✅ |
| ask 延迟 p50 / p95 | 18s / 48s | 深度问答型，可接受 |

调优案例（面试素材）：基线 10 个 miss 归因为「复合长问题下模型提交 0 引用」（8 例）；prompt 增加"禁止交白卷"规则后 answerable 15→17/25、关键词覆盖 0.587→0.64，拒答与引用有效率保持满分。报告在 `eval/reports/eval-20260928-*-{baseline,tuned-anti-blank}.json`。

## 常见故障

| 症状 | 原因 | 处置 |
| --- | --- | --- |
| 页面一直"无法连接后端服务" | 用 127.0.0.1 访问（Vite 只绑 ::1） | 改用 **http://localhost:5173** |
| 提问后 60s+ 无响应且报连接错误 | 后端没起 / 正在被评测占用 | 看 `/tmp/dovideo-backend.log`；等评测跑完 |
| 回答"证据不足"但问题明明在库里 | 复合太长的问题先试试拆开问；或看 `eval/reports` 里该类 case | 换关键词重问 |
| MCP 工具 40100 | 走了静态 token 模式 | `docs/MCP_SOP.md` §5 |
| 引用点 ↗ 没跳转 | 该视频不在工作台列表（如脚本来源） | 只有点 VIDEO 来源的引用可回跳 |
