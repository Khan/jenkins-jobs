#!/bin/sh -xe

# A script to run topic tree json generation used for api responses.
# This must be run from workspace-root.
# For this script to work, secrets.py must be on the PYTHONPATH.

cd webapp

if [ ! -z "$1" ]
then
    tools/devshell.py --prod --script tools/generate_topictree_json.py $1
else
    tools/devshell.py --prod --script tools/list_test_or_better_locales.py |
        while IFS= read -r line
        do
            if [ ${#line} -ge 3 ]; then echo "Ignore this output line"
            else
            echo "$line"
            tools/devshell.py --prod --script tools/generate_topictree_json.py $line
            fi
        done

fi
