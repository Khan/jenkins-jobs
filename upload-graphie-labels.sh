#!/bin/bash -xe

# This script is run by the jenkins 'upload-labels' job, to upload translated
# graphie-to-png label data files to S3.

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd -P )"
WORKSPACE_ROOT=`pwd -P`
source "${SCRIPT_DIR}/build.lib"
ensure_virtualenv
decrypt_secrets_py_and_add_to_pythonpath

( cd "$WEBSITE_ROOT" && "$MAKE" install_deps )


# --- The actual work:

cd "$WEBSITE_ROOT"

echo "Updating the webapp repo."
safe_pull .
# We also make sure the translations sub-repo is up to date.
safe_pull intl/translations

echo "Translating graphie labels."
# upload_graphie_labels.py expects all of the labels to have already been
# translated.
kake/build_prod_main.py -v3 i18n_graphie_labels --language=all

echo "Uploading labels to S3."
tools/upload_graphie_labels.py

echo "DONE"
