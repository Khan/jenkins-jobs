#!/bin/bash -xe


SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd -P )"
WORKSPACE_ROOT="$(pwd -P)"
source "${SCRIPT_DIR}/build.lib"
ensure_virtualenv
decrypt_secrets_py_and_add_to_pythonpath

( cd webapp && make python_deps )

# This lets us commit messages without a test plan
export FORCE_COMMIT=1

# Keep track of which scripts failed after making partial progress.
# It's a string that keeps growing
error=""

echo "Checking status of dropbox holding historical data"

# dropbox.py doesn't like it when the directory is a symlink
DATA_DIR=`readlink -f /mnt/dropbox/Dropbox/webapp-i18n-data`

# Start dropbox service if it is not running
! HOME=/mnt/dropbox dropbox.py running || HOME=/mnt/dropbox dropbox.py start

prof_incoming="$DATA_DIR/captions/professional_incoming/"
incoming="$DATA_DIR/captions/incoming/"
published="$DATA_DIR/captions/published/"
published_prod="$DATA_DIR/captions/published_prod/"
video_list_path="$DATA_DIR/captions/video_list.txt"

tools="`pwd`/webapp/tools"
# Needed to get appengine_tool_setup.py
PYTHONPATH="$tools:$PYTHONPATH"

# Download a list of videos that exist in production
"$tools/get_video_list.py" > "$video_list_path"

busy_wait_on_dropbox "$DATA_DIR/captions/"

# Flow is something like:
# hired             YouTube FanCaptions
# transcribers      |
# | [dss]           | [kt]
# v                 v
# prof_incoming --> incoming ----> published ----> published_prod
#               ^[move]       ^[kt]           ^[uptp]
#
# [dss]: tools/dropbox_sync_source.py
# [move]: moving professional captions to incoming
# [kt]: tools/khantube.py
# [uptp]: tools/upload_captions_to_production.py
#
# [dss] has a metadate file /captions/dropbox_info.json
# For the purposes of SKIP_TO_STAGE
# [dss] = 0
# [move] = 1
# (obsolete, now a no-op) tools/amara_exporter.py = 2
# [kt] = 3
# [uptp] = 4
echo "Starting at stage: ${SKIP_TO_STAGE:=0}"  # Set to 0 if not set

version_data="$DATA_DIR/captions/version_data.json"


# --- The actual work:
cd "$DATA_DIR"

if [ "$SKIP_TO_STAGE" -le 0 ]; then
    echo "Pulling from dropbox"
    # TODO(james): Share professional captions folder with jenkins account and
    # delete this script.
    # If it exits with nonzero code, stop the script, fix it.
    # There is no meaningful partial progress.
    "$tools/dropbox_sync_source.py" "$DATA_DIR/captions"
fi

if [ "$SKIP_TO_STAGE" -le 1 ]; then
    echo "Looking for professional captions"
    if [ -d "$prof_incoming" ] && \
       [ -n "$(find "$prof_incoming" -type f | head -n 1)" ]
    then
        # Note that `[ -n "" ]` returns false, so there's at least one caption
        # to process and `|head -n 1` prints the first line and closes,
        # stopping the search early.
        echo "Moving them into incoming"
        for locale in $(ls "$prof_incoming") ; do
            mkdir -p "$prof_incoming/$locale"
            for file in $(ls "$prof_incoming/$locale"); do
                mv "$prof_incoming/$locale/$file" "$incoming/$locale/"
            done;
        done;
    else
        echo "None found"
    fi
fi

if [ "$SKIP_TO_STAGE" -le 2 ]; then
    echo "Skipping download from Amara (Amara is no longer used)"
fi

if [ "$SKIP_TO_STAGE" -le 3 ]; then
    echo "Uploading to Youtube"

    stats_file=/var/tmp/khantube_stats.txt
    if "$tools/khantube.py" "$incoming" "$published" \
        --youtube-ids-file="$video_list_path" \
        --data-file="$version_data" \
        --english-caption-dir="$published_prod" \
        --stats-file="$stats_file";
    then
        echo "Competed upload to youtube"
    else
        echo "FAILED: There were some problems uploading to youtube"
        error+="Error uploading to youtube\n"
    fi
    cat "$stats_file"
fi

if [ "$SKIP_TO_STAGE" -le 4 ]; then
    mkdir -p "$published_prod"
    stats_file=/var/tmp/upload_to_prod_stats.txt
    if "$tools/upload_captions_to_production.py" \
        "$published" "$published_prod" 'https://www.khanacademy.org'\
        --stats-file="$stats_file" \
        --youtube-ids-file="$video_list_path";
    then
        echo "Completed upload to prod"
    else
        echo "FAILED: Something went wrong when uploading to production"
        error+="Error uploading captions to production\n"
    fi
    cat "$stats_file"
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
