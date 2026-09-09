"""离线 FakeLLM:按任务标记分发可脚本化的响应,供测试/演示/无 Key 运行。

每个 prompt 的 system 消息首行带 `# TASK: <NAME>` 标记,FakeLLM 据此路由。
"""

from __future__ import annotations

import json
from typing import Any

from dovideo.core.llm import ChatReply, EmbeddingUnavailableError, PermanentLLMError


def detect_task(messages: list[dict[str, str]]) -> str:
    for message in messages:
        content = message.get("content", "")
        if content.startswith("# TASK: "):
            return content.split("\n", 1)[0].removeprefix("# TASK: ").strip()
    return "UNKNOWN"


class FakeLLM:
    """按任务名排队响应;队列耗尽时重复最后一个。可注入失败与调用记录。"""

    def __init__(
        self,
        *,
        plans: list[dict] | None = None,
        repairs: list[dict] | None = None,
        revises: list[dict] | None = None,
        executes: list[dict] | None = None,
        critiques: list[dict] | None = None,
        mode: dict | None = None,
        summaries: list[dict] | None = None,
        intents: list[dict] | None = None,
        followups: list[str] | None = None,
    ) -> None:
        self._queues: dict[str, list[Any]] = {
            "PLANNER": list(plans or []),
            "PLANNER_REPAIR": list(repairs if repairs is not None else (plans or [])),
            "PLANNER_REVISE": list(revises if revises is not None else (plans or [])),
            "EXECUTOR": list(executes or []),
            "CRITIC": list(critiques or []),
            "MODE_ROUTER": [mode] if mode else [],
            "CHUNK_SUMMARY": list(summaries or []),
            "RETRIEVAL_INTENT": list(intents or []),
            "FOLLOW_UP": list(followups or []),
        }
        self.calls: list[tuple[str, int]] = []  # (task, message_count)
        self.fail_next: Exception | None = None
        self.chat_count = 0

    def _next(self, task: str) -> Any:
        queue = self._queues.get(task)
        if not queue:
            # 未配置的任务按"永久失败"处理,使上层降级逻辑(而非测试断言)生效
            raise PermanentLLMError(f"FakeLLM 未配置任务响应: {task}")
        item = queue.pop(0)
        if len(queue) == 0:
            queue.append(item)  # 末位响应可重复
        return item

    async def chat(
        self, messages: list[dict[str, str]], *, timeout_s: float, temperature: float = 0.3
    ) -> ChatReply:
        self.chat_count += 1
        if self.fail_next is not None:
            err, self.fail_next = self.fail_next, None
            raise err
        task = detect_task(messages)
        self.calls.append((task, len(messages)))
        payload = self._next(task)
        text = payload if isinstance(payload, str) else json.dumps(payload, ensure_ascii=False)
        return ChatReply(text=text, input_tokens=100, output_tokens=50)

    async def embed(self, texts: list[str]) -> list[list[float]]:
        raise EmbeddingDisabledError("FakeLLM 不提供 embedding,走降级路径")

    def calls_for(self, task: str) -> int:
        return sum(1 for t, _ in self.calls if t == task)


class EmbeddingDisabledError(EmbeddingUnavailableError):
    """FakeLLM 不提供 embedding → 走与真实服务故障相同的降级路径。"""
