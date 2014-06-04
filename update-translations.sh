#!/bin/bash -xe

# This script is run by the jenkins 'update-translations' job, to
# 1) download up-to-date translations from crowdin
# 2) sanity-check the results and check them in
# 3) upload the latest all.pot to crowdin


WORKSPACE_ROOT=`pwd -P`
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd -P )"

source "${SCRIPT_DIR}/build.lib"

ensure_virtualenv
( cd "$WEBSITE_ROOT" && "$MAKE" install_deps )

decrypt_secrets_py_and_add_to_pythonpath


# --- The actual work:

# This lets us commit messages without a test plan
export FORCE_COMMIT=1

echo "Updating the repo holding historical data (old translations repo)"
REPO=ssh://khanacademy@khanacademy.kilnhg.com/Website/translations/webapp
[ -d translations ] && GIT_TRACE=1 safe_pull translations || git clone "$REPO" translations
mkdir -p translations/upload_to_crowdin
mkdir -p translations/download_from_crowdin

CROWDIN_REPO=`pwd`/translations

cd "$WEBSITE_ROOT"

echo "Updating the webapp repo."
safe_pull .
# We also make sure the translations sub-repos are up to date.
GIT_TRACE=1 safe_pull "$CROWDIN_REPO"
safe_pull intl/translations

echo "Downloading the current translations from crowdin."
deploy/download_i18n.py -v -s "$CROWDIN_REPO"/download_from_crowdin/ \
   --send-lint-reports \
   --export \
   --crowdin-data-filename="$CROWDIN_REPO"/crowdin_data.pickle

# download_i18n.py downloads all.zip in download_from_crowdin/.  Unzip
# it so we can check in the actual files.
# TODO(csilvers): once kiln doesn't die on all these files, do this, not the cp
#unzip -o -d "$CROWDIN_REPO"/download_from_crowdin \
#            "$CROWDIN_REPO"/download_from_crowdin/all.zip
for i in `ls "$CROWDIN_REPO"/download_from_crowdin/*.zip` ; do
    cp "$i" "$TMPDIR"/`basename "$i"`.`date +%Y-%m-%d`
    rm "$i"
done

echo "Creating a new, up-to-date all.pot."
# Both handlebars.babel and shared_jinja.babel look for popular_urls in /tmp,
# but we also want to keep a version in source control for debugging purposes.
# TODO(csilvers): uncomment once we get popular_pages up and using bigquery.
#tools/popular_pages.py --limit 10000 > "$CROWDIN_REPO"/popular_urls
cp -f "$CROWDIN_REPO"/popular_urls /tmp/
# By removing genfiles/extracted_strings/en/intl/datastore.pot.pickle,
# we force compile_all_pot to re-fetch nltext datastore info from prod.
rm -f genfiles/extracted_strings/en/intl/datastore.pot.pickle
kake/build_prod_main.py -v3 pot
cp -vf genfiles/translations/all.pot.txt_for_debugging \
       "$CROWDIN_REPO"/all.pot

echo "Sanity check: will fail if the new all.pot is missing stuff."
[ `wc -l < "$CROWDIN_REPO"/all.pot` -gt 100000 ]
grep -q 'intl/datastore:1' "$CROWDIN_REPO"/all.pot

echo "Translating fake languages."
"$MAKE" i18n_mo
echo "Done creating .po files:"
ls -l intl/translations/pofiles/

echo "Checking it all in!"
safe_commit_and_push intl/translations \
   -m "Automatic update of crowdin .po files" \
   -m "(at webapp commit `git rev-parse HEAD`)"

GIT_TRACE=1 safe_commit_and_push "$CROWDIN_REPO" \
   -m "Automatic update of all.pot and download_from_crowdin/" \
   -m "(at webapp commit `git rev-parse HEAD`)"

echo "Uploading the new all.pot to crowdin."
deploy/upload_i18n.py -v --save-temps="$CROWDIN_REPO"/upload_to_crowdin/ \
   --popular-urls="$CROWDIN_REPO"/popular_urls \
   --crowdin-data-filename="$CROWDIN_REPO"/crowdin_data.pickle \
   --pot-filename="$CROWDIN_REPO"/all.pot

echo "Downloading the new en-PT jipt tags from crowdin for translate.ka.org."
deploy/download_i18n.py -v -s "$CROWDIN_REPO"/download_from_crowdin/ \
    --langs_to_download=en-PT \
    --export \
    --nolint \
    --crowdin-data-filename="$CROWDIN_REPO"/crowdin_data.pickle

# TODO(csilvers): once kiln doesn't die on all these files, do this, not the cp
#unzip -o -d "$CROWDIN_REPO"/download_from_crowdin \
#            "$CROWDIN_REPO"/download_from_crowdin/en-PT.zip
cp "$CROWDIN_REPO"/download_from_crowdin/en-PT.zip \
    "$TMPDIR"/en-PT.zip.`date +%Y-%m-%d`
rm "$CROWDIN_REPO"/download_from_crowdin/en-PT.zip

echo "Checking in any newly added strings to crowdin_data.pickle."
safe_commit_and_push "$CROWDIN_REPO" \
   -m "Automatic update of crowdin_data.pickle and upload_to_crowdin/" \
   -m "(at webapp commit `git rev-parse HEAD`)"

echo "Checking in crowdin_stringids.pickle and en-PT.po"
safe_commit_and_push intl/translations \
   -m "Automatic update of crowdin_stringids.pickle" \
   -m "(at webapp commit `git rev-parse HEAD`)"

echo "DONE"
