# MCP 跨进程接入 SOP

> 目标读者：任何想让外部 AI 进程（Claude Code / Cursor / 扣子 / 自研 Agent）消费本视频知识库的人。
> 本文档是标准作业流程：照做即可从零接入并验收，不需要读服务端代码。

## 0. 架构与认证模型（30 秒版）

```
外部 MCP 客户端 ──Bearer client token──▶ mcp-server :9091 /mcp（Streamable HTTP, 无状态）
                                            │ 服务账号（自动登录 + 40100 自动重登录）
                                            ▼
                              DOVideo server :9090 /knowledge/*（跨视频 RAG）
                                            │
                              MySQL(分段真源) · Qdrant(向量) · MinIO(媒体) · RocketMQ(分析)
```

两级认证，职责不同，不要混用：

| 层 | 凭据 | 配置在哪 | 给谁 |
| --- | --- | --- | --- |
| 客户端 → mcp-server | `MCP_CLIENT_TOKENS`（逗号分隔多 token） | `.env` | 每个 AI 客户端一个 |
| mcp-server → server | `DOVIDEO_API_USERNAME/PASSWORD`（服务账号） | `.env` | 仅 mcp-server 自己 |

**红线：不要用 `DOVIDEO_API_TOKEN`（静态会话 token）模式**——上游会话过期（code 40100）后无刷新路径，MCP 工具会全部 401。服务账号模式下 40100 会自动重登录重试一次（`DovideoApiClient`）。

## 1. 一次性配置（已完成即跳过）

1. **基础设施与两个服务**：`scripts/dev-up.sh`（docker compose 起 MySQL/Redis/Qdrant/MinIO/RocketMQ → server 9090 → client 5173 → mcp-server 9091）。注意：`.env` 未设 `MCP_CLIENT_TOKENS` 时 MCP 分支会被跳过。
2. **服务账号**（知识库数据的主人账号，MCP 与之同视角）：
   ```bash
   curl -s -X POST http://127.0.0.1:9090/user/register -H 'Content-Type: application/json' \
     -d '{"username":"mcp_service","password":"<8位以上>","nickname":"MCP Service Account"}'
   ```
   用户名限字母/数字/下划线（不能带连字符）。
3. **`.env` 三行**（值含空格必须加引号）：
   ```ini
   MCP_CLIENT_TOKENS="<openssl rand -hex 24 生成的随机串>"
   DOVIDEO_API_USERNAME=mcp_service
   DOVIDEO_API_PASSWORD="<注册时的密码>"
   ```
   改完重启 mcp-server（`lsof -ti:9091 | xargs kill` 后按 dev-up.sh 第 80 行的方式拉起，注意必须 `unset SERVER_PORT`，否则适配器会被 .env 的 9090 劫持）。
4. **语料**（无数据则所有检索为空）：见 `scripts/import_corpus.py`（本地目录零重烧入库）或 Vue 工作台上传。

## 2. 外部客户端接入

端点：`http://<host>:9091/mcp`，协议 MCP Streamable HTTP（POST，JSON-RPC 2.0），Bearer 头携带 client token。

- **Claude Code**：`claude mcp add --transport http dovideo-knowledge http://127.0.0.1:9091/mcp --header "Authorization: Bearer <MCP_CLIENT_TOKENS 之一>"`
- **Cursor / 通用 JSON 配置**：
  ```json
  {
    "mcpServers": {
      "dovideo-knowledge": {
        "url": "http://127.0.0.1:9091/mcp",
        "headers": { "Authorization": "Bearer <token>" }
      }
    }
  }
  ```
- **任意 HTTP 进程**：`POST /mcp`，body 为 JSON-RPC（`initialize` → `notifications/initialized` → `tools/call`），参考 `scripts/mcp_e2e_client.py` 的完整握手。

## 3. 四个工具（全部只读）

