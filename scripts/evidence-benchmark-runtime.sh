#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
set -a
source .env
set +a
# The caller must prepare independent copies. Never use this URL against the product DB.
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
export DB_URL='jdbc:mysql://localhost:13307/media_db?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf-8&allowPublicKeyRetrieval=true'
export SERVER_PORT=19092
export REDIS_DATABASE=12
export QDRANT_URL=http://localhost:16333
export ROCKETMQ_ANALYSIS_TOPIC=evidence-analysis-20261008
export ROCKETMQ_ANALYSIS_GROUP=evidence-analysis-workers-20261008
export ROCKETMQ_ANALYSIS_DEAD_TOPIC=evidence-analysis-dead-20261008
export KNOWLEDGE_INGEST_TOPIC=evidence-ingest-20261008
export KNOWLEDGE_INGEST_GROUP=evidence-ingest-workers-20261008
export ROCKETMQ_PRODUCER_GROUP=evidence-producer-20261008
exec server/mvnw -f server/pom.xml -q compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.mainClass=com.example.server.ServerApplication \
  -Dexec.args="${KB_BENCHMARK_ARGS:---knowledge.ingest.dispatch-enabled=false}"
