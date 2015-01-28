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

echo "Checking status of dropbox holding historical data"

# dropbox.py doesn't like it when the directory is a symlink
DATA_DIR=`readlink -f /mnt/dropbox/Dropbox/webapp-i18n-data`

# Start dropbox service if it is not running
! HOME=/mnt/dropbox dropbox.py running || HOME=/mnt/dropbox dropbox.py start

prof_incoming="$DATA_DIR/captions/professional_incoming/"
incoming="$DATA_DIR/captions/incoming/"
published="$DATA_DIR/captions/published/"
published_prod="$DATA_DIR/captions/published_prod/"

busy_wait_on_dropbox "$DATA_DIR/captions/"

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

amara_progress="$DATA_DIR/captions/amara_progress.json"

tools="$WEBSITE_ROOT/tools"
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
    echo "Looking for high quality captions"
    if [ -d "$prof_incoming" ] && \
       [ -n "$(find "$prof_incoming" -type f | head -n 1)" ]
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
        error+="Error exporting from Amara\n"
    fi
    cat "$stats_file"
fi

if [ "$SKIP_TO_STAGE" -le 3 ]; then
    echo "Uploading to Youtube"

    stats_file=/var/tmp/khantube_stats.txt
    if "$tools/khantube.py" "$incoming" "$published" \
        --data-file="$amara_progress" \
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
        --stats-file="$stats_file";
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
