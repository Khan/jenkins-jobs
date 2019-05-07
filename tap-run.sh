#!/bin/sh -ex

# A script to run the Translation Analytics to update the Dashboard.
#
# This script should be run from workspace-root.
#
# For this script to work, secrets.py must be on the PYTHONPATH.

# Fail the job on error
set -e

# remove any existing database
rm -f current.sqlite

cd webapp
make deps
make current.sqlite

# We need one file that is generated at build time. We can't run with
# USE_PROD_FILES disabled as we prefer to get the most recent translation files
# from GCS, so just build the one file manually to start with.
build/kake/build_prod_main.py genfiles/combined_template_strings/combined_template_strings.json
export USE_PROD_FILES=1

tools/devshell.py --prod --log_level INFO --script tools/run_translation_analytics.py "$1"
