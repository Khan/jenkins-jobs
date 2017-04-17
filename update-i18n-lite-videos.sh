#!/bin/sh -xe

# This script is run by the jenkins 'update-i18n-lite-videos' in order to
# query youtube to get the videos information from our i18n lite youtube
# channels and add them to intl/translations/videos_*.json
#
# This script must be run from workspace-root.

echo "Updating the webapp repo."
# We do our work in the 'translations' branch.
jenkins-tools/build.lib safe_pull_in_branch webapp translations
# ...which we want to make sure is up-to-date with master.
jenkins-tools/build.lib safe_merge_from_master webapp translations
# We also make sure the intl/translations sub-repo is up to date.
jenkins-tools/build.lib safe_pull webapp/intl/translations

(
    cd webapp
    make python_deps

    echo "Updating the list of videos we have for 'lite' languages."
    tools/update_i18n_lite_videos.py intl/translations
)

# This lets us commit messages without a test plan
export FORCE_COMMIT=1

echo "Checking in new video-lists."
jenkins-tools/build.lib safe_commit_and_push webapp/intl/translations \
   -m "Automatic update of video_*.json" \
   -m "(at webapp commit `cd webapp && git rev-parse HEAD`)"
