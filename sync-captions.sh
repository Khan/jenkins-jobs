#!/bin/bash -xe


SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd -P )"
WORKSPACE_ROOT="$(pwd -P)"
source "${SCRIPT_DIR}/build.lib"
ensure_virtualenv
decrypt_secrets_py_and_add_to_pythonpath

( cd "$WEBSITE_ROOT" && "$MAKE" install_deps )

# This lets us commit messages without a test plan
export FORCE_COMMIT=1

# Keep track of which scripts failed after making partial progress.
# It's a string that keeps growing
error=""

echo "Updating the repo holding historical data (old translations repo)"
REPO=ssh://khanacademy@khanacademy.kilnhg.com/Website/translations/webapp
[ -d translations ] && GIT_TRACE=1 safe_pull translations || git clone "$REPO" translations

OLD_TRANSLATIONS_REPO="$(pwd)/translations"


prof_incoming="$OLD_TRANSLATIONS_REPO/captions/professional_incoming/"
incoming="$OLD_TRANSLATIONS_REPO/captions/incoming/"
published="$OLD_TRANSLATIONS_REPO/captions/published/"
published_prod="$OLD_TRANSLATIONS_REPO/captions/published_prod/"

# Flow is something like:
# hired             Amara
# transcribers      |
# | [dss]           | tools/amara_exporter.py
# v                 v
# prof_incoming --> incoming ----> published ----> published_prod
#                ^[hq]        ^[kt]           ^[uptp]
#
# [dss]: tools/dropbox_sync_source.py
# [hq]: tools/high_quality_captions_import.py
# [kt]: tools/khantube.py
# [uptp]: tools/upload_captions_to_production.py
#
# [dss] has a metadate file /captions/dropbox_info.json
# [hq] and amara_exporter touch $amara_progess
# For the purposes of SKIP_TO_STAGE
# [dss] = 0
# [hq] = 1
# tools/amara_exporter.py = 2
# [kt] = 3
# [uptp] = 4
echo "Starting at stage: ${SKIP_TO_STAGE:=0}"  # Set to 0 if not set

amara_progress="$OLD_TRANSLATIONS_REPO/captions/amara_progress.json"

tools="$WEBSITE_ROOT/tools"
# --- The actual work:
cd "$OLD_TRANSLATIONS_REPO"

if [ "$SKIP_TO_STAGE" -le 0 ]; then
    echo "Pulling from dropbox"
    # If it exits with nonzero code, stop the script, fix it.
    # There is no meaningful partial progress.
    "$tools/dropbox_sync_source.py" "$OLD_TRANSLATIONS_REPO/captions"

    if [ -z "$(git status --porcelain | head -n 1)" ];
    then
        echo "No new files from dropbox"
    else
        GIT_TRACE=1 safe_commit_and_push "$OLD_TRANSLATIONS_REPO" \
            -m "Automatic commit of dropbox sync" \
            -m "(at webapp commit $(git rev-parse HEAD))"
    fi
fi

if [ "$SKIP_TO_STAGE" -le 1 ]; then
    echo "Looking for high quality captions"
    if [ -d "$prof_incoming" ] && \
       [ -n "$(find $prof_incoming -type f | head -n 1)" ]
    then
        # Note that `[ -n "" ]` returns false, so there's at least one caption
        # to process and `|head -n 1` prints the first line and closes,
        # stopping the search early.
        "$tools/high_quality_captions_import.py" \
            "$prof_incoming" "$incoming" "$amara_progress"

        echo "Moving them into incoming"
        for locale in $(ls "$prof_incoming") ; do
            mkdir -p "$prof_incoming/$locale"
            for file in $(ls "$prof_incoming/$locale"); do
                mv "$prof_incoming/$locale/$file" "$incoming/$locale/"
            done;
        done;
        GIT_TRACE=1 safe_commit_and_push "$OLD_TRANSLATIONS_REPO" \
            -m "Professional captions duly noted and moved to incoming" \
            -m "(at webapp commit $(git rev-parse HEAD))"
    else
        echo "None found"
    fi
fi

if [ "$SKIP_TO_STAGE" -le 2 ]; then
    echo "Downloading from Amara"
    stats_file=/var/tmp/amara_stats.txt
    if "$tools/amara_exporter.py" \
        --youtube-ids-file=<( "$tools/get_video_list.py" ) \
        --dest-dir="$incoming" \
        --version-file="$amara_progress" \
        --stats-file="$stats_file" \
        --download-incomplete
    then
        echo "Successfully exported from Amara"
    else
        echo "FAILED: Some problems exporting from Amara"
        error+="Error exporting to Amara\n"
    fi
    if [ -z "$(git status --porcelain | head -n 1)" ];
    then
        echo "No changes at all from Amara"
    else
        # Even if it fails, it should have made some progress, save it
        # git add captions
        GIT_TRACE=1 safe_commit_and_push "$OLD_TRANSLATIONS_REPO" \
            -m "Automatic commit of Amara download" \
            -m "(at webapp commit $(git rev-parse HEAD))" \
            -m "$(cat $stats_file)"
    fi
fi

if [ "$SKIP_TO_STAGE" -le 3 ]; then
    echo "Uploading to Youtube"

    stats_file=/var/tmp/khantube_stats.txt
    if "$tools/khantube.py" "$incoming" "$published" \
        --data-file="$amara_progress" \
        --stats-file="$stats_file";
    then
        echo "Competed upload to youtube"
    else
        echo "FAILED: There were some problems uploading to youtube"
        error+="Error uploading to youtube\n"
    fi
    if [ -z "$(git status --porcelain | head -n 1)" ];
    then
        echo "No changes at all when uploading to youtube"
    else
        GIT_TRACE=1 safe_commit_and_push "$OLD_TRANSLATIONS_REPO" \
            -m "Automatic commit of Youtube upload" \
            -m "(at webapp commit $(git rev-parse HEAD))" \
            -m "$(cat $stats_file)";
    fi
fi

if [ "$SKIP_TO_STAGE" -le 4 ]; then
    mkdir -p "$published_prod"
    stats_file=/var/tmp/upload_to_prod_stats.txt
    if "$tools/upload_captions_to_production.py" \
        "$published" "$published_prod" 'https://www.khanacademy.org'\
        --stats-file="$stats_file";
    then
        echo "Completed upload to prod"
    else
        echo "FAILED: Something went wrong when uploading to production"
        error+="Error uploading captions to production\n"
    fi
    if [ -z "$(git status --porcelain | head -n 1)" ];
    then
        echo "No changes at all when uploading to production"
    else
        GIT_TRACE=1 safe_commit_and_push "$OLD_TRANSLATIONS_REPO" \
            -m "Automatic commit of upload to production" \
            -m "(at webapp commit $(git rev-parse HEAD))" \
            -m "$(cat $stats_file)";
    fi
fi

if [ -n "$error" ]; then
    echo "There were some errors"
    echo "================================================"
    echo ""
    echo -n "$error"
    echo ""
    echo "================================================"
    exit 1
fi
