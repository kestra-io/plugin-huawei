#!/usr/bin/env bash
set -uo pipefail

# Teardown mirror of setup-unit.sh. CI Docker services are always torn down. When the tests ran
# against the shared real OBS bucket, we also delete THIS run's objects: the tests namespace every
# key under it/<GITHUB_RUN_ID>/... (see AbstractObsTest#runPrefix), so removing that single prefix
# reclaims everything this run created without touching a concurrent run's in-flight objects.
# This is a backstop for the in-JVM @AfterAll sweep, which is skipped if the JVM or the Gradle
# step dies before it runs. The bucket's 1-day object-expiry lifecycle rule is the final backstop.

# eu-west-101 is Huawei's EU cloud — endpoints live under .myhuaweicloud.eu, not .com.
OBS_ENDPOINT="https://obs.eu-west-101.myhuaweicloud.eu"
OBS_BUCKET="kestra-unit-test"
# OBS's S3-compatible API validates the SigV4 region in the request. Without this the aws CLI signs
# for its default (us-east-1), OBS rejects it with SignatureDoesNotMatch, and — because the error is
# swallowed below — the delete becomes a silent no-op that leaves every object behind. Setting the
# bucket's real region is what makes this script actually delete anything.
OBS_REGION="eu-west-101"

docker compose -f docker-compose-ci.yml down -v || true

if [ -n "${HUAWEI_ACCESS_KEY:-}" ] && [ -n "${HUAWEI_SECRET_ACCESS_KEY:-}" ] && [ -n "${GITHUB_RUN_ID:-}" ]; then
  RUN_PREFIX="it/${GITHUB_RUN_ID}/"
  echo "Deleting this run's OBS objects under s3://${OBS_BUCKET}/${RUN_PREFIX} ..."
  # OBS exposes an S3-compatible API; the preinstalled aws CLI works against the OBS endpoint.
  # A cleanup failure must never fail the build (the step already did its job), so we warn instead
  # of erroring — but we warn loudly rather than swallowing silently, so a broken teardown is visible.
  AWS_ACCESS_KEY_ID="${HUAWEI_ACCESS_KEY}" \
  AWS_SECRET_ACCESS_KEY="${HUAWEI_SECRET_ACCESS_KEY}" \
  AWS_DEFAULT_REGION="${OBS_REGION}" \
    aws s3 rm "s3://${OBS_BUCKET}/${RUN_PREFIX}" \
      --recursive \
      --endpoint-url "${OBS_ENDPOINT}" \
      || echo "WARNING: OBS cleanup failed; objects may remain under ${RUN_PREFIX} (bucket lifecycle rule is the backstop)."
fi
