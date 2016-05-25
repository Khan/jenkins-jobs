#!/bin/bash -xe

# This script is run by the jenkins 'upload-translations-to-gcs' job to:
# 1) build index and chunk files
# 2) upload index and chunk files from genfiles/translations to a Google Cloud
#    Storage bucket at ka_translations
# 3) change the setting `translations_version`, which controls which set of
#    translations the webapp will load from Google Cloud Storage

# empty string means 'all locales', and so does the special string "all";
# otherwise it should be a space-separated list.
: ${I18N_GCS_UPLOAD_LOCALES:=}

# empty string means 'default number of parallel jobs', otherwise it
# should be an integer.
: ${JOBS:=}

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd -P )"
WORKSPACE_ROOT=`pwd -P`
source "${SCRIPT_DIR}/build.lib"
ensure_virtualenv
decrypt_secrets_py_and_add_to_pythonpath


if [ -z "$I18N_GCS_UPLOAD_LOCALES" -o "$I18N_GCS_UPLOAD_LOCALES" = "all" ]; then
    locales_for_build="all-with-data"
    locales_for_upload=""
else
    locales_for_build="$I18N_GCS_UPLOAD_LOCALES"
    # Convert the list of locales to '-l <locale> -l <locale> ...'
    locales_for_upload="-l `echo "$I18N_GCS_UPLOAD_LOCALES" | sed "s/ / -l /g"`"
fi

if [ -n "$JOBS" ]; then
    JOBS="--jobs $JOBS"
fi


cd "$WEBSITE_ROOT"

# These kake jobs can use a lot of memory; to avoid trouble we build
# just one at a time.
for locale in $locales_for_build; do
    kake/build_prod_main.py -v1 $JOBS -l $locale compiled_po
done
deploy/upload_gcs_i18n.py $locales_for_upload
