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
# OBS's S3-compatible API validates the SigV4 region in the request; give the CLI the bucket's
# region explicitly so it doesn't default to us-east-1 and get a SignatureDoesNotMatch.
OBS_REGION="eu-west-101"

docker compose -f docker-compose-ci.yml down -v || true

if [ -n "${HUAWEI_ACCESS_KEY:-}" ] && [ -n "${HUAWEI_SECRET_ACCESS_KEY:-}" ] && [ -n "${GITHUB_RUN_ID:-}" ]; then
  RUN_PREFIX="it/${GITHUB_RUN_ID}/"
  export AWS_ACCESS_KEY_ID="${HUAWEI_ACCESS_KEY}"
  export AWS_SECRET_ACCESS_KEY="${HUAWEI_SECRET_ACCESS_KEY}"
  export AWS_DEFAULT_REGION="${OBS_REGION}"

  echo "OBS cleanup: aws $(aws --version 2>&1)"
  echo "OBS cleanup: objects under s3://${OBS_BUCKET}/${RUN_PREFIX} before delete:"
  aws s3 ls "s3://${OBS_BUCKET}/${RUN_PREFIX}" --recursive --endpoint-url "${OBS_ENDPOINT}" || true

  echo "OBS cleanup: deleting s3://${OBS_BUCKET}/${RUN_PREFIX} ..."
  aws s3 rm "s3://${OBS_BUCKET}/${RUN_PREFIX}" --recursive --endpoint-url "${OBS_ENDPOINT}"
  echo "OBS cleanup: aws s3 rm exit code = $?"

  echo "OBS cleanup: objects remaining under s3://${OBS_BUCKET}/${RUN_PREFIX} after delete:"
  aws s3 ls "s3://${OBS_BUCKET}/${RUN_PREFIX}" --recursive --endpoint-url "${OBS_ENDPOINT}" || true
fi
