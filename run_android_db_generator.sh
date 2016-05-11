#!/bin/bash -xe

# This script runs Android's database-generator against webapp
# as an integration test, in order to verify that the API responses
# are compatible with the mobile clients.

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd -P )"
source "${SCRIPT_DIR}/build.lib"

safe_sync_to_origin "git@github.com:Khan/android" "master"

export API_BASE_URL="https://${VERSION}-dot-khan-academy.appspot.com"
android/make-dbs.sh
