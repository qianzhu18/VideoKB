#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# evidence-benchmark-runtime owns the isolated DB/Qdrant/Redis/topic assignments.
export MILVUS_BM25_ENABLED=true
export MILVUS_URL="${MILVUS_URL:-http://127.0.0.1:19531}"
export MILVUS_BM25_COLLECTION="${MILVUS_BM25_COLLECTION:-evidence_bm25_20261009}"
export KB_BENCHMARK_ARGS="--knowledge.ingest.dispatch-enabled=false --knowledge.lexical.milvus.enabled=true --knowledge.lexical.milvus.url=$MILVUS_URL --knowledge.lexical.milvus.collection=$MILVUS_BM25_COLLECTION --app.cors.allowed-origins=http://127.0.0.1:15174"
exec bash scripts/evidence-benchmark-runtime.sh
