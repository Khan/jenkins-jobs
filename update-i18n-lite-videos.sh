#!/bin/bash -xe

# This script is run by the jenkins 'update-i18n-lite-videos' in order to
# query youtube to get the videos information from our i18n lite youtube
# channels and add them to intl/translations/videos_*.json

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd -P )"
WORKSPACE_ROOT=`pwd -P`
source "${SCRIPT_DIR}/build.lib"
ensure_virtualenv
decrypt_secrets_py_and_add_to_pythonpath

cd "$WEBSITE_ROOT"

echo "Updating the webapp repo."
# We do our work in the 'translations' branch.
safe_pull_in_branch . translations
# ...which we want to make sure is up-to-date with master.
safe_merge_from_master . translations
# We also make sure the intl/translations sub-repo is up to date.
safe_pull intl/translations

"$MAKE" install_deps

# --- The actual work:

# This lets us commit messages without a test plan
export FORCE_COMMIT=1

echo "Updating the list of videos we have for 'lite' languages."
tools/update_i18n_lite_videos.py intl/translations

echo "Checking in new video-lists."
safe_commit_and_push intl/translations \
   -m "Automatic update of video_*.json" \
   -m "(at webapp commit `git rev-parse HEAD`)"
