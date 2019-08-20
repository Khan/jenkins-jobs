#!/bin/sh -xe

# This is a simple job that takes an archive with .po files and commits them to
# intl/translations in webapp. We do this on Jenkins as it currently requires a
# fair bit of support code for pulling, merging and committing to the webapp
# repo, which would be a pain to port to Go.
#
# The LOCALE parameter specifies the locale we are updating pofiles for.
#
# The ARCHIVEID parameter is the ID of the pipeline run which we use to find
# the snapshot file to download with our pofiles in it.

: ${LOCALE:=}
: ${ARCHIVEID:=}

( cd webapp && make install_deps )

echo "Updating the webapp repo."
# We do our work in the 'automated-commits' branch.
jenkins-jobs/safe_git.sh pull_in_branch webapp automated-commits
# ...which we want to make sure is up-to-date with master.
jenkins-jobs/safe_git.sh merge_from_master webapp automated-commits
# We also make sure the intl/translations sub-repo is up to date.
jenkins-jobs/safe_git.sh pull webapp/intl/translations

TRANSLATIONS_DIR=`pwd`/webapp/intl/translations/pofiles
APPROVED_TRANSLATIONS_DIR=`pwd`/webapp/intl/translations/approved_pofiles

echo "Fetching pofiles snapshot from GCS"
gsutil cp "gs://ka_translations_archive/$LOCALE/$LOCALE-$ARCHIVEID.pofiles.tar.gz" ./pofiles.tar.gz
( cd webapp && tar -xvf ../pofiles.tar.gz)
rm pofiles.tar.gz

# This lets us commit messages without a test plan
export FORCE_COMMIT=1

echo "Checking in crowdin_stringids.pickle and [approved_]pofiles/*.po"
( cd webapp/intl/translations && git add . )

# If we updated some "bigfiles", we need to push them to S3.  We do
# that first so if it fails we don't do the git push.
(
    echo "Pushing bigfiles"
    cd webapp/intl/translations
    # If this repo uses bigfiles, we have to push them to S3 now, as well.
    timeout 120m env PATH="$HOME/git-bigfile/bin:$PATH" \
                    PYTHONPATH="/usr/lib/python2.7/dist-packages:$PYTHONPATH" \
                    git bigfile push
    # Clean up bigfile objects older than two days.
    timeout 240m find "`git rev-parse --git-dir`/bigfile/objects" -mtime +2 -type f -print0 \
        | xargs -r0 rm -f
)

# Now we can push to git.
jenkins-jobs/safe_git.sh commit_and_push_submodule \
    webapp intl/translations \
    -a \
    -m "Automatic update of crowdin .po files and crowdin_stringids.pickle" \
    -m "(locales: $LOCALES)" \
    -m "(at webapp commit `cd webapp && git rev-parse HEAD`)"

echo "DONE"