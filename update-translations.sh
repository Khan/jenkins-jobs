#!/bin/bash -xe

# This script is run by the jenkins 'update-translations' job, to
# 1) download up-to-date translations from crowdin
# 2) sanity-check the results and check them in
# 3) upload the latest all.pot to crowdin
#
# Environment variables:
#
# One of the following must be set for this script to do any work:
#
#   DOWNLOAD_TRANSLATIONS - set to 1 to download language translations,
#       which is (1) and (2) above.
#   UPDATE_STRINGS - set to 1 to upload new strings to Crowdin,
#       regenerating all.pot, fake languages, and JIPT strings,
#       which is (3) above.
#
# These environment variables are optional:
#
#   NUM_LANGS_TO_DOWNLOAD - update at most this many languages. The
#       default is to process all languages that have updates.

: ${DOWNLOAD_TRANSLATIONS:=}
: ${UPDATE_STRINGS:=}
: ${NUM_LANGS_TO_DOWNLOAD:=1000}

if [ -z "$DOWNLOAD_TRANSLATIONS" -a -z "$UPDATE_STRINGS" ]; then
    echo "One of DOWNLOAD_TRANSLATIONS or UPDATE_STRINGS must be set" >&2
    exit 1
fi


SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd -P )"
WORKSPACE_ROOT=`pwd -P`
source "${SCRIPT_DIR}/build.lib"
ensure_virtualenv
decrypt_secrets_py_and_add_to_pythonpath

( cd "$WEBSITE_ROOT" && "$MAKE" install_deps )


# After downloading a lang.po file from crowdin, splits it up like we want.
# $1: the directory the contains the unsplit po file.
# We split up the file in this way so compile_small_mo kake rule can run more 
# quickly.
split_po() {
    # Just look at the lang.po files, ignoring lang.rest.po/etc.
    langs=`ls -1 "$1" | sed -n 's/^\([^.]*\)\.po$/\1/p'`
    for lang in $langs; do
        # Remove the old .rest.po and .datastore.po files.
        rm -f "$1/$lang.rest.po"
        rm -f "$1/$lang.datastore.po"

        # Split the po-file into datastore only strings and all other strings.
        tools/split_po_files.py "$1/$lang.po"
    done

    echo "Done creating .po files:"
    ls -l "$1"
}


# --- The actual work:

echo "Checking status of dropbox"

# dropbox.py doesn't like it when the directory is a symlink
DATA_DIR=`readlink -f /mnt/dropbox/Dropbox/webapp-i18n-data`

# Start dropbox service if it is not running
! HOME=/mnt/dropbox dropbox.py running || HOME=/mnt/dropbox dropbox.py start

busy_wait_on_dropbox "$DATA_DIR"/upload_to_crowdin
busy_wait_on_dropbox "$DATA_DIR"/download_from_crowdin
busy_wait_on_dropbox "$DATA_DIR"/crowdin_data.pickle

echo "Dropbox folders are ready and fully synched"

cd "$WEBSITE_ROOT"

echo "Updating the webapp repo."
safe_pull .
# We also make sure the translations sub-repo is up to date.
safe_pull intl/translations

TRANSLATIONS_DIR="$WEBSITE_ROOT"/intl/translations/pofiles
APPROVED_TRANSLATIONS_DIR="$WEBSITE_ROOT"/intl/translations/approved_pofiles

# Locales whose .po files have been updated from running this script
# are listed here, one per line. This is used by the Jenkins job to
# determine which languages need to be uploaded to production.
UPDATED_LOCALES_FILE="$WORKSPACE_ROOT"/updated_locales.txt

if [ -n "$DOWNLOAD_TRANSLATIONS" ]; then

    # Download the approved entries.
    for lang in `deploy/order_download_i18n.py --verbose --approved-only | head -n "$NUM_LANGS_TO_DOWNLOAD"` ; do
        echo "Downloading the approved translations for $lang from crowdin."
        deploy/download_i18n.py -v -s "$DATA_DIR"/download_from_crowdin/ \
           --lint_log_file "$DATA_DIR"/download_from_crowdin/"$lang"_lint.pickle \
           --use_temps_for_linting \
           --english-version-dir="$DATA_DIR"/upload_to_crowdin \
           --crowdin-data-filename="$DATA_DIR"/crowdin_data.pickle \
           --send-lint-reports \
           --export \
           --approved-only \
           $lang
    done

    # Now download entries regardless as to whether they have been approved.
    for lang in `deploy/order_download_i18n.py --verbose | head -n "$NUM_LANGS_TO_DOWNLOAD"` ; do
        echo "Downloading the current translations for $lang from crowdin."
        deploy/download_i18n.py -v -s "$DATA_DIR"/download_from_crowdin/ \
           --lint_log_file "$DATA_DIR"/download_from_crowdin/"$lang"_lint.pickle \
           --use_temps_for_linting \
           --english-version-dir="$DATA_DIR"/upload_to_crowdin \
           --crowdin-data-filename="$DATA_DIR"/crowdin_data.pickle \
           --send-lint-reports \
           --export \
           $lang
    done

    echo "Splitting .po files"
    split_po "$TRANSLATIONS_DIR"

    echo "Splitting approved .po files"
    split_po "$APPROVED_TRANSLATIONS_DIR"

