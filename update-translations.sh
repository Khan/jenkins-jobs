#!/bin/bash -xe

# This script is run by the jenkins 'update-translations' job, to
# 1) download up-to-date translations from crowdin
# 2) sanity-check the results and check them in
# 3) upload the latest all.pot to crowdin


SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd -P )"
WORKSPACE_ROOT=`pwd -P`
source "${SCRIPT_DIR}/build.lib"
ensure_virtualenv
decrypt_secrets_py_and_add_to_pythonpath

( cd "$WEBSITE_ROOT" && "$MAKE" install_deps )


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

for lang in `tools/list_candidate_active_languages.py` ; do
    echo "Downloading the current translations for $lang from crowdin."
    deploy/download_i18n.py -v -s "$CROWDIN_REPO"/download_from_crowdin/ \
       --send-lint-reports \
       --export \
       --crowdin-data-filename="$CROWDIN_REPO"/crowdin_data.pickle \
       $lang
done

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
# We put a smaller version of all.pot in the intl/translations repo.
# We take out the comments-for-translators, which are like 70% of
# the text, but leave in the strings and the location info.
# Actually, we *do* need to leave in a subset of comments, though:
# those that say '(format: xxx)' -- fake_translate uses those.
grep -v '^#\. [^(]' genfiles/translations/all.pot.txt_for_debugging \
    > intl/translations/all.pot

echo "Sanity check: will fail if the new all.pot is missing stuff."
[ `wc -l < "$CROWDIN_REPO"/all.pot` -gt 100000 ]
grep -q 'intl/datastore:1' "$CROWDIN_REPO"/all.pot

echo "Done creating .po files:"
ls -l intl/translations/pofiles/

echo "Checking it into crowdin repo"
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
    --export \
    --nolint \
    --crowdin-data-filename="$CROWDIN_REPO"/crowdin_data.pickle \
    en-PT

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

echo "DONE with update-translations.sh"
echo "Don't forget to run checkin-update-translations.sh to check this all in!"
