#!/bin/sh -e

# Run several e2e tests in parallel on the same machine.
#
# The arguments to this script are a list of numbers, which
# are indices into input and output files.  For instance,
# if the numbers are `2 3 8`, we run 3 e2e tests in parallel.
# The first run gets the list of tests to run from
# ./e2e_splits.2.txt, and emits its pickle-output to
# ./e2e-test-results.2.pickle.
#
# If the FAILFAST environment variable is set to `true`, we
# attempt to abort as soon as we notice a test failure.
# TODO(csilvers): rewrite in python/etc so we can do better waiting.
#
# You should run this from workspace-root, with webapp as a subdir.


if [ -z "$URL" ]; then
    echo "You must specify a URL environment variable."
    exit 1
fi

if [ "$FAILFAST" = "true" ]; then
    FAILFAST_FLAG="--failfast"
fi

# This is apparently needed to avoid hanging with the chrome driver.
# See https://github.com/SeleniumHQ/docker-selenium/issues/87
export DBUS_SESSION_BUS_ADDRESS=/dev/null

cd webapp

pids=
for i in "$@"; do
    timeout -k 5m 5h xvfb-run -a tools/rune2etests.py \
        --xml --pickle --pickle-file="../e2e-test-results.$i.pickle" \
        --quiet --jobs=1 --retries 3  $FAILFAST_FLAG \
        --url "$URL" --driver=chrome --backup-driver=sauce \
        - < "../e2e_splits.$i.txt"
    pids="$pids $!"
done

for pid in $pids; do
    # This will fail with an exit code if the rune2etests.py command failed.
    wait "$pid"
done
