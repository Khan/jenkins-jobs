#!/bin/bash -xe

# This script runs all the end-to-end tests in parallel.  We move all
# output to stderr so we don't have to worry about the output lines
# getting intermingled.

./android-e2e-tests.sh 1>&2 &
./selenium-e2e-tests.sh 1>&2 &

wait
