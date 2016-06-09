#!/bin/bash -xe

# This script is run by the jenkins 'upload-translations-to-gcs' job to:
# 1) build index and chunk files
# 2) upload index and chunk files from genfiles/translations to a Google Cloud
#    Storage bucket at ka_translations
# 3) change the setting `translations_version`, which controls which set of
#    translations the webapp will load from Google Cloud Storage

# This should be a space-separated list of locales.
: ${I18N_GCS_UPLOAD_LOCALES:=}

# empty string means 'default number of parallel jobs', otherwise it
# should be an integer.
: ${JOBS:=}

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd -P )"
WORKSPACE_ROOT=`pwd -P`
source "${SCRIPT_DIR}/build.lib"
ensure_virtualenv
decrypt_secrets_py_and_add_to_pythonpath


if [ -z "$I18N_GCS_UPLOAD_LOCALES" ]; then
    echo "You must specify a value for I18N_GCS_UPLOAD_LOCALES!"
    exit 1
fi

if [ -n "$JOBS" ]; then
    JOBS="--jobs $JOBS"
fi


cd "$WEBSITE_ROOT"

# These kake jobs can use a lot of memory; to avoid trouble we build
# just one at a time.
for locale in $I18N_GCS_UPLOAD_LOCALES; do
    kake/build_prod_main.py -v1 $JOBS -l $locale compiled_po
done

# Convert the list of locales to '-l <locale> -l <locale> ...'
locales_for_upload="-l `echo "$I18N_GCS_UPLOAD_LOCALES" | sed "s/ / -l /g"`"
deploy/upload_gcs_i18n.py $locales_for_upload
