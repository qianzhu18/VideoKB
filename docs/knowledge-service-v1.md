# Knowledge Service V1

本批交付将现有的单视频系统接入“个人知识库资产层”。它管理视频的归属、目录和版本入口；跨视频向量检索与 MCP 将在下一批建立在这些稳定 ID 之上。

## 已交付能力

- 每个用户首次访问自动拥有一个 `未分类` 默认空间；
- 创建、读取、更新知识空间；默认空间不可改名；
- 创建、读取、更新、删除目录树，并阻止跨空间挂载、循环移动和非空目录删除；
- 已有视频可移入任意空间/目录；
- 新上传的视频自动创建 `VIDEO / PENDING / version 1` 知识来源；
- 删除视频时，知识来源标记为 `DELETED`，普通查询不会再返回它；
- 所有 `/knowledge/**` 接口经过既有 Bearer Token 鉴权和用户归属校验。

## API

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `GET` | `/knowledge/spaces` | 列出当前用户的知识空间 |
| `POST` | `/knowledge/spaces` | 创建空间，Body：`name`, `description` |
| `PATCH` | `/knowledge/spaces/{spaceId}` | 更新空间名称或描述 |
| `GET` | `/knowledge/spaces/{spaceId}/collections` | 读取空间目录树（扁平 `path` 列表） |
| `POST` | `/knowledge/spaces/{spaceId}/collections` | 创建目录，Body：`parentId?`, `name`, `sortOrder?` |
| `PATCH` | `/knowledge/collections/{collectionId}` | 重命名、排序或移动目录 |
| `DELETE` | `/knowledge/collections/{collectionId}` | 删除空目录 |
| `GET` | `/knowledge/sources?spaceId=&collectionId?` | 读取指定空间/目录的有效内容源 |
| `POST` | `/knowledge/sources/media/{mediaId}` | 将已有视频加入/移动到空间，Body：`spaceId`, `collectionId?` |
| `PATCH` | `/knowledge/sources/{sourceId}/location` | 移动内容源，Body：`spaceId`, `collectionId?` |

接口均沿用统一响应：`{ "code": 0, "message": "success", "data": ... }`。

## 本地验收路径

1. 启动 Docker 基础设施、前端与 Java 21 后端；
2. 注册并登录，取得 `Authorization: Bearer <token>`；
3. `GET /knowledge/spaces`，确认有 `未分类`；
4. 创建 `学习` 空间与 `Java / JVM` 目录；
5. 上传一个视频后，调用 `POST /knowledge/sources/media/{mediaId}` 放入 `JVM`；
6. 用来源查询接口确认视频出现；删除该视频后再次查询，应不再返回。

## 当前边界

本批只完成资产组织与接入，不宣称已具备跨视频 RAG。`knowledge_sources`、`knowledge_source_versions` 和 `knowledge_segments` 已提供后续 P2 索引任务所需的权威元数据边界；Qdrant payload、混合召回、证据回填和 MCP 尚未实现。
