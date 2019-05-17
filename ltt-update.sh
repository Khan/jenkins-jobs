#!/bin/sh -ex

# A script to run the "LTT Update" job.
#
# This script should be run from workspace-root.
#
# For this script to work, secrets.py must be on the PYTHONPATH.
# Fail the job on error
cd webapp

export USE_PROD_FILES=1

tools/devshell.py --prod --log_level INFO --script tools/run_ltt_update.py --locale "$1"

