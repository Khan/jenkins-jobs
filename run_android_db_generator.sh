#!/bin/sh -xe

# This script runs Android's database-generator against webapp
# as an integration test, in order to verify that the API responses
# are compatible with the mobile clients.
#
# This must be run in the workspace-root directory (not webapp/).

jenkins-jobs/safe_git.sh sync_to "git@github.com:Khan/mobile" "master"

# We want to make sure the request goes to the frontend-highmem module.
# TODO(csilvers): figure out the module by parsing dispatch.yaml,
#                 rather than hard-coding frontend-highmem.
if [ "$URL" = "https://www.khanacademy.org" ]; then
    export API_BASE_URL="https://frontend-highmem-dot-khan-academy.appspot.com"
else
    # We hope it's a appspot.com url!
    # TODO(csilvers): what to do about static-only deploys, which are at
    # static-XXX.ka.org?  There's no way to point at appspot.com with a
    # working url. :-(
    export API_BASE_URL=`echo "$URL" | sed s/-dot-khan-academy.appspot.com/-dot-frontend-highmem-dot-khan-academy.appspot.com/`
fi
if mobile/android/make-dbs.sh; then
    echo "RUN_ANDROID_DB_GENERATOR PASSED"
else
    echo "RUN_ANDROID_DB_GENERATOR FAILED"
    exit 1
fi
