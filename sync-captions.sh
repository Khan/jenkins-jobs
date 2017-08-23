#!/bin/bash

# Downloads YouTube FanCaptions and uploads them to production.
#
# This script must be called from workspace-root (i.e., a folder with both
# jenkins-tools and webapp)
#
# Data flow:
#  - Captions are created using YouTube FanCaptions.
#  - Then khantube.py puts them into /published.
#  - Finally, upload_captions_to_production uploads captions to production (as
#    advertised) and moves them to /published_prod.
#
# To run locally without uploading anything to production:
#  - clone git@github.com:Khan/webapp-i18n-data into
#    /mnt/dropbox/Dropbox/webapp-i18n-data
#  - cd into workspace-root (see above)
#  - env SKIP_DROPBOX_SYNC=1 SKIP_PROD_UPLOAD=1 ./jenkins-tools/sync-captions.sh

# Unofficial strict mode -- http://redsymbol.net/articles/unofficial-bash-strict-mode/
# Also, we set -x to echo all commands for easier debugging
set -euox pipefail
IFS=$'\n\t'

# Set defaults for optional environment variables
SKIP_DROPBOX_SYNC=${SKIP_DROPBOX_SYNC:-}
SKIP_PROD_UPLOAD=${SKIP_PROD_UPLOAD:-}
SKIP_TO_STAGE=${SKIP_TO_STAGE:=0}

( cd webapp && make python_deps )

echo "Checking status of dropbox holding historical data"

# dropbox.py doesn't like it when the directory is a symlink
# Use GNU readlink if found, since the built-in macOS one doesn't support -f
if type greadlink; then
    DATA_DIR=`greadlink -f /mnt/dropbox/Dropbox/webapp-i18n-data`
else
    DATA_DIR=`readlink -f /mnt/dropbox/Dropbox/webapp-i18n-data`
fi

# Start dropbox service if it is not running
if [[ -z "$SKIP_DROPBOX_SYNC" ]] && ! env HOME=/mnt/dropbox dropbox.py running; then
     HOME=/mnt/dropbox dropbox.py start
fi

published="$DATA_DIR/captions/published/"
published_prod="$DATA_DIR/captions/published_prod/"
video_list_path="$DATA_DIR/captions/video_list.txt"

tools="`pwd`/webapp/tools"

# Download a list of videos that exist in production
"$tools/get_video_list.py" > "$video_list_path"

if [[ -z "$SKIP_DROPBOX_SYNC" ]]; then
    jenkins-tools/busy_wait_on_dropbox.sh "$DATA_DIR/captions/"
fi

echo "Starting at stage: $SKIP_TO_STAGE"

version_data="$DATA_DIR/captions/version_data.json"

all_fancaptions_file=$(mktemp)
function cleanup {
    echo "Cleaning up..."
    # Delete our temporary file, no matter how we exit.
    rm -f "$all_fancaptions_file"
}
trap cleanup EXIT



# --- The actual work:
cd "$DATA_DIR"

if [ "$SKIP_TO_STAGE" -le 0 ]; then
    echo "Downloading captions from Youtube FanCaptions"

    stats_file=/var/tmp/khantube_stats.txt
    "$tools/khantube.py" "$published" \
        --youtube-ids-file="$video_list_path" \
        --data-file="$version_data" \
        --stats-file="$stats_file" \
        --all-fancaptions-file="$all_fancaptions_file";

    echo "Competed upload to youtube"
    cat "$stats_file"
else
    echo "WARNING: NOT FETCHING UPDATED YOUTUBE FANCAPTIONS" >&2
fi

if [ "$SKIP_TO_STAGE" -le 1 ] && [[ -z "$SKIP_PROD_UPLOAD" ]]; then
    mkdir -p "$published_prod"
    stats_file=/var/tmp/upload_to_prod_stats.txt
    "$tools/upload_captions_to_production.py" \
        "$published" "$published_prod" 'https://www.khanacademy.org'\
        --stats-file="$stats_file" \
        --youtube-ids-file="$video_list_path" \
        --all-fancaptions-file="$all_fancaptions_file";
    echo "Completed upload to prod"
    cat "$stats_file"
else
    echo "WARNING: NOT UPLOADING UPDATED CAPTIONS TO PRODUCTION" >&2
fi
