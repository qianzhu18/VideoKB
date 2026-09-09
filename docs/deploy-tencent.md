# dovideo 腾讯服务器部署指南

目标形态:**视频知识库 AI 解析平台**——用户上传视频 → 证据约束 Agent 分析 → 时间戳可回溯的知识产物,经 MCP 可被 Claude Code 等客户端调用。

## 0. 前置要求(腾讯云)

- Ubuntu 22.04/24.04 或 Debian 12,≥ 2C4G(推荐 4C8G,ffmpeg/OCR 是 CPU 活)
- 已安装 Docker + Compose v2:`curl -fsSL https://get.docker.com | bash`
- 域名可选(直接 IP + 端口也能跑);硅基流动 API Key 一个

## 1. 上传代码

```bash
# 本机执行:把 dovideo 目录同步到服务器
rsync -avz --exclude .venv --exclude __pycache__ --exclude '*.db' --exclude data \
    "/Users/mac/qianzhu Vault/project/videoagent/dovideo/" \
    ubuntu@<服务器IP>:~/dovideo/
```

## 2. 配置

```bash
cd ~/dovideo
cp .env.production.example .env
openssl rand -hex 24   # 生成访问令牌
vim .env               # 填 SILICONFLOW_API_KEY 与 DOVIDEO_API_TOKEN
```

## 3. 启动

```bash
docker compose -f docker-compose.prod.yml --env-file .env up -d --build
curl http://127.0.0.1:9101/health
# 期望: {"code":0,"message":"success","data":"UP"}
```

容器只绑 `127.0.0.1:9101`;对外走 Nginx(第 5 步),不要裸暴露。

## 4. 平台 API 用法(令牌认证)

```bash
TOKEN=<你的DOVIDEO_API_TOKEN>
AUTH="Authorization: Bearer $TOKEN"

# 上传视频(multipart,≤几百 MB 视磁盘;分片续传为后置项)
curl -s $AUTH -F "file=@lecture.mp4" http://127.0.0.1:9101/media/upload
# → {"mediaId":"ab12...","bytes":...,"md5":"..."}

# 提交分析(mode: GENERAL/LEARNING/REVIEW/CREATION)
curl -s $AUTH -X POST http://127.0.0.1:9101/analysis/ai \
    -H 'Content-Type: application/json' \
    -d '{"media_id":"<mediaId>","goal":"整理本节课知识点并出自测题","mode":"LEARNING"}'
# → 202 受理;重复提交同一 (mediaId, goal) → 409;已完成 → 200 复用

# 轮询状态 / SSE 订阅 / 查询产物
curl -s $AUTH "http://127.0.0.1:9101/analysis/analysis-status?mediaId=<id>&goal=<goal>&mode=LEARNING"
curl -s $AUTH "http://127.0.0.1:9101/analysis/agent-plan?mediaId=<id>&goal=<goal>&mode=LEARNING"
curl -N  $AUTH "http://127.0.0.1:9101/analysis/analysis-events?mediaId=<id>&goal=<goal>&mode=LEARNING"

# 证据检索与追问(知识库核心交互)
curl -s $AUTH "http://127.0.0.1:9101/analysis/evidence-search?mediaId=<id>&q=二叉树"
curl -s $AUTH -X POST http://127.0.0.1:9101/analysis/follow-up \
    -H 'Content-Type: application/json' -d '{"media_id":"<id>","question":"平衡树和BST什么关系?"}'
```

MCP:客户端配置 `dovideo mcp`(见 src/dovideo/mcp_server/server.py)后,Claude Code 可直接调 analyze_video / evidence_search / follow_up 三个工具。

## 5. Nginx 反代 + HTTPS(对外必须)

```nginx
server {
    listen 443 ssl;
    server_name video.你的域名.com;
    # 证书: certbot --nginx -d video.你的域名.com
    client_max_body_size 2g;                 # 视频上传放行
    location / {
        proxy_pass http://127.0.0.1:9101;
        proxy_http_version 1.1;
        proxy_set_header Connection "";      # SSE 必需
        proxy_read_timeout 3600s;            # SSE 长连接
        proxy_buffering off;                 # SSE 必需
    }
}
```

防火墙(腾讯云控制台安全组):放行 80/443;**不要**放行 9101/6379/6333。

## 6. 数据与运维

- 持久化:`dovideo-data` 卷(/data 下 = SQLite checkpoint + 媒体文件)。备份即备份该卷。
- 日志:`docker logs -f dovideo-api`
- 升级:`git pull && docker compose -f docker-compose.prod.yml --env-file .env up -d --build`
- 失败任务重放:查询失败台账后人工重试提交(checkpoint 命中的阶段不会重算)

## 7. 诚实的现状边界(部署前必读)

1. **前端未建**:当前是纯 API 平台(curl/脚本/MCP 可用,浏览器工作台是 Phase 4 Next.js,未开工)。
2. **单用户令牌,无账号体系**:一个全局 Bearer token,没有多用户/配额。个人知识库够用,开放注册不行。
3. **上传为整文件直传**:大文件(GB 级)弱网体验差,分片续传未实现。
4. **任务是进程内执行**:重启容器会中断进行中的任务(终态与 checkpoint 落盘不丢,重新提交即可续跑);并发多任务建议后续切 ARQ worker。
5. **视频来源 = 上传文件**:yt-dlp URL 直取(B站/YouTube)在 ingestion 代码层已预留、未接 API。
6. **首次分析较慢**:ASR 按 60s 切片逐段转写,1 小时视频约几分钟(受硅基流动限速),受 SSE 进度可观测。

## 8. 已验证项(2026-09-07 本机)

- 60/60 pytest 全绿;离线 demo 全链路通过
- 生产镜像 docker build 成功、compose 配置校验通过
- 本机真实媒体冒烟:ffmpeg 生成测试视频 → /media/upload → /analysis/ai → 状态收敛 COMPLETED(见 docs 本文件附带的冒烟记录)
