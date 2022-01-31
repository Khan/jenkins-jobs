#!/bin/bash -ex

# A script to build an up-to-date current.sqlite by using various tools to seed
# our dev environment state.

# Specifically those tools are:
# services/users/cmd/make_dev_users - go script to create dev user accounts
# services/admin/cmd/make_admin - go script to make 1 user an admin
# dev/dev_appserver/sync_snapshot.py - python script to copy content snapshots from prod to the dev environment
# dev/dev_appserver/create_user_interactions.py - python script to create user comments and whatnot to simulate some use
# services/progress/cmd/watch-videos - go script to create some video watching data
#
# This script should be run from workspace-root.
#
# For this script to work, secrets.py must be on the PYTHONPATH.

# Space-separated list of GCS buckets to download snapshots from.
: ${SNAPSHOT_NAMES:="snapshot_en snapshot_es"}
# GCS bucket to upload current.sqlite to.
: ${CURRENT_SQLITE_BUCKET:="gs://ka_dev_sync/"}

# Fetch sync snapshots from google storage
for snapshot_bucket in $SNAPSHOT_NAMES; do
    gsutil cp "gs://ka_dev_sync/$snapshot_bucket" "./$snapshot_bucket"
done

# remove any existing database
rm -f current.sqlite
# And clean up existing memcache prefill files
rm -rf webapp/genfiles/content_prefill

# make sure our dependencies are installed and up to date
cd webapp
make deps

# start dev server serving locally
# go services assume graphql works on 8080 in dev
# write logs to genfiles/appserver.log
timeout 10h python third_party/frankenserver/dev_appserver.py \
    --application=khan-academy \
    --datastore_path=../current.sqlite \
    --port=8080 \
    --require_indexes=yes \
    --skip_sdk_update_check=yes \
    --automatic_restart=no \
    --admin_port=0 \
    --max_module_instances=1 \
    --log_level=info \
    --host=127.0.0.1 \
    --enable_datastore_translator=yes \
    $DEV_APPSERVER_ARGS \
    ./app.yaml > genfiles/appserver.log 2>&1 &
appserver_pid=$!

# if we close this script early, dump the last part of the appserver logs to the console
trap 'tail -n100 genfiles/appserver.log' 0 HUP INT QUIT


# setup redis server.
redis-server --port 8202 --dir genfiles &
redis_pid=$!

# hard to detect what's required or not, so just maintain a hard coded list
# This list is ordered! some of these services are early in the list because other
# services depend on them to start.For example: grpc-translator and graphql-gateway
required_services="grpc-translator localproxy graphql-gateway admin analytics assignments campaigns coaches content content-editing content-library discussions districts donations emails progress rest-gateway rewards search test-prep users"

# We also need to start the go services
service_pids=
for d in $required_services; do
    # We do the "sed" so we can tell what service a given piece of output
    # is coming from (since they're all interleaved).  We use the weird
    # `>(...)` syntax so "$!" still points to the "make" command, not "sed":
    # https://stackoverflow.com/questions/1652680/how-to-get-the-pid-of-a-process-that-is-piped-to-another-process-in-bash
   make -C "services/$d" serve < /dev/null > >(sed "s/^/[$d] /") 2>&1 &
   service_pids="$service_pids $!"
   sleep 5  # some services need to come up first, so we wait for them
done

# It can take graphql-gateway more than a minute to start up, so let's
# give it some time before we start throwing traffic at it
sleep 60

# create all the dev users and make test admin an admin user
go run ./services/users/cmd/create_dev_users/
go run ./services/admin/cmd/make_admin --username=testadmin

for snapshot_bucket in $SNAPSHOT_NAMES; do
    # do content sync
    locale_name=`echo "$snapshot_bucket" | awk -F"_" '{print $NF}'`
    tools/devshell.py --host localhost:8080  --script dev/dev_appserver/sync_snapshot.py "../$snapshot_bucket" "$locale_name"
    sleep 10
done

# create interaction data for the users, and have testcoach watch some videos
tools/devshell.py --host localhost:8080  --script dev/dev_appserver/create_user_interactions.py
kaid=$(go run ./services/users/cmd/get-kaid-for-username testcoach)
go run ./services/progress/cmd/watch-videos/ $kaid

# stop the go services. We don't do try too hard because these generally shut down cleanly.
kill $service_pids

# try to stop dev server
kill -15 "$appserver_pid"

# try to stop redis
kill $redis_pid

sleep 10

# If it's still here, let it know it really needs to stop
if ps -p "$appserver_pid" > /dev/null; then
    kill -15 "$appserver_pid"
    sleep 10
fi

# Ok, that's enough of that. It really really needs to stop
if ps -p "$appserver_pid" > /dev/null; then
    kill -9 "$appserver_pid"
    sleep 10
fi

# make sure dev-server's subprocesses are stopped too.
# 8011 is the pubsub emulator; 8081 is the nginx proxy.
lsof -t -iTCP:8011 -iTCP:8081 -iTCP:8080 | xargs -r kill -15
sleep 10
lsof -t -iTCP:8011 -iTCP:8081  -iTCP:8080 | xargs -r kill -9

trap - 0 HUP INT QUIT

# wait some extra time to make sure everything has actually shut down and
# fully written current.sqlite to disk
sleep 30

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
