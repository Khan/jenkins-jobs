#!/bin/bash -xe

# This script is run by the jenkins 'upload-translations-to-gcs' job to:
# 1) build index and chunk files
# 2) upload index and chunk files from genfiles/translations to a Google Cloud
#    Storage bucket at ka_translations
# 3) build js and css files (and other files that we serve from GCS)
# 4) Upload these static files to a Google Cloud Storage bucket at ka-static.
# 5) change the Setting `translations_version`, which controls which set of
#    translations the webapp will load from Google Cloud Storage
# 6) change the Setting `js_css_md5sum`, which controls the set of js/css
#    files that webapp will serve from Google Cloud Storage

# This should be a space-separated list of locales.
: ${I18N_GCS_UPLOAD_LOCALES:=}

# If the webapp repo at WEBSITE_ROOT is synced to a commit that is
# compatible with a current deployed gae version, this should be
# set to that gae version.  In that case, we'll rebuild the js/css
# files and upload them to prod, to replace the existing js/css at
# that gae version.
: ${GAE_VERSION:=}

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

make install_deps

# These kake jobs can use a lot of memory; to avoid trouble we build
# just one at a time.
for locale in $I18N_GCS_UPLOAD_LOCALES; do
    kake/build_prod_main.py -v1 $JOBS -l $locale compiled_po
done

# Convert the list of locales to '-l <locale> -l <locale> ...'
locales_for_upload="-l `echo "$I18N_GCS_UPLOAD_LOCALES" | sed "s/ / -l /g"`"
deploy/upload_gcs_i18n.py $locales_for_upload

if [ -n "$GAE_VERSION" ]; then
    # Let's build the js/css files as well, and update the Settings for them.
    # But only do this if we've updated a language that we have js/css
    # content for.
    # TODO(csilvers): update deploy_to_gcs to take a list of languages, rather
    # than all-or-nothing.
    locales_with_packages="`intl/locale_main.py | sed -ne 's/locales for packages: //p' | tr ' ' '\012' | grep -vx en`"
    locales_for_static_upload=""
    for locale in $I18N_GCS_UPLOAD_LOCALES; do
        if echo "$locales_with_packages" | grep -qx "$locale"; then
            locales_for_static_upload="$locales_for_static_upload -l $locale"
        fi
    done

    if [ -n "$locales_for_static_upload" ]; then
        deploy/deploy_to_gcs.py $JOBS "$GAE_VERSION"
    fi
fi
