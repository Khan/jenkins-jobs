#!/bin/bash -xe

# This script is run by the jenkins 'update-translations' job, to
# check in the latest intl/translations subrepo and update master.

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd -P )"
WORKSPACE_ROOT=`pwd -P`
source "${SCRIPT_DIR}/build.lib"
ensure_virtualenv
decrypt_secrets_py_and_add_to_pythonpath

( cd "$WEBSITE_ROOT" && "$MAKE" install_deps )

# Make sure we are in website root as make install_deps takes us out.
cd "$WEBSITE_ROOT"

# --- The actual work:

# This lets us commit messages without a test plan
export FORCE_COMMIT=1

echo "Checking in crowdin_stringids.pickle and en-PT.po"
safe_commit_and_push intl/translations \
   -m "Automatic update of crowdin .po files and crowdin_stringids.pickle" \
   -m "(at webapp commit `git rev-parse HEAD`)"

echo "DONE"
