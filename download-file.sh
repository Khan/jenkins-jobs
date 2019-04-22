#!/bin/bash -xe

gsutil cp "gs://ka_translations/raw_TESTING/$1_archive/$2.tar.gz" .
echo $1
echo $2
echo "Copied GCS files to current  directory"
sleep 200
echo "I slept"
echo "now awake"

