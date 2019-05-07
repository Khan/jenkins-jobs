#!/bin/sh -ex

# A script to run the Translation Analytics to update the Dashboard.
#
# This script should be run from workspace-root.
#
# For this script to work, secrets.py must be on the PYTHONPATH.

cd webapp

# We need one file that is generated at build time. We can't run with
# USE_PROD_FILES disabled as we prefer to get the most recent translation files
# from GCS, so just build the one file manually to start with.

export USE_PROD_FILES=1

tools/devshell.py --prod --log_level INFO --script tools/run_translation_analytics.py --locale "$1" --content_locale "$2" --use_staged_content "$3"
