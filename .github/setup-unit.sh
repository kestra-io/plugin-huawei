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
  docker compose -f docker-compose-ci.yml up -d
  echo "OBS_MINIO_TESTS=true" >> "$GITHUB_ENV"
fi
