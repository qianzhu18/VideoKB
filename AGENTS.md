# Project working rules

## Product implementation direction

- `DOVideo-AI` is the sole active product engineering project. Its product backend is Java 21 / Spring Boot and its web client is Vue.
- Do not move product implementation to Python or Node.js because the user has used those languages in other projects. Follow the explicit Java backend direction for this project.
- `https://github.com/qianzhu18/VideoKB` is the existing Python 3.11 / FastAPI project that the user wants converted to Java. Preserve the project and its capabilities; do not treat it as archive-only, discard it, or continue building product features in Python.
- The current GitHub archived/read-only flag is repository metadata, not the user's desired project outcome. The Java migration target location must follow the user's explicit direction; when not specified, ask whether the port belongs in `DOVideo-AI` or in the `VideoKB` repository itself.
- Python files may remain only as evaluation/data assets during migration. Port product capabilities to Java rather than extending the Python implementation.
- When a README, TODO, or report claims a capability is complete, verify it in source code and distinguish code presence from tests, real-data evaluation, and end-to-end acceptance.
- Treat Qdrant as the current vector store. Milvus is only a candidate until dual-write, backfill, shadow-query, and benchmark gates are met.

## Knowledge-base acceptance

- A backend endpoint is not a completed user-facing feature until the Vue workflow calls it and displays the answer, source, timestamp, evidence, and refusal state as applicable.
- Retrieval quality claims must name the corpus, metric, and evaluation artifact. The existing 30-case set and Python runner are evaluation assets, not proof of real-corpus readiness.
- Do not claim local-folder ingestion, MCP, or real-video processing is accepted based only on unit tests or documentation; require the corresponding end-to-end evidence.
