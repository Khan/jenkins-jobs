#!/bin/sh -xe

# A script to run topic tree json generation used for api responses.
# This must be run from workspace-root.
# For this script to work, secrets.py must be on the PYTHONPATH.

cd webapp

if [ ! -z "$1" ]
then
    flag="false"
    echo "flag = $flag"
    tools/devshell.py --prod --script tools/generate_topictree_json.py $1
else
    flag="true"
fi

if [ $flag="true" ]
then
    tools/devshell.py --prod --script tools/list_test_or_better_locales.py
    filename="listfile.txt"
    while read -r line; do
        echo "Processing locale = $line"
        tools/devshell.py --prod --script tools/generate_topictree_json.py $line
    done < "$filename"
fi
