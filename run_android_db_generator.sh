#!/bin/bash -xe

# This script runs Android's database-generator against webapp
# as an integration test, in order to verify that the API responses
# are compatible with the mobile clients.

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd -P )"
source "${SCRIPT_DIR}/build.lib"

safe_sync_to_origin "git@github.com:Khan/android" "master"

# TODO(csilvers): figure out the module by parsing dispatch.yaml,
#                 rather than hard-coding frontend-highmem.
export API_BASE_URL="https://${VERSION}-dot-frontend-highmem-dot-khan-academy.appspot.com"
if android/make-dbs.sh; then
    echo "RUN_ANDROID_DB_GENERATOR PASSED"
else
    echo "RUN_ANDROID_DB_GENERATOR FAILED"
    exit 1
fi
