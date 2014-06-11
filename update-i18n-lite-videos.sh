#!/bin/bash -xe

# This script is run by the jenkins 'update-i18n-lite-videos' in order to 
# query youtube to get the videos information from our i18n lite youtube 
# channels and add them to intl/translations/videos_*.json

WORKSPACE_ROOT=`pwd -P`
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd -P )"

source "${SCRIPT_DIR}/build.lib"

ensure_virtualenv
( cd "$WEBSITE_ROOT" && "$MAKE" install_deps )

decrypt_secrets_py_and_add_to_pythonpath

cd "$WEBSITE_ROOT"

# --- The actual work:

# This lets us commit messages without a test plan
export FORCE_COMMIT=1

echo "Updating intl/translations."
safe_pull intl/translations

echo "Updating the list of videos we have for 'lite' languages."
tools/import_translations.py intl/translations

echo "Checking in new video-lists."
safe_commit_and_push intl/translations \
   -m "Automatic update of video_*.json" \
   -m "(at webapp commit `git rev-parse HEAD`)"


