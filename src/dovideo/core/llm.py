"""LLM 客户端层:OpenAI 兼容网关(硅基流动)+ 重试语义 + 结构化 JSON 解析。

移植自 utils/DeepSeekUtils.java 的调用工程:
- 重试 3 次,指数退避 1s/2s/4s;408/429/5xx/网络错误 → RetriableLLMError,其余 → PermanentLLMError
- 结构化输出:剥 ```json 围栏取首 { 到末 };解析失败原样重发一次并追加严格 JSON 指令
- 预算感知:模型调用超时 = min(模型超时, 剩余预算)
"""

from __future__ import annotations

import asyncio
import json
import math
from typing import Any, Protocol

import httpx
from pydantic import BaseModel

from dovideo.core.budget import AgentExecutionBudget, BudgetExhaustedError
from dovideo.core.telemetry import Telemetry


class RetriableLLMError(Exception):
    """可重试:网络抖动 / 408 / 429 / 5xx(异常类型即重试语义)。"""


class PermanentLLMError(Exception):
    """永久失败:4xx 等,重投无益。"""


class StructuredParseError(Exception):
    """模型输出不是合法 JSON 对象。"""


class EmbeddingUnavailableError(Exception):
    """Embedding 服务不可用 → 调用方降级为纯关键词检索。"""


class ChatReply(BaseModel):
    text: str
    input_tokens: int = 0
    output_tokens: int = 0


class LLMClient(Protocol):
    async def chat(
        self, messages: list[dict[str, str]], *, timeout_s: float, temperature: float = 0.3
    ) -> ChatReply: ...

    async def embed(self, texts: list[str]) -> list[list[float]]: ...


def _tokens_from_usage(usage: dict[str, Any] | None) -> tuple[int, int]:
    if not usage:
        return 0, 0
    return int(usage.get("prompt_tokens") or 0), int(usage.get("completion_tokens") or 0)


class SiliconFlowLLMClient:
    """OpenAI 兼容 /chat/completions 与 /embeddings。"""

    def __init__(
        self,
        *,
        base_url: str,
        api_key: str,
        model: str,
        embedding_model: str = "BAAI/bge-m3",
        max_attempts: int = 3,
    ) -> None:
        self._client = httpx.AsyncClient(
            base_url=base_url.rstrip("/"),
            headers={"Authorization": f"Bearer {api_key}"},
            timeout=httpx.Timeout(10.0, read=None),
        )
        self.model = model
        self.embedding_model = embedding_model
        self._max_attempts = max_attempts

    async def chat(
        self, messages: list[dict[str, str]], *, timeout_s: float, temperature: float = 0.3
    ) -> ChatReply:
        payload: dict[str, Any] = {
            "model": self.model,
            "messages": messages,
            "temperature": temperature,
        }
        last_error: Exception | None = None
        for attempt in range(self._max_attempts):
            try:
                resp = await self._client.post(
                    "/chat/completions",
                    json=payload,
                    timeout=httpx.Timeout(timeout_s),
                )
            except (httpx.TransportError, asyncio.TimeoutError) as exc:
                last_error = RetriableLLMError(f"网络错误: {exc}")
            else:
                if resp.status_code in (408, 429) or resp.status_code >= 500:
                    last_error = RetriableLLMError(f"HTTP {resp.status_code}: {resp.text[:200]}")
                elif resp.status_code >= 400:
                    raise PermanentLLMError(f"HTTP {resp.status_code}: {resp.text[:200]}")
                else:
                    data = resp.json()
                    in_tok, out_tok = _tokens_from_usage(data.get("usage"))
                    try:
                        text = data["choices"][0]["message"]["content"] or ""
                    except (KeyError, IndexError, TypeError) as exc:
                        raise PermanentLLMError(f"响应结构异常: {data}") from exc
                    return ChatReply(text=text, input_tokens=in_tok, output_tokens=out_tok)
            if attempt < self._max_attempts - 1:
                await asyncio.sleep(2**attempt)  # 1s, 2s, 4s 指数退避
        assert last_error is not None
        raise last_error

    async def embed(self, texts: list[str]) -> list[list[float]]:
        try:
            resp = await self._client.post(
                "/embeddings", json={"model": self.embedding_model, "input": texts}
            )
        except (httpx.TransportError, asyncio.TimeoutError) as exc:
            raise EmbeddingUnavailableError(str(exc)) from exc
        if resp.status_code >= 400:
            raise EmbeddingUnavailableError(f"HTTP {resp.status_code}")
        data = resp.json()
        try:
            items = sorted(data["data"], key=lambda d: d["index"])
            return [item["embedding"] for item in items]
        except (KeyError, TypeError, ValueError) as exc:
            raise EmbeddingUnavailableError(f"embedding 响应结构异常: {exc}") from exc

    async def aclose(self) -> None:
        await self._client.aclose()


