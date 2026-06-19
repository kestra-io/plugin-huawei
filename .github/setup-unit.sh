#!/bin/bash
set -euo pipefail

# OBS integration tests run against either real Huawei Cloud OBS (preferred, in CI) or a
# local MinIO instance (fallback, for forks/PRs where org secrets are unavailable).
#
# The org secrets HUAWEI_ACCESS_KEY / HUAWEI_SECRET_ACCESS_KEY are provisioned by
# kestra-io/flows-engineering (terraform/huawei-unittest.tf) with visibility "all" for
# kestra-io/plugin-* repos, and are exposed here as env vars by the secrets-to-env step in
# the reusable plugins.yml workflow. The dedicated IAM user has "OBS OperateAccess" and the
# pre-created bucket "kestra-unit-test" lives in region eu-west-101 (1-day object lifecycle).
#
# Test-time configuration is bridged to the OBS_TEST_* contract read by AbstractMinioTest and
# written to $GITHUB_ENV so it persists into the subsequent "gradle check" step.

if [ -n "${HUAWEI_ACCESS_KEY:-}" ] && [ -n "${HUAWEI_SECRET_ACCESS_KEY:-}" ]; then
  echo "Huawei Cloud credentials detected — targeting real Huawei Cloud OBS (eu-west-101)."
  {
    echo "OBS_MINIO_TESTS=true"
    echo "OBS_TEST_ENDPOINT=https://obs.eu-west-101.myhuaweicloud.eu"
    echo "OBS_TEST_ACCESS_KEY=${HUAWEI_ACCESS_KEY}"
    echo "OBS_TEST_SECRET_KEY=${HUAWEI_SECRET_ACCESS_KEY}"
    echo "OBS_TEST_AUTH_TYPE=OBS"
    echo "OBS_TEST_PATH_STYLE=false"
    # Pre-created shared bucket: the IAM user relies on it, so CreateBucket/DeleteBucket tests
    # self-skip and the remaining tests rely on per-test prefix isolation.
    echo "OBS_TEST_BUCKET=kestra-unit-test"
  } >> "$GITHUB_ENV"
else
  echo "No Huawei Cloud credentials — falling back to local MinIO for OBS integration tests."
  docker compose -f docker-compose-ci.yml up -d minio
  echo "OBS_MINIO_TESTS=true" >> "$GITHUB_ENV"
fi

# Helper: wait for a Docker Compose service to reach the "healthy" state.
# Usage: wait_healthy <service> <retries> <sleep_seconds>
wait_healthy() {
  local service="$1"
  local retries="${2:-30}"
  local delay="${3:-5}"
  local i=0
  while [ "$i" -lt "$retries" ]; do
    local status
    status=$(docker inspect --format='{{.State.Health.Status}}' \
      "$(docker compose -f docker-compose-ci.yml ps -q "$service")" 2>/dev/null || true)
    if [ "$status" = "healthy" ]; then
      return 0
    fi
    i=$((i + 1))
    echo "Waiting for $service to be healthy... ($i/$retries, current: ${status:-starting})"
    sleep "$delay"
  done
  return 1
}

# DMS for Kafka — use the local Kafka container from docker-compose-ci.yml.
# docker compose up is allowed to fail (bad image, no disk space, etc.) — we just skip.
echo "Starting Kafka container for DMS Kafka integration tests..."
if docker compose -f docker-compose-ci.yml up -d kafka 2>&1; then
  if wait_healthy kafka 36 5; then
    echo "Kafka is healthy — enabling DMS Kafka integration tests."
    {
      echo "DMS_KAFKA_TESTS=true"
      echo "DMS_KAFKA_BOOTSTRAP_SERVERS=localhost:9092"
    } >> "$GITHUB_ENV"
  else
    echo "Kafka did not become healthy — DMS Kafka tests will be skipped."
  fi
else
  echo "Kafka container failed to start — DMS Kafka tests will be skipped."
fi

# DMS for RocketMQ — use the local RocketMQ containers from docker-compose-ci.yml.
echo "Starting RocketMQ containers for DMS RocketMQ integration tests..."
if docker compose -f docker-compose-ci.yml up -d rocketmq-namesrv 2>&1; then
  if wait_healthy rocketmq-namesrv 24 5; then
    echo "RocketMQ namesrv is healthy — starting broker..."
    if docker compose -f docker-compose-ci.yml up -d rocketmq-broker 2>&1; then
      if wait_healthy rocketmq-broker 24 5; then
        echo "RocketMQ broker is healthy — enabling DMS RocketMQ integration tests."
        {
          echo "DMS_ROCKETMQ_TESTS=true"
          echo "DMS_ROCKETMQ_NAME_SERVER=localhost:9876"
        } >> "$GITHUB_ENV"
      else
        echo "RocketMQ broker did not become healthy — DMS RocketMQ tests will be skipped."
      fi
    else
      echo "RocketMQ broker container failed to start — DMS RocketMQ tests will be skipped."
    fi
  else
    echo "RocketMQ namesrv did not become healthy — DMS RocketMQ tests will be skipped."
  fi
else
  echo "RocketMQ namesrv container failed to start — DMS RocketMQ tests will be skipped."
fi
