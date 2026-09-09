"""向量存储:Qdrant(REST)+ 本地内存降级(移植自 service/QdrantVectorStore.java)。

- point id = uuid5("mediaId:startMs:endMs") 确定性生成 → 幂等 upsert
- 懒建集合(404 才 PUT,失败即复位 ready 标志)
- 查询失败 → 调用方回落本地余弦并计数 vectorStoreFallbacks
"""

from __future__ import annotations

import math
import uuid

import httpx

from dovideo.core.models import VideoChunk


def deterministic_point_id(media_id: str, start_ms: int, end_ms: int) -> str:
    return str(uuid.uuid5(uuid.NAMESPACE_OID, f"{media_id}:{start_ms}:{end_ms}"))


def cosine(a: list[float], b: list[float]) -> float:
    if not a or not b or len(a) != len(b):
        return 0.0
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(y * y for y in b))
    if na == 0 or nb == 0:
        return 0.0
    return dot / (na * nb)


class VectorStoreUnavailableError(Exception):
    pass


class QdrantStore:
    def __init__(self, *, url: str, api_key: str, collection: str) -> None:
        self._client = httpx.AsyncClient(
            base_url=url.rstrip("/"),
            headers={"api-key": api_key} if api_key else {},
            timeout=httpx.Timeout(3.0, read=10.0),
        )
        self.collection = collection
        self._ready = False

    async def ensure_collection(self) -> None:
        if self._ready:
            return
        resp = await self._client.get(f"/collections/{self.collection}")
        if resp.status_code == 404:
            put = await self._client.put(
                f"/collections/{self.collection}",
                json={"vectors": {"size": 1024, "distance": "Cosine"}},
            )
            if put.status_code >= 400:
                self._ready = False
                raise VectorStoreUnavailableError(f"创建集合失败: HTTP {put.status_code}")
        elif resp.status_code >= 400:
            raise VectorStoreUnavailableError(f"查询集合失败: HTTP {resp.status_code}")
        self._ready = True

    async def upsert(self, media_id: str, chunks: list[VideoChunk]) -> None:
        points = [
            {
                "id": deterministic_point_id(media_id, c.start_ms, c.end_ms),
                "vector": c.embedding,
                "payload": {"mediaId": media_id, "startMs": c.start_ms, "endMs": c.end_ms},
            }
            for c in chunks
            if c.embedding
        ]
        if not points:
            return
        await self.ensure_collection()
        resp = await self._client.put(
            f"/collections/{self.collection}/points", json={"points": points}
        )
        if resp.status_code >= 400:
            raise VectorStoreUnavailableError(f"upsert 失败: HTTP {resp.status_code}")

    async def query(
        self, media_id: str, vector: list[float], limit: int
    ) -> list[tuple[str, float]]:
        if not vector:
            return []
        await self.ensure_collection()
        resp = await self._client.post(
            f"/collections/{self.collection}/points/query",
            json={
                "query": vector,
                "limit": limit,
                "with_payload": True,
                "filter": {"must": [{"key": "mediaId", "match": {"value": media_id}}]},
            },
        )
        if resp.status_code >= 400:
            raise VectorStoreUnavailableError(f"query 失败: HTTP {resp.status_code}")
        hits = []
        for point in resp.json().get("result", {}).get("points", []):
            payload = point.get("payload", {})
            key = f"{payload.get('startMs')}:{payload.get('endMs')}"
            hits.append((key, float(point.get("score", 0.0))))
        return hits

    async def aclose(self) -> None:
        await self._client.aclose()


class LocalVectorStore:
    """内存降级:Qdrant 不可用时回落本地余弦(原版语义)。"""

    def __init__(self) -> None:
        self._by_media: dict[str, list[tuple[str, list[float]]]] = {}

    def load(self, media_id: str, chunks: list[VideoChunk]) -> None:
        self._by_media[media_id] = [
            (c.key, c.embedding) for c in chunks if c.embedding
        ]

    def upsert(self, media_id: str, chunks: list[VideoChunk]) -> None:
        existing = dict(self._by_media.get(media_id, []))
        for c in chunks:
            if c.embedding:
                existing[c.key] = c.embedding
        self._by_media[media_id] = list(existing.items())

    def query(self, media_id: str, vector: list[float], limit: int) -> list[tuple[str, float]]:
        scored = [
            (key, cosine(vector, emb))
            for key, emb in self._by_media.get(media_id, [])
        ]
        scored.sort(key=lambda kv: kv[1], reverse=True)
        return scored[:limit]


class CompositeVectorStore:
    """写双路;读优先 Qdrant,失败回落本地 + 计数。"""

    def __init__(self, qdrant: QdrantStore | None, local: LocalVectorStore) -> None:
        self.qdrant = qdrant
        self.local = local
        self.fallbacks = 0

    async def sync(self, media_id: str, chunks: list[VideoChunk]) -> None:
        self.local.load(media_id, chunks)
        if self.qdrant is None:
            return
        try:
            await self.qdrant.upsert(media_id, chunks)
        except (VectorStoreUnavailableError, httpx.TransportError) as exc:
            self.fallbacks += 1
            self.local.upsert(media_id, chunks)
            _ = exc  # 已计数,不阻断主链路

    async def query(
        self, media_id: str, vector: list[float], limit: int
    ) -> tuple[list[tuple[str, float]], bool]:
        """返回 (hits, used_fallback)。"""
        if self.qdrant is not None and vector:
            try:
                return await self.qdrant.query(media_id, vector, limit), False
            except (VectorStoreUnavailableError, httpx.TransportError):
                self.fallbacks += 1
        return self.local.query(media_id, vector, limit), self.qdrant is not None
