#!/bin/sh -ex

# A script to build a virtualenv
#
# This script should be run from workspace-root.
#
# For this script to work, secrets.py must be on the PYTHONPATH.

# remove any existing database
rm -f current.sqlite

cd webapp
make deps

tools/devshell.py --prod --script tools/run_translation_analytics_pipeline.py "$locale"