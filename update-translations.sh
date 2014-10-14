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

echo "Updating the repo storing historical data (old translations repo)"
DATA_REPO=git@github.com:Khan/webapp-i18n-data
DATA_REPO_DIR=`pwd`/webapp-i18n-data
[ -d "$DATA_REPO_DIR" ] && safe_pull "$DATA_REPO_DIR" \
   || git clone "$DATA_REPO" "$DATA_REPO_DIR"
mkdir -p "$DATA_REPO_DIR"/upload_to_crowdin
mkdir -p "$DATA_REPO_DIR"/download_from_crowdin

cd "$WEBSITE_ROOT"

echo "Updating the webapp repo."
safe_pull .
# We also make sure the translations sub-repo is up to date.
safe_pull intl/translations

for lang in `tools/list_candidate_active_languages.py` ; do
    echo "Downloading the current translations for $lang from crowdin."
    deploy/download_i18n.py -v -s "$DATA_REPO_DIR"/download_from_crowdin/ \
       --lint_log_file "$DATA_REPO_DIR"/download_from_crowdin/"$lang"_lint.pickle \
       --use_temps_for_linting \
       --english-version-dir="$DATA_REPO_DIR"/upload_to_crowdin \
       --crowdin-data-filename="$DATA_REPO_DIR"/crowdin_data.pickle \
       --send-lint-reports \
       --export \
       $lang
done

echo "Creating a new, up-to-date all.pot."
# Both handlebars.babel and shared_jinja.babel look for popular_urls in /tmp,
# but we also want to keep a version in source control for debugging purposes.
# TODO(csilvers): uncomment once we get popular_pages up and using bigquery.
#tools/popular_pages.py --limit 10000 > "$DATA_REPO_DIR"/popular_urls
cp -f "$DATA_REPO_DIR"/popular_urls /tmp/
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

# github has a limit of 100M per file.  We split up the .po files to
# stay under the limit.  For consistency with files that don't need
# to be split up at all (and to make all_locales_for_mo() happy), we
# don't give the first chunk an extension but do for subsequent chunks.
# (Same as how /var/log/syslog works.)
for p in intl/translations/pofiles/*; do
    split --suffix-length=1 --line-bytes=95M --numeric-suffixes "$p" "$p."
    mv -f "$p.0" "$p"
done

echo "Done creating .po files:"
ls -l intl/translations/pofiles/

echo "Checking it into crowdin repo"
# Store a compressed version of all.po (github thinks the raw version
# is too big.)
gzip -9 < "$ALL_POT" > "$DATA_REPO_DIR"/all.pot.gz
safe_commit_and_push "$DATA_REPO_DIR" \
   -m "Automatic update of all.pot and download_from_crowdin/" \
   -m "(at webapp commit `git rev-parse HEAD`)"

echo "Uploading the new all.pot to crowdin."
deploy/upload_i18n.py -v --save-temps="$DATA_REPO_DIR"/upload_to_crowdin/ \
   --crowdin-data-filename="$DATA_REPO_DIR"/crowdin_data.pickle \
   --popular-urls="$DATA_REPO_DIR"/popular_urls \
   --pot-filename="$ALL_POT"

echo "Downloading the new en-PT jipt tags from crowdin for translate.ka.org."
deploy/download_i18n.py -v -s "$DATA_REPO_DIR"/download_from_crowdin/ \
    --english-version-dir="$DATA_REPO_DIR"/upload_to_crowdin \
    --crowdin-data-filename="$DATA_REPO_DIR"/crowdin_data.pickle \
    --export \
    --nolint \
    en-PT

echo "Checking in any newly added strings to crowdin_data.pickle."
safe_commit_and_push "$DATA_REPO_DIR" \
   -m "Automatic update of crowdin_data.pickle and upload_to_crowdin/" \
   -m "(at webapp commit `git rev-parse HEAD`)"

echo "DONE with update-translations.sh"
echo "Don't forget to run checkin-update-translations.sh to check this all in!"
