#!/bin/sh -ex

# A script to run the Translation Analytics to update the Dashboard.
#
# This script should be run from workspace-root.
#
# For this script to work, secrets.py must be on the PYTHONPATH.

# remove any existing database
rm -f current.sqlite

cd webapp
make deps
make current.sqlite

tools/devshell.py --prod --script tools/run_translation_analytics.py "$locale"