def extract_json_object(text: str) -> dict[str, Any]:
    """剥 ```json 围栏后取首个 { 到末个 } 的子串反序列化。"""
    cleaned = text.strip()
    if cleaned.startswith("```"):
        first_newline = cleaned.find("\n")
        if first_newline != -1:
            cleaned = cleaned[first_newline + 1 :]
        if cleaned.rstrip().endswith("```"):
            cleaned = cleaned.rstrip()[:-3]
    start, end = cleaned.find("{"), cleaned.rfind("}")
    if start == -1 or end <= start:
        raise StructuredParseError(f"未找到 JSON 对象: {text[:120]}")
    try:
        obj = json.loads(cleaned[start : end + 1])
    except json.JSONDecodeError as exc:
        raise StructuredParseError(f"JSON 解析失败: {exc}") from exc
    if not isinstance(obj, dict):
        raise StructuredParseError("顶层不是 JSON 对象")
    return obj


class _NullBudget:
    """预算占位:辅助调用(摘要/路由/追问)不走总预算闸门时使用。"""

    def model_timeout_seconds(self, timeout_s: float) -> float:
        return timeout_s

    def check(self, **_: Any) -> None:
        return None


async def structured_chat(
    llm: LLMClient,
    messages: list[dict[str, str]],
    *,
    timeout_s: float,
    budget: AgentExecutionBudget | None,
    telemetry: Telemetry,
    cost_fn=None,
) -> dict[str, Any]:
    """带预算检查 + 严格 JSON 重试一次的结构化调用(原版 parseJson + structuredOutputRetries)。"""
    budget = budget or _NullBudget()
    from dovideo.core.textnorm import estimate_messages_tokens

    def _estimate(messages_: list[dict[str, str]]) -> int:
        return estimate_messages_tokens(messages_)

    budget.check(
        used_tokens=telemetry.total_tokens,
        used_cost=telemetry.estimated_cost,
        estimated_extra_tokens=_estimate(messages),
    )
    timeout = budget.model_timeout_seconds(timeout_s)
    reply = await llm.chat(messages, timeout_s=timeout)
    telemetry.model_call(
        input_tokens=reply.input_tokens or _estimate(messages),
        output_tokens=reply.output_tokens,
        cost=(cost_fn or (lambda i, o: 0.0))(reply.input_tokens, reply.output_tokens),
    )
    try:
        return extract_json_object(reply.text)
    except StructuredParseError:
        telemetry.incr("structuredOutputRetries")
        budget.check(used_tokens=telemetry.total_tokens, used_cost=telemetry.estimated_cost)
        retry_messages = [*messages, {"role": "assistant", "content": reply.text[:2000]}]
        retry_messages.append(
            {"role": "user", "content": "请严格返回合法 JSON,不要添加解释或代码块。"}
        )
        reply2 = await llm.chat(
            retry_messages, timeout_s=budget.model_timeout_seconds(timeout_s)
        )
        telemetry.model_call(
            input_tokens=reply2.input_tokens or _estimate(retry_messages),
            output_tokens=reply2.output_tokens,
            cost=(cost_fn or (lambda i, o: 0.0))(reply2.input_tokens, reply2.output_tokens),
        )
        return extract_json_object(reply2.text)


def heuristic_cost_fn(input_price: float, output_price: float):
    """返回 (input_tokens, output_tokens) -> cost 的闭包。"""

    def _cost(input_tokens: int, output_tokens: int) -> float:
        return (
            input_tokens * input_price / 1_000_000 + output_tokens * output_price / 1_000_000
        )

    return _cost