| 工具 | 用途 | 关键参数 |
| --- | --- | --- |
| `list_knowledge_spaces` | 列出账号可见知识空间 | 无 |
| `search_video_knowledge` | 跨视频证据检索（命中带 mediaId+毫秒时间戳+摘录） | `query`（必填）、`spaceId`（缺省=扇出全部空间，上限 10）、`topK≤20`、`strategy: vector/keyword/hybrid` |
| `ask_video_knowledge` | **跨视频 RAG 问答**：自然语言回答 + 服务端逐字校验的引用；证据不足返回 `INSUFFICIENT_EVIDENCE` 拒答 | `query`（必填）、`spaceId`（缺省=默认空间）、`collectionId`、`topK≤20`、`strategy` |
| `get_video_evidence` | 拉某视频的原始转写/OCR/摘要行（可按时间窗缩窄） | `mediaId`（必填）、`startMs`/`endMs` |

`ask_video_knowledge` 返回结构（对齐 server `KnowledgeAnswer`，quote 上限 2000 字符）：

```json
{
  "answerability": "SUPPORTED | INSUFFICIENT_EVIDENCE",
  "answer": "自然语言回答全文",
  "citations": [{"segmentId","title","mediaId","startMs","endMs","startSec","endSec","claim","quote"}],
  "warnings": ["..."]
}
```

**语义约定（写给被接入的 AI）**：`INSUFFICIENT_EVIDENCE` 时必须转述拒答，不得自行编造；引用要带 mediaId 与秒级时间戳转述给用户。空账号（无任何空间）会返回确定性拒答而非报错。

真实返回样例（2026-09-27，13 期 B站面试八股语料，674 段）：

- `ask_video_knowledge {query: "做外卖或点评这类项目时，Redis 缓存一般怎么用？"}` → `SUPPORTED`，回答综合 Redis 概念与项目实战，**5 条引用命中两期视频**（11 苍穹外卖 + 12 黑马点评），每条带 mediaId 与秒级时间戳、逐字 quote。
- `ask_video_knowledge {query: "JVM 有哪些常见的垃圾回收器？"}` → `SUPPORTED`，3 条引用命中 10 期 JVM 视频 1920s 处（串行/并行/CMS）。
- `ask_video_knowledge {query: "React Hooks 的使用规则是什么？"}` → `INSUFFICIENT_EVIDENCE`，citations 为空——域外问题正确拒答。

完整样例与引用原文见 `docs/acceptance/rag-samples.md`。

## 4. 一键验收

```bash
scripts/mcp_sop_check.sh                       # 全链路：健康→握手→四工具→拒答护栏
scripts/mcp_sop_check.sh --ask-query "三次握手的过程是什么"
```

通过标准：脚本末行 `SOP CHECK PASSED`，exit 0。

## 5. 故障排查

| 症状 | 根因 | 处置 |
| --- | --- | --- |
| HTTP 401 | client token 缺失/不在 `MCP_CLIENT_TOKENS` | 核对 `.env`，重启 mcp-server |
| 工具结果 `code=40100` 反复出现 | 走了静态 token 模式 | 删 `DOVIDEO_API_TOKEN`，改用 `DOVIDEO_API_USERNAME/PASSWORD` |
| 工具结果 `DoVideo API ... failed: code=40100 ...`（一次性） | 上游会话刚好过期 | 服务账号模式会自动重登录重试；仍失败查 server 日志 `/tmp/dovideo-backend.log` |
| search/ask 全空 | 账号名下无空间或语料未入库 | `list_knowledge_spaces` 确认；用 `scripts/import_corpus.py` 入库 |
| GET /mcp 405/404 | 正常现象 | 本服务只实现 POST（无 SSE 长连接） |
| mcp-server 起不来撞 9090 | `.env` 的 `SERVER_PORT` 经宽松绑定劫持了适配器端口 | 启动子 shell 里 `unset SERVER_PORT SERVER_ADDRESS`（dev-up.sh 已修复） |
| audit 排查 | 需要看谁在什么时候调了什么 | `mcp-server/data/mcp-audit.jsonl`（JSONL：client/tool/success/耗时/错误） |

## 6. 变更记录

- 2026-09-27：新增 `ask_video_knowledge`（第 4 工具）；认证切服务账号模式；dev-up.sh 端口劫持修复；本 SOP 建立；一键验收 `SOP CHECK PASSED`（真实 13 期语料，含跨视频引用与拒答护栏）。
