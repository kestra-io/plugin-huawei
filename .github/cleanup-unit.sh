#!/usr/bin/env bash
set -uo pipefail

# Mirror of setup-unit.sh: all CI Docker services are always torn down; on post-merge runs we also
# empty the shared OBS bucket. Best-effort — the bucket's 1-day object-expiry lifecycle rule is the
# backstop for anything this misses (e.g. when a failed Gradle step skips this cleanup entirely).

# eu-west-101 is Huawei's EU cloud — endpoints live under .myhuaweicloud.eu, not .com.
OBS_ENDPOINT="https://obs.eu-west-101.myhuaweicloud.eu"
OBS_BUCKET="kestra-unit-test"

docker compose -f docker-compose-ci.yml down -v || true

case "${GITHUB_EVENT_NAME:-}" in
  pull_request|"")
    ;;
  *)
    if [ -n "${HUAWEI_ACCESS_KEY:-}" ] && [ -n "${HUAWEI_SECRET_ACCESS_KEY:-}" ]; then
      echo "Emptying OBS shared bucket s3://${OBS_BUCKET} ..."
      # OBS exposes an S3-compatible API; the preinstalled aws CLI works against the OBS endpoint.
      AWS_ACCESS_KEY_ID="${HUAWEI_ACCESS_KEY}" \
      AWS_SECRET_ACCESS_KEY="${HUAWEI_SECRET_ACCESS_KEY}" \
        aws s3 rm "s3://${OBS_BUCKET}" \
          --recursive \
          --endpoint-url "${OBS_ENDPOINT}" || true
    fi
    ;;
esac
