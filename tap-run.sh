#!/bin/sh -ex

# A script to run the Translation Analytics to update the Dashboard.
#
# This script should be run from workspace-root.
#
# For this script to work, secrets.py must be on the PYTHONPATH.
# Fail the job on error
set -e

cd webapp

export USE_PROD_FILES=1

tools/devshell.py --prod --log_level INFO --script tools/run_translation_analytics.py --locale "$1" --content_locale "$2" --use_staged_content "$3"
