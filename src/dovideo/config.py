"""运行配置:全部来自环境变量,与原 DOVideo-AI .env 命名对齐。"""

from __future__ import annotations

import os
from dataclasses import dataclass


def _env(name: str, default: str = "") -> str:
    return os.environ.get(name, default)


def _env_int(name: str, default: int) -> int:
    try:
        return int(os.environ.get(name, default))
    except ValueError:
        return default


def _env_float(name: str, default: float) -> float:
    try:
        return float(os.environ.get(name, default))
    except ValueError:
        return default


@dataclass(slots=True)
class Settings:
    """与原项目 application.properties / .env.example 一一对应的配置。"""

    siliconflow_api_key: str = ""
    siliconflow_base_url: str = "https://api.siliconflow.cn/v1"
    llm_model: str = "deepseek-ai/DeepSeek-V3.2"
    llm_timeout_seconds: float = 300.0

    embedding_model: str = "BAAI/bge-m3"
    asr_model: str = "TeleAI/TeleSpeechASR"

    agent_max_rounds: int = 2
    agent_max_duration_ms: int = 120_000
    agent_max_estimated_tokens: int = 50_000
    agent_max_estimated_cost: float = 0.0
    llm_input_price_per_million: float = 0.0
    llm_output_price_per_million: float = 0.0

    # 上下文打包预算(原版 MAX_CONTEXT_CHARS=24000,第一条 segment 永远保留)
    max_context_chars: int = 24_000
    # 检索分块 5 分钟、合并窗口 60 秒
    chunk_ms: int = 300_000
    window_ms: int = 60_000
    top_k: int = 3
    max_user_hits: int = 8

    qdrant_url: str = ""
    qdrant_api_key: str = ""
    qdrant_collection: str = "video_chunks"

    redis_url: str = ""
    db_path: str = "dovideo.db"

    ffmpeg_bin: str = "ffmpeg"
    ocr_command: str = "tesseract"
    # 服务器部署:媒体文件落盘目录;设置了 api_token 时所有非 /health 接口要求 Bearer 认证
    media_dir: str = "data/media"
    api_token: str = ""

    @classmethod
    def from_env(cls) -> "Settings":
        return cls(
            siliconflow_api_key=_env("SILICONFLOW_API_KEY"),
            siliconflow_base_url=_env("SILICONFLOW_BASE_URL", "https://api.siliconflow.cn/v1"),
            llm_model=_env("LLM_MODEL", "deepseek-ai/DeepSeek-V3.2"),
            llm_timeout_seconds=_env_float("LLM_TIMEOUT_SECONDS", 300.0),
            embedding_model=_env("EMBEDDING_MODEL", "BAAI/bge-m3"),
            asr_model=_env("ASR_MODEL", "TeleAI/TeleSpeechASR"),
            agent_max_rounds=_env_int("AGENT_MAX_ROUNDS", 2),
            agent_max_duration_ms=_env_int("AGENT_MAX_DURATION_MS", 120_000),
            agent_max_estimated_tokens=_env_int("AGENT_MAX_ESTIMATED_TOKENS", 50_000),
            agent_max_estimated_cost=_env_float("AGENT_MAX_ESTIMATED_COST", 0.0),
            llm_input_price_per_million=_env_float("LLM_INPUT_PRICE_PER_MILLION", 0.0),
            llm_output_price_per_million=_env_float("LLM_OUTPUT_PRICE_PER_MILLION", 0.0),
            max_context_chars=_env_int("MAX_CONTEXT_CHARS", 24_000),
            chunk_ms=_env_int("CHUNK_MS", 300_000),
            window_ms=_env_int("WINDOW_MS", 60_000),
            top_k=_env_int("RETRIEVAL_TOP_K", 3),
            max_user_hits=_env_int("MAX_USER_HITS", 8),
            qdrant_url=_env("QDRANT_URL"),
            qdrant_api_key=_env("QDRANT_API_KEY"),
            qdrant_collection=_env("QDRANT_COLLECTION", "video_chunks"),
            redis_url=_env("REDIS_URL"),
            db_path=_env("DOVIDEO_DB_PATH", "dovideo.db"),
            ffmpeg_bin=_env("FFMPEG_DIR", "") or "ffmpeg",
            ocr_command=_env("OCR_COMMAND", "tesseract"),
            media_dir=_env("DOVIDEO_MEDIA_DIR", "data/media"),
            api_token=_env("DOVIDEO_API_TOKEN"),
        )

    @property
    def llm_configured(self) -> bool:
        return bool(self.siliconflow_api_key)
