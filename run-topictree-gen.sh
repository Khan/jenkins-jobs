#!/bin/sh -xe

# A script to run topic tree json generation used for api responses.
# This must be run from workspace-root.
# For this script to work, secrets.py must be on the PYTHONPATH.

cd webapp

tools/devshell.py --prod --script tools/generate_topictree_json.py
