#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
set -a
source .env
set +a
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
# The database must be created separately. Never point this fixture at the product DB.
export DB_URL='jdbc:mysql://localhost:3307/videoagent_arch_20261007?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf-8&allowPublicKeyRetrieval=true'
export SERVER_PORT=19090
export REDIS_DATABASE=13
export QDRANT_COLLECTION=architecture_fixture_20261007
export ROCKETMQ_ANALYSIS_TOPIC=architecture-analysis-20261007
export ROCKETMQ_ANALYSIS_GROUP=architecture-analysis-workers-20261007
export ROCKETMQ_ANALYSIS_DEAD_TOPIC=architecture-analysis-dead-20261007
export KNOWLEDGE_INGEST_TOPIC=architecture-ingest-20261007
export KNOWLEDGE_INGEST_GROUP=architecture-ingest-workers-20261007
export ROCKETMQ_PRODUCER_GROUP=architecture-producer-20261007
export SILICONFLOW_API_KEY=architecture-placeholder
export SILICONFLOW_BASE_URL=http://127.0.0.1:1/v1
export MANAGEMENT_EXPOSURE=health,prometheus,metrics
export CORS_ALLOWED_ORIGINS=http://127.0.0.1:15173
exec server/mvnw -f server/pom.xml -q test-compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
  -Dexec.mainClass=com.example.architecture.ArchitectureFixtureRuntime -Dexec.classpathScope=test
