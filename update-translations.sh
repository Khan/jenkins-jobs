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
#
#   OVERRIDE_LANGS - a whitespace-separated list of languages to
#       process, e.g., "fr es pt". Ignored unless
#       DOWNLOAD_TRANSLATIONS is also set. When this is set,
#       NUM_LANGS_TO_DOWNLOAD is ignored.

: ${DOWNLOAD_TRANSLATIONS:=}
: ${UPDATE_STRINGS:=}
: ${NUM_LANGS_TO_DOWNLOAD:=1000}
: ${OVERRIDE_LANGS:=}

if [ -z "$DOWNLOAD_TRANSLATIONS" -a -z "$UPDATE_STRINGS" ]; then
    echo "One of DOWNLOAD_TRANSLATIONS or UPDATE_STRINGS must be set" >&2
    exit 1
fi


( cd webapp && make install_deps )


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

jenkins-jobs/busy_wait_on_dropbox.sh "$DATA_DIR"/upload_to_crowdin
jenkins-jobs/busy_wait_on_dropbox.sh "$DATA_DIR"/download_from_crowdin
jenkins-jobs/busy_wait_on_dropbox.sh "$DATA_DIR"/crowdin_data.pickle

echo "Dropbox folders are ready and fully synched"

echo "Updating the webapp repo."
# We do our work in the 'translations' branch.
jenkins-jobs/safe_git.sh pull_in_branch webapp translations
# ...which we want to make sure is up-to-date with master.
jenkins-jobs/safe_git.sh merge_from_master webapp translations
# We also make sure the intl/translations sub-repo is up to date.
jenkins-jobs/safe_git.sh pull webapp/intl/translations

TRANSLATIONS_DIR=`pwd`/webapp/intl/translations/pofiles
APPROVED_TRANSLATIONS_DIR=`pwd`/webapp/intl/translations/approved_pofiles

# Locales whose .po files have been updated from running this script
# are listed here, one per line. This is used by the Jenkins job to
# determine which languages need to be uploaded to production.
UPDATED_LOCALES_FILE=`pwd`/updated_locales.txt

cd webapp

if [ -n "$DOWNLOAD_TRANSLATIONS" ]; then

    if [ -n "$OVERRIDE_LANGS" ]; then
        list_of_langs="$OVERRIDE_LANGS"
        # Even when we override the langs, we still call the ordering script as
        # it also updates the first time we have seen any changed string counts
        # so we know how long a language has been waiting to be updated
        # correctly.
        deploy/order_download_i18n.py
    else
        # Download the next NUM_LANGS_TO_DOWNLOAD most important langs
        # pofiles and stats files in parallel and create the combined
        # intl/translations/pofiles and intl/translations/approved_pofiles
        list_of_langs=`deploy/order_download_i18n.py --verbose | head -n "$NUM_LANGS_TO_DOWNLOAD"`
    fi

    # xargs -n1 takes a string and puts each word on its own line.
    for lang in `echo "$list_of_langs" | xargs -n1`; do
        echo "Downloading translations and stats for $lang from crowdin & making combined pofile."
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

    echo "Downloading and extracting sync snapshot"
    # find_graphie_images_in_items.js needs this snapshot of article content in
    # order to extract images from articles.
    gsutil cp gs://ka_dev_sync/snapshot_en snapshot_en
    tools/extract_lintable_content.py \
        --articles \
        --input snapshot_en \
        --output article_content.zip

    echo "Updating the list of graphie images."
    # find_graphie_images_in_items.js caches items here, so we create the directory
    # for it.
    mkdir -p genfiles/assessment_items
    dev/tools/run_js_in_node.js content_editing/tools/find_graphie_images_in_items.js

    echo "Creating a new, up-to-date all.pot."
    # Both handlebars.babel and shared_jinja.babel look for popular_urls in /tmp,
    # but we also want to keep a version in source control for debugging purposes.
    # TODO(csilvers): uncomment once we get popular_pages up and using bigquery.
    #tools/popular_pages.py --limit 10000 > "$DATA_DIR"/popular_urls
    cp -f "$DATA_DIR"/popular_urls /tmp/
    # By removing genfiles/extracted_strings/en/intl/datastore.pot.pickle,
    # we force compile_all_pot to re-fetch nltext datastore info from prod.
    rm -f genfiles/extracted_strings/en/intl/datastore.pot.pickle
    build/kake/build_prod_main.py -v3 pot
    # This is where build_prod_main.py puts the output all.pot
    ALL_POT="$PWD"/genfiles/translations/all.pot.pickle

    echo "Sanity check: will fail if the new all.pot is missing stuff."
    [ `strings "$ALL_POT" | wc -l` -gt 3000000 ]
    strings "$ALL_POT" | grep -q 'intl/datastore'

    # Update export timestamps for fake languages.
    mark_fake_langs=`cat <<PYCOMMAND
from deploy import download_i18n
download_i18n.mark_strings_export('accents')
download_i18n.mark_strings_export('boxes')
PYCOMMAND
`
    python -c "$mark_fake_langs"

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
        | grep -e '^\s*M' -e '^\s*A' \
        | grep --only-matching -e 'approved_pofiles/[^.]*' -e 'pofiles/[^.]*' \
        | xargs -n1 basename \
        | sort -u \
      >"$UPDATED_LOCALES_FILE"
)

# e.g., "de fr zh-hans" for the commit message.
# xargs with no args just converts newlines to spaces.
updated_locales=`xargs <"$UPDATED_LOCALES_FILE"`

# This lets us commit messages without a test plan
export FORCE_COMMIT=1
cd ..         # get back to workspace-root.

echo "Checking in crowdin_stringids.pickle and [approved_]pofiles/*.po"
( cd webapp/intl/translations && git add . )
jenkins-jobs/safe_git.sh commit_and_push_submodule \
    webapp intl/translations \
    -a \
    -m "Automatic update of crowdin .po files and crowdin_stringids.pickle" \
    -m "(locales: $updated_locales)" \
    -m "(at webapp commit `cd webapp && git rev-parse HEAD`)"

echo "DONE"
