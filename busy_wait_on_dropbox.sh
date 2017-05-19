#!/bin/sh -ex

# Wait to make sure the dropbox client has fully synced all the files.

dir="$1"
while HOME=/mnt/dropbox dropbox.py filestatus "$dir" | grep -v "up to date"; do
    echo "Waiting for $dir to be up to date"
    sleep 30
done
