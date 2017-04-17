#!/bin/sh -xe

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
#
# This must be run from workspace-root.
#
# NOTE: this script requires access to secrets to run, since it sends
# to slack.

# This should be a space-separated list of locales.
: ${I18N_GCS_UPLOAD_LOCALES:=}

# If the webapp repo (in webapp/) is synced to a commit that has
# been deployed live, set this to the git-tag of that commit.
# (`gae-<version>`).  In that case, we'll rebuild the js/css
# files and upload them to prod, to replace the existing js/css at
# that gae version and static-content version.
: ${GIT_TAG:=}

# empty string means 'default number of parallel jobs', otherwise it
# should be an integer.
: ${JOBS:=}


if [ -z "$I18N_GCS_UPLOAD_LOCALES" ]; then
    echo "You must specify a value for I18N_GCS_UPLOAD_LOCALES!"
    exit 1
fi

if [ -n "$JOBS" ]; then
    JOBS="--jobs $JOBS"
fi


cd webapp

make python_deps

# These kake jobs can use a lot of memory; to avoid trouble we build
# just one at a time.
for locale in $I18N_GCS_UPLOAD_LOCALES; do
    kake/build_prod_main.py -v1 $JOBS -l $locale compiled_po
done

# Convert the list of locales to '-l <locale> -l <locale> ...'
locales_for_upload="-l `echo "$I18N_GCS_UPLOAD_LOCALES" | sed "s/ / -l /g"`"
deploy/upload_gcs_i18n.py $locales_for_upload

if [ -n "$GIT_TAG" ]; then
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

    # In a perfect world, we'd do a js-only deploy at this time to
    # deploy the new translated files in a way that we could easily
    # roll back.  But that would mean that i18n-gcs-upload could not
    # run at the same time as a deploy, which is too big a cost for
    # us.  So we just overwrite the content at the $GIT_TAG version
    # instead, with this new content which should be exactly the same
    # except maybe a bit better translated.
    #
    # If it turns out it's problematic, we can still roll back, it
    # will just be a bit further back than we'd roll back if we did a
    # real js-only deploy.
    STATIC_CONTENT_VERSION=`deploy/git_tags.py --static "$GIT_TAG"`

    if [ -n "$locales_for_static_upload" ]; then
        # We don't send to slack since there aren't really any changes
        # to report.
        deploy/deploy_to_gcs.py $JOBS --slack-channel= "$STATIC_CONTENT_VERSION"
    fi
fi
