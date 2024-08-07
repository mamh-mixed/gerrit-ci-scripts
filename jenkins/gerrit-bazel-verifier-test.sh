#!/bin/bash -ex

case $TARGET_BRANCH in
  master|stable-3.10|stable-3.9)
    . set-java.sh 17
    ;;

  *)
    . set-java.sh 11
    ;;
esac

cd gerrit

echo "Test with mode=$MODE"
echo '----------------------------------------------'

case $TARGET_BRANCH$MODE in
  masterrbe|stable-3.8rbe|stable-3.9rbe|stable-3.10rbe)
    TEST_TAG_FILTER="-flaky,-elastic,-no_rbe"
    BAZEL_OPTS="$BAZEL_OPTS --config=remote_bb --jobs=50 --remote_header=x-buildbuddy-api-key=$BB_API_KEY"
    ;;
  masternotedb|stable-3.8notedb|stable-3.9notedb|stable-3.10notedb)
    TEST_TAG_FILTER="-flaky,elastic,no_rbe"
    ;;
  stable-2.*)
    TEST_TAG_FILTER="-flaky,-elastic"
    ;;
  *)
    TEST_TAG_FILTER="-flaky"
esac

export BAZEL_OPTS="$BAZEL_OPTS \
                 --flaky_test_attempts 3 \
                 --test_timeout 3600 \
                 --test_tag_filters=$TEST_TAG_FILTER"
export WCT_HEADLESS_MODE=1

java -fullversion
bazelisk version

if [[ "$MODE" == *"notedb"* ]]
then
  bazelisk test $BAZEL_OPTS //...
fi

if [[ "$MODE" == *"rbe"* ]]
then
  bazelisk test $BAZEL_OPTS //...
fi

if [[ "$MODE" == *"polygerrit"* ]]
then

  echo 'Running Documentation tests...'
  bazelisk test $BAZEL_OPTS //tools/bzl:always_pass_test Documentation/...

  echo "Running local tests in $(google-chrome --version)"
  bash ./polygerrit-ui/app/run_test.sh || touch ~/polygerrit-failed
fi