fi

if [ -n "$UPDATE_STRINGS" ]; then

    echo "Updating the list of graphie images."
    # find_graphie_images_in_items.js caches items here, so we create the directory
    # for it.
    mkdir -p genfiles/assessment_items
    node tools/find_graphie_images_in_items.js

    echo "Creating a new, up-to-date all.pot."
    # Both handlebars.babel and shared_jinja.babel look for popular_urls in /tmp,
    # but we also want to keep a version in source control for debugging purposes.
    # TODO(csilvers): uncomment once we get popular_pages up and using bigquery.
    #tools/popular_pages.py --limit 10000 > "$DATA_DIR"/popular_urls
    cp -f "$DATA_DIR"/popular_urls /tmp/
    # By removing genfiles/extracted_strings/en/intl/datastore.pot.pickle,
    # we force compile_all_pot to re-fetch nltext datastore info from prod.
    rm -f genfiles/extracted_strings/en/intl/datastore.pot.pickle
    kake/build_prod_main.py -v3 pot
    # This is where build_prod_main.py puts the output all.pot
    ALL_POT="$PWD"/genfiles/translations/all.pot.txt_for_debugging

    echo "Sanity check: will fail if the new all.pot is missing stuff."
    [ `wc -l < "$ALL_POT"` -gt 100000 ]
    grep -q 'intl/datastore:1' "$ALL_POT"

    echo "Translating fake languages."
    "$MAKE" i18n_mo

    cp "$ALL_POT" "$DATA_DIR"/all.pot

    echo "Uploading the new all.pot to crowdin."
    deploy/upload_i18n.py -v --save-temps="$DATA_DIR"/upload_to_crowdin/ \
       --use-temps-to-skip \
       --crowdin-data-filename="$DATA_DIR"/crowdin_data.pickle \
       --popular-urls="$DATA_DIR"/popular_urls \
       --pot-filename="$ALL_POT" \
       --automatically_delete_html_files

    echo "Downloading the new en-PT jipt tags from crowdin for translate.ka.org."
    deploy/download_i18n.py -v -s "$DATA_DIR"/download_from_crowdin/ \
        --english-version-dir="$DATA_DIR"/upload_to_crowdin \
        --crowdin-data-filename="$DATA_DIR"/crowdin_data.pickle \
        --export \
        --nolint \
        en-pt

    # Split up en-PT and the fake languages as well.  The other langs will be
    # ignored since they have already been split up.
    split_po "$TRANSLATIONS_DIR"
    split_po "$APPROVED_TRANSLATIONS_DIR"
    # We don't bother redownloading en-pt for approved as it is a fake language and
    # so approval doesn't make sense.  So we just copy the po files on over.
    cp "$TRANSLATIONS_DIR"/en-pt\.* "$APPROVED_TRANSLATIONS_DIR"

fi

(
    # Let's determine which locales have updated .po files. We use
    # `git add` so that untracked files will list as 'A' in `git
    # status`. Then, we convert the output of `git status` for
    # modified and added files to one locale per line.
    #
    #   M  approved_pofiles/bn.datastore.po
    #   A  pofiles/ck.rest.po
    #
    # becomes
    #
    #   bn
    #   ck
    cd intl/translations
    timeout 10m git add pofiles approved_pofiles
    git status --porcelain \
        | grep -e '^M' -e '^A' \
        | grep --only-matching '\(approved_\)\{0,1\}pofiles/[^.]\{1,\}' \
        | xargs basename \
        | sort -u \
      >"$UPDATED_LOCALES_FILE"
)

# e.g., "de fr zh-hans" for the commit message.
updated_locales=`<"$UPDATED_LOCALES_FILE" tr -s '\n\t ' ' ' | sed 's/ $//'`

# This lets us commit messages without a test plan
export FORCE_COMMIT=1

echo "Checking in crowdin_stringids.pickle and [approved_]pofiles/*.po"
safe_commit_and_push intl/translations \
   -m "Automatic update of crowdin .po files and crowdin_stringids.pickle" \
   -m "(locales: $updated_locales)" \
   -m "(at webapp commit `git rev-parse HEAD`)"

echo "DONE"
