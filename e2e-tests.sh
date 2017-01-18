#!/bin/bash -xe

# This script runs all the end-to-end tests in parallel.  We move all
# output to stderr so we don't have to worry about the output lines
# getting intermingled.

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd -P )"

"$SCRIPT_DIR"/android-e2e-tests.sh 1>&2 &
"$SCRIPT_DIR"/selenium-e2e-tests.sh 1>&2 &

wait
