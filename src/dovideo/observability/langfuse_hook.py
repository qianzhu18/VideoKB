"""LangFuse 可观测钩子(扩展,原项目没有):可选启用,未配置时零开销。

环境变量 LANGFUSE_PUBLIC_KEY / LANGFUSE_SECRET_KEY / LANGFUSE_HOST 存在且
langfuse 包已安装时,把遥测计数器作为一次 trace 记录上报。
"""

from __future__ import annotations

import logging

from dovideo.core.telemetry import Telemetry

logger = logging.getLogger(__name__)


class LangfuseHook:
    def __init__(self) -> None:
        self._client = None
        self.enabled = False
        import os

        public_key = os.environ.get("LANGFUSE_PUBLIC_KEY", "")
        secret_key = os.environ.get("LANGFUSE_SECRET_KEY", "")
        host = os.environ.get("LANGFUSE_HOST", "")
        if not (public_key and secret_key and host):
            return
        try:
            from langfuse import Langfuse  # type: ignore

            self._client = Langfuse(public_key=public_key, secret_key=secret_key, host=host)
            self.enabled = True
        except ImportError:
            logger.info("langfuse 未安装,可观测降级为本地 Telemetry")
        except Exception as exc:  # 观测故障不阻断主链路
            logger.warning("LangFuse 初始化失败: %s", exc)

    def flush_trace(self, telemetry: Telemetry, *, name: str = "dovideo.agent-loop", metadata: dict | None = None) -> None:
        if not self.enabled or self._client is None:
            return
        try:
            self._client.trace(
                name=name,
                id=telemetry.trace_id,
                metadata={"telemetry": telemetry.to_dict(), **(metadata or {})},
            )
        except Exception as exc:
            logger.warning("LangFuse 上报失败: %s", exc)


__all__ = ["LangfuseHook"]
