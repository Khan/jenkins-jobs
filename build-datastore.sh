#!/bin/bash -xe

# This script generates a current.sqlite that is up-to-date
# with the live site and suitable for use with a dev appserver.
# It is indented to be run by the continuous integration server
# from the root of a workspace where the website code is checked
# out into a subdirectory.

# In order to actually upload the output current.sqlite file to
# Dropbox, we need a secrets file with the API token. For
# information on the contents of this file and how to regenerate
# the token, see:
# Dropbox/Khan Academy All Staff/Secrets/password-for-jenkins-ka.org-and-dropbox.txt

# Configuration options for building the datastore.

# The file containing the password to decrypt secrets.py.
: ${SECRETS_PW_FILE:="$HOME"/secrets_py/secrets.py.cast5.password}

# The file containing the Dropbox API token for uploading current.sqlite.
: ${DROPBOX_SECRETS_FILE:="$HOME"/secrets_py/dropbox-secret.py}

# If this flag is set to 1, DO NOT reuse the cached content from the last run (takes longer)
: ${IGNORE_CACHED_CONTENT:=0}


SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd -P )"
source "${SCRIPT_DIR}/build.lib"
ensure_virtualenv

if [ ! -e $DROPBOX_SECRETS_FILE ]; then
    echo "Could not find Dropbox secrets file in $DROPBOX_SECRETS_FILE! Aborting."
    exit 1
fi

( cd "$WEBSITE_ROOT" && "$MAKE" install_deps )

# Decrypt secrets file
pushd "$WEBSITE_ROOT"
openssl cast5-cbc -d -in secrets.py.cast5 -out secrets.py -kfile "$SECRETS_PW_FILE"
chmod 600 secrets.py
popd

DATASTORE_DIR=`mktemp -d`
DATASTORE_PATH="$DATASTORE_DIR/current.sqlite"
SERVER_PORT=8900

function start_appserver {
    # Start a dev appserver in the background
    echo "Starting AppEngine dev appserver..."
    dev_appserver.py --require_indexes=true --skip_sdk_update_check=yes --datastore_path=$DATASTORE_PATH --blobstore_path=$DATASTORE_DIR/blobs --port $SERVER_PORT --admin_port 9000 $WEBSITE_ROOT & PID=$!

    # Give the appserver time to initialize before polling
    sleep 10

    # Wait up to 30 more seconds for a running appserver
    x=1
    started=0
    while [ $x -le 30 ]
    do
        x=$(( $x + 1 ))

        echo "Polling for running appserver..."
        curl -f -s "http://localhost:$SERVER_PORT/robots.txt" > /dev/null && started=1 && break

        sleep 1
    done

    if [ $started -eq 0 ]; then
        echo "Could not start dev appserver! Aborting."
        kill $PID || true
        exit 1
    fi
}

function kill_appserver {
    # Kill the appserver
    kill $PID || true

    # Give the datastore time to write everything to disk, then signal it a few
    # more times to make sure it's closed.
    sleep 15
    kill $PID || true
    kill $PID || true
}

if [ "$IGNORE_CACHED_CONTENT" != "1" ]; then
    # Restore the current-content.sqlite from the last run if it exists
    if [ -e ./current-content.sqlite ]; then
        cp ./current-content.sqlite $DATASTORE_PATH
    fi
fi

# Start up dev appsever the first time to sync the content
start_appserver

# Sync the content with live (won't fetch revisions we already have)
if ! curl -f -s "http://localhost:$SERVER_PORT/api/v1/dev/initialize_datastore/content"; then
    echo "Failed to start content sync process!"
    kill_appserver
    exit 1
fi

# Wait for the editing HEAD revision to be set, signaling that the sync has completed
while :
do
    status=`curl -f -s "http://localhost:$SERVER_PORT/api/v1/dev/datastore_initialized/content"`
    echo "Polling on completion: $status"
    if [ "$status" = '"YES"' ]; then
        break
    fi
    if [ "$status" != '"NO"' ]; then
        # There was an error during sync
        kill_appserver
        exit 1
    fi

    sleep 60
done

echo "Content initialization is complete."
kill_appserver

# Copy the current.sqlite out of the temporary directory
cp $DATASTORE_PATH ./current-content.sqlite

# Uncomment LAYER_CACHE_ALLOW_DATASTORE='1' in app.yaml
pushd "$WEBSITE_ROOT"
sed -i -e "s/^#\(  LAYER_CACHE_ALLOW_DATASTORE: '1'\)$/\1/g" app.yaml
popd

# Start the dev appsever a second time to publish the content
start_appserver

# Publish the content for the first time
if ! curl -f -s "http://localhost:$SERVER_PORT/api/v1/dev/initialize_datastore/publish"; then
    echo "Failed to start publish process!"
    kill_appserver
    exit 1
fi

# Wait for the published version to change, signaling that publish has completed
while :
do
    status=`curl -f -s "http://localhost:$SERVER_PORT/api/v1/dev/datastore_initialized/publish"`
    echo "Polling on completion: $status"
    if [ "$status" = '"YES"' ]; then
        break
    fi
    if [ "$status" != '"NO"' ]; then
        # There was an error during publish
        kill_appserver
        exit 1
    fi

    sleep 60
done

echo "Content publish is complete."
kill_appserver

# Revert the LAYER_CACHE_ALLOW_DATASTORE change in app.yaml
pushd "$WEBSITE_ROOT"
git reset --hard HEAD
popd

# Copy the current.sqlite out of the temporary directory
cp $DATASTORE_PATH ./current.sqlite

# Delete the temporary datastore directory
rm -rf $DATASTORE_DIR

# Upload current.sqlite to Dropbox
echo "Uploading current.sqlite to Dropbox..."
pip install dropbox
python "$SCRIPT_DIR/dropbox-upload.py" "$DROPBOX_SECRETS_FILE" ./current.sqlite "/Khan Academy All Staff/Other shared items/datastores/current.sqlite"

echo "Finished successfully!"
