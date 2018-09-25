#!/bin/bash -xe

# This script is run by the jenkins 'i18n-create-all-pot-pickle' job

( cd webapp && make install_deps )

DATA_DIR=`readlink -f /mnt/webapp-i18n-data`

echo "Updating the webapp repo."
# We do our work in the 'automated-commits' branch.
jenkins-jobs/safe_git.sh pull_in_branch webapp automated-commits
# ...which we want to make sure is up-to-date with master.
jenkins-jobs/safe_git.sh merge_from_master webapp automated-commits
# We also make sure the intl/translations sub-repo is up to date.
jenkins-jobs/safe_git.sh pull webapp/intl/translations

cd webapp

echo "Starting update_strings job at $(date +%H:%M:%S)"

# find_graphie_images_in_items.js needs this snapshot of article content in
# order to extract images from articles.
echo "Starting extracting sync snapshot at $(date +%H%M)"
gsutil cp gs://ka_dev_sync/snapshot_en snapshot_en
tools/extract_lintable_content.py \
    --articles \
    --input snapshot_en \
    --output article_content.zip
echo "Ending extracting sync snapshot at $(date +%H:%M:%S)"

# find_graphie_images_in_items.js caches items here, so we create the directory
# for it.
echo "Starting updating the list of graphie images at $(date +%H:%M:%S)"
mkdir -p genfiles/assessment_items
dev/tools/run_js_in_node.js content_editing/tools/find_graphie_images_in_items.js
echo "Ending updating the list of graphie images at $(date +%H%M)"

echo "Starting to create a new, up-to-date all.pot at $(date +%H:%M:%S)"
rm -f genfiles/extracted_strings/en/intl/datastore.pot.pickle
echo "Starting build_prod_main $(date +%H:%M:%S)"
build/kake/build_prod_main.py -v3 new_pot
# This is where build_prod_main.py puts the output all.pot
echo "Ending build_prod_main $(date +%H:%M:%S)"
ALL_POT="$PWD"/genfiles/translations/new/all.pot.pickle

echo "Sanity check: will fail if the new all.pot is missing stuff."
[ `strings "$ALL_POT" | wc -l` -gt 3000000 ]
strings "$ALL_POT" | grep -q 'intl/datastore'

echo "Ending create of all.pot at $(date +%H:%M:%S)"

# Update export timestamps for fake languages.
mark_fake_langs=`cat <<PYCOMMAND
from deploy import download_i18n
download_i18n.mark_strings_export('accents')
download_i18n.mark_strings_export('boxes')
PYCOMMAND
`
python -c "$mark_fake_langs"

cp "$ALL_POT" "$DATA_DIR"/all.pot
