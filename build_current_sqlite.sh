#!/bin/sh -ex

# A script to build an up-to-date current.sqlite by using the
# run_sync.sh tool.  We download the current datastore snapshots from
# gcs, run the tool to create a current.sqlite, and upload that to
# gcs.
#
# This script should be run from workspace-root.
#
# For this script to work, secrets.py must be on the PYTHONPATH.


# Space-separated list of GCS buckets to download snapshots from.
: ${SNAPSHOT_NAMES:="snapshot_en snapshot_es"}
# GCS bucket to upload current.sqlite to.
: ${CURRENT_SQLITE_BUCKET:="gs://ka_dev_sync/"}


# fetch sync snapshots
for snapshot_bucket in $SNAPSHOT_NAMES; do
    gsutil cp "gs://ka_dev_sync/$snapshot_bucket" "./$snapshot_bucket"
done

# remove any existing database
rm -f current.sqlite
# And clean up existing memcache prefill files
rm -rf webapp/genfiles/content_prefill

# make deps
cd webapp
make deps

is_first_run=1
for snapshot_bucket in $SNAPSHOT_NAMES; do
    # start dev server serving locally on port 9085
    # write logs to genfiles/appserver.log
    timeout 10h python third_party/frankenserver/dev_appserver.py \
        --application=khan-academy \
        --datastore_path=../current.sqlite \
        --port=9085 \
        --require_indexes=yes \
        --skip_sdk_update_check=yes \
        --automatic_restart=no \
        --admin_port=0 \
        --max_module_instances=1 \
        --log_level=info \
        --host=127.0.0.1 \
        $DEV_APPSERVER_ARGS \
        ./app.yaml > genfiles/appserver.log 2>&1 &
    appserver_pid=$!

    # wait for server to start
    sleep 60

    # do sync
    locale_name=`echo "$snapshot_bucket" | awk -F"_" '{print $NF}'`
    tools/devshell.py --host localhost:9085 \
        --script dev/dev_appserver/run_sync.py \
        ${is_first_run:+"--add-users"} \
        "../$snapshot_bucket" "$locale_name"
    sleep 10

    # stop dev server
    kill -15 "$appserver_pid"
    sleep 10
    if ps -p "$appserver_pid" > /dev/null; then
        kill -15 "$appserver_pid"
        sleep 10
    fi
    if ps -p "$appserver_pid" > /dev/null; then
        kill -9 "$appserver_pid"
        sleep 10
    fi

    # wait some extra time to make sure everything has actually shut down and
    # fully written current.sqlite to disk
    sleep 30
    is_first_run=
done

cd ..
# upload current.sqlite and new content prefill files (deleteing old ones)
gsutil cp current.sqlite "$CURRENT_SQLITE_BUCKET/current.sqlite"
gsutil -m rsync -d webapp/genfiles/content_prefill/ "$CURRENT_SQLITE_BUCKET/content_prefill/"

# cleanup
rm -f current.sqlite
rm -rf webapp/genfiles/content_prefill
for snapshot_bucket in $SNAPSHOT_NAMES; do
    rm -f "$snapshot_bucket"
done
