#!/bin/bash -xe

# This script is run by the jenkins 'upload-labels' job, to upload translated
# graphie-to-png label data files to S3.

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd -P )"
WORKSPACE_ROOT=`pwd -P`
source "${SCRIPT_DIR}/build.lib"
ensure_virtualenv
decrypt_secrets_py_and_add_to_pythonpath

( cd "$WEBSITE_ROOT" && "$MAKE" python_deps )


# --- The actual work:

cd "$WEBSITE_ROOT"

echo "Updating the webapp repo."
# We do our work in the 'translations' branch.
safe_pull_in_branch . translations
# ...which we want to make sure is up-to-date with master.
safe_merge_from_master . translations
# We also make sure the translations sub-repo is up to date.
safe_pull intl/translations

# upload_graphie_labels.py expects all of the labels to have already been
# translated.
languages=`intl/locale_main.py | grep "locales for packages:" | cut -d: -f2`
for language in $languages; do
    [ "$language" = "en" ] && continue
    echo "Translating graphie labels for $language."
    kake/build_prod_main.py -v1 i18n_graphie_labels --language="$language"
done

echo "Uploading labels to S3."
tools/upload_graphie_labels.py

echo "DONE"
