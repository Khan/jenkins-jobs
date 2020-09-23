#!/bin/bash

# A Jenkins job that periodically runs some cleanup tasks on
# our webapp.
#
# NOTE: We run the functions in the order listed in this file,
# so if the job times out, stuff at the end is least likely to have
# gotten run.  So think carefully about where you place new functions!
# Probably you want to put your new function before `svgcrush`.
#
# Here are some cleanups we'd like to add:
#   vacuum unused indexes
#   store test times to use with the @tiny/@small/@large decorators
#
# There are also some cleanups we'd like to run but probably can't
# because they require manual intervention:
#   tools/find_nltext_in_js
#       find places in .js files that need $._(...)
#   tools/list_unused_images
#       find images we can delete from the repo entirely
#   tools/list_unused_api_calls.py
#       find /api/... routes we can delete from the repo entirely
#   deploy/list_files_uploaded_to_appengine
#       find files we can add to skip_files.yaml
#   clean up translations that download_i18n.py's linter complains about
#   clean up the bottom of lint_blacklist.txt
#   move not-commonly-used s3 data to glacier

set -e

# This lets us commit messages without a test plan
export FORCE_COMMIT=1


# Every week, make sure that /var/lib/docker's size is under control.
# We then re-do a docker run to re-create the docker images we
# actually still need (to make the next deploy faster).
clean_docker() {
    docker rm `docker ps -a | grep Exited | cut -f1 -d" "` || true
    docker rmi `docker images -aq` || true
    ( cd webapp && make docker-prod-staging-dir )
}


# Every week, we do a 'partial' clean of genfiles directories that
# gets rid of certain files that are "probably" obsolete.
clean_genfiles() {
    for dir in $HOME/jobs/*/jobs/*/workspace/webapp/genfiles; do
        (
        echo "Cleaning genfiles in $dir"
        cd "$dir"

        # This means that slow-changing languages may get nuked even
        # though they're up to date, but we'll just redownload them so
        # no harm done.
        find translations/pofiles -mtime +7 -a -type f -print0 | xargs -0r rm -v
        find translations/approved_pofiles -mtime +7 -a -type f -print0 | xargs -0r rm -v
        )
    done
}


# Every week, we compress jenkins logs.  Jenkins can read compressed
# log files but has trouble making them, so we just making them manually.
compress_jenkins_logs() {
    for dir in $HOME/jobs/*/jobs/*/builds; do
        (
        echo "Compressing log-files in $dir"
        cd "$dir"

        # Ignore logs that are less than a day old; we might still be
        # writing to them.  (We assume no jenkins job runs for >24 hours!)
        find . -mtime +1 -a -type f -a \( -name 'log' -o -name '*.log' \) -print0 | xargs -0rt gzip
        )
    done
}


# Every week, we prune invalid branches that creep into our repos somehow.
# See http://stackoverflow.com/questions/6265502/getting-rid-of-does-not-point-to-a-valid-object-for-an-old-git-branch
clean_invalid_branches() {
    find $HOME/jobs/*/jobs -maxdepth 4 -name ".git" -type d | while read dir; do
        (
        dir=`dirname "$dir"`
        echo "Cleaning invalid branches in $dir"
        cd "$dir"

        find .git/refs/remotes -type f | while read ref; do
            id=`cat "$ref"`
            if git rev-parse -q --verify "$id" >/dev/null && \
               ! git rev-parse -q --verify "$id^{commit}" >/dev/null; then
                echo "Removing ref $ref with missing commit $id"
                rm "$ref"
            fi
        done

        [ -s .git/packed-refs ] || continue

        cat .git/packed-refs | awk '/refs\/remotes/ {print $2}' | while read ref; do
            id=`git rev-parse -q --verify "$ref"`   # "" if we fail to verify
            if [ -n "$id" ] && \
               git rev-parse -q --verify "$id" >/dev/null && \
               ! git rev-parse -q --verify "$id^{commit}" >/dev/null; then
                echo "Removing packed ref $ref with missing commit $id"
                git update-ref -d "$ref"
            fi
        done
        )
    done
}


# Turn branches that haven't been worked on a for a while -- like, 6
# months -- into tags.  No content is deleted, but if you ever wanted to
# continue working on that branch again you'd need to recreate it in
# git. The reason we bother is that phabricator daemons do `git branch
# --contains` which gets very slow when there are a lot of branches.
turn_old_branches_into_tags() {
    (
        cd webapp
        echo "Turning old branches into tags in `pwd`"

        git fetch --prune origin
        six_months_ago=`date +%s -d "-6 months"`
        # This pattern-match should be good for another 83 years or so!
        git for-each-ref --format='%(refname:strip=3) %(authordate:unix)' \
                         'refs/remotes/origin/*' \
        | while read branch date; do
            if [ "$date" -lt "$six_months_ago" ]; then
                echo "Turning '$branch' from a branch into a tag"
                # This copies the branch to a tag and then deletes the
                # branch, but *only* if the copy succeeded.
                git push origin "origin/$branch:refs/tags/$branch" \
                    && git push origin ":refs/heads/$branch"
            fi
        done
    )
}


# Explicitly run `gc` on every workspace.  This causes us to repack
# all our objects using the "alternates" directory, which saves a
# lot of space.
gc_all_repos() {
    # Make sure we have all the objects we need in the "canonical" repo
    find /mnt/jenkins/repositories -maxdepth 4 -name ".git" -type d | while read dir; do
        (
        dir=`dirname "$dir"`
        echo "Fetching in $dir"
        cd "$dir"

        git fetch --progress origin
        git gc
        )
    done

    find "$HOME"/jobs/*/jobs -maxdepth 4 -name ".git" -type d | while read dir; do
        (
        dir=`dirname "$dir"`
        echo "GC-ing in $dir"
        cd "$dir"

        git gc
        )
    done
}


# Clean up some gcs directories that have too-complicated cleanup
# rules to use the gcs lifecycle rules.
clean_ka_translations() {
    for dir in `gsutil ls gs://ka_translations`; do
        # Ignore the "raw" dir, which isn't a language.
        [ "$dir" = "gs://ka_translations/raw/" ] && continue
        versions=`gsutil ls $dir | sort`
        # We keep all version-dirs that fit either of these criteria:
        # 1) One of the last 3 versions
        # 2) version was created within the last week
        not_last_three=`echo "$versions" | tac | tail -n+4`
        week_ago_time_t=`date -d "-7 days" +%s`
        for version in $not_last_three; do
            # `basename $version` looks like "2016-04-17-2329",
            # but `date` wants "2016-04-17 23:29".
            date="`basename "$version" | cut -b1-10` `basename "$version" | cut -b12-13`:`basename "$version" | cut -b14-15`"
            # It seems like this file uses UTC dates.
            time_t=`env TZ=UTC date -d "$date" +%s`
            if [ "$time_t" -lt "$week_ago_time_t" ]; then
                # Very basic sanity-check: never delete files from today!
                if echo "$version" | grep -q `date +%Y-%m-%d-`; then
                    echo "FATAL ERROR: Why are we trying to delete $version??"
                    exit 1
                fi
                echo "Deleting obsolete directory $version"
                # TODO(csilvers): make it 'gsutil -m' after we've debugged
                # why that sometimes fails with 'file not found'.
                gsutil rm -r "$version"
            fi
        done
    done
}


clean_ka_static() {
    # First we ask Fastly for the list of live static versions.
    # (Buildmaster is responsible for pruning that list.)
    active_versions=`webapp/deploy/list_static_versions.py`

    files_to_keep=`mktemp -d`/files_to_keep
    # The 'ls -l' output looks like this:
    #    2374523  2016-04-21T17:47:23Z  gs://ka-static/_manifest.foo
    gsutil ls -l 'gs://ka-static/_manifest.*.json' | grep _manifest | sort -k2r | while read line; do
        # (Since we create the manifest-files, we know they don't
        # have spaces in their name.)
        manifest=`echo "$line" | awk '{print $3}'`
        manifest_version=`echo "$manifest" | cut -d. -f2`  # _manifest.<v>.json
        if echo "$active_versions" | grep -q "$manifest_version"; then
            # This gets the keys (which is the url) to each dict-entry
            # in the manifest file.  The manifest file might be
            # uploaded compressed, so I use `zcat -f` to uncompress it
            # if needed. (`-f` handles uncompressed data correctly too.)
            gsutil cat "$manifest" | zcat -f | grep -o '"[^"]*":' | tr -d '":' \
                >> "$files_to_keep"
            # We also keep the manifest file itself around -- we do so
            # explicitly since it does not reference itself.  We also
            # explicitly keep the version's toc-file, because it is
            # copied on a static-only deploy, and the manifest's
            # reference to it is not updated.
            # TODO(benkraft): Update the manifest on copy, and remove
            # at least the latter special case.
            echo "$manifest" >> "$files_to_keep"
            echo "/genfiles/manifests/toc-webpack-manifest-$manifest_version.json" >> "$files_to_keep"
        fi
    done

    # We need to add the gs://ka-static prefix to match the gsutil ls output.
    sed s,^/,gs://ka-static/, "$files_to_keep" \
        | LANG=C sort -u > "$files_to_keep.sorted"

    # Basic sanity check: make sure favicon.ico is in the list of files
    # to keep.  If not, something has gone terribly wrong.
    if ! grep -q "favicon.ico" "$files_to_keep.sorted"; then
        echo "FATAL ERROR: The list of files-to-keep seems to be wrong"
        exit 1
    fi

    # Configure the whitelist. Any files in the whitelist will be kept around
    # in perpetuity.
    # We need to keep topic icons around forever because older mobile clients
    # continue to point to them. Thankfully, they don't change that often, and
    # so we shouldn't expect an explosion of stale icons. We don't need to
    # worry about keeping older manifests around, since the mobile clients
    # download and ship with the most recent manifest.
    KA_STATIC_WHITELIST="-e genfiles/topic-icons/icons/"

    # Now we go through every file in ka-static and delete it if it's
    # not in files-to-keep.  We ignore lines ending with ':' -- those
    # are directories.  We also ignore any files in the whitelist.
    # Finally, we keep any files touched recently: they were presumably
    # deployed for a reason, perhaps due to an ongoing deploy whose
    # manifest has not yet been uploaded.
    # The 'ls -l' output looks like this:
    #    2374523  2016-04-21T17:47:23Z  gs://ka-static/_manifest.foo
    # TODO(csilvers): make the xargs 'gsutil -m' after we've debugged
    # why that sometimes fails with 'file not found'.
    yesterday_or_today="-e `date --utc +"%Y-%m-%d"`T -e `date --utc -d "-1 day" +"%Y-%m-%d"`T"
    gsutil -m ls -r gs://ka-static/ \
        | grep . \
        | grep -v ':$' \
        | grep -v $KA_STATIC_WHITELIST \
        | grep -v $yesterday_or_today \
        | LANG=C sort > "$files_to_keep.candidates"
    # This prints files in 'candidates that are *not* in files_to_keep.
    LANG=C comm -23 "$files_to_keep.candidates" "$files_to_keep.sorted" \
        | tr '\012' '\0' \
        | xargs -0r gsutil -m rm
}


backup_network_config() {
    (
        cd network-config
        make deps
        make ACCOUNT=storage-read@khanacademy.org CONFIG=$HOME/s3-reader.cfg PROFILE=default GOOGLE_APPLICATION_CREDENTIALS=$HOME/gcloud-service-account.json
        git add .
    )
    @: The subshell lists every directory we have a Makefile in.
    jenkins-jobs/safe_git.sh commit_and_push network-config -a -m "Automatic update of `ls network-config/*/Makefile | xargs -n1 dirname | xargs -n1 basename | xargs`"
}


# Delete unused queries from our GraphQL whitelist.
clean_unused_queries() {
    curl --retry 3 https://www.khanacademy.org/api/internal/gql_safelist/clean
}


svgcrush() {
    # Note: this can't be combined with the subshell below; we need to
    # make sure it terminates *before* the pipe starts.
    ( cd webapp; deploy/svgcrush.py; git add '*.svg' )
    (
        cd webapp
        echo "Automatic compression of webapp svg files via $0"
        echo
        echo "| size % | old size | new size | filename"
        git status --porcelain | sort | while read status filename; do
            old_size=`git show HEAD:$filename | wc -c`
            new_size=`cat $filename | wc -c`      # git shell-escapes filename!
            ratio=`expr $new_size \* 100 / $old_size`
            echo "| $ratio% | $old_size | $new_size | $filename"
        done
    ) | jenkins-jobs/safe_git.sh commit_and_push webapp -F - '*.svg'
}


pngcrush() {
    # Note: this can't be combined with the subshell below; we need to
    # make sure it terminates *before* the pipe starts.
    ( cd webapp; deploy/pngcrush.py; git add '*.png' '*.jpeg' )
    (
        cd webapp
        echo "Automatic compression of webapp images via $0"
        echo
        echo "| size % | old size | new size | filename"
        git status --porcelain | sort | while read status filename; do
            old_size=`git show HEAD:$filename | wc -c`
            new_size=`cat $filename | wc -c`   # git shell-escapes `filename`!
            ratio=`expr $new_size \* 100 / $old_size`
            echo "| $ratio% | $old_size | $new_size | $filename"
        done
    ) | jenkins-jobs/safe_git.sh commit_and_push webapp -F - '*.png' '*.jpeg' '*.jpg'
}


clean_package_files() {
    ( cd webapp; go mod tidy; git add 'go.*' )
    jenkins-jobs/safe_git.sh commit_and_push webapp -a -m "Automatic cleanup of language package files"
}


update_caniuse() {
    # The nodejs "caniuse" library starts complaining if it's more
    # than a few months out of date.  To avoid that, let's auto-update
    # it every week!  I follow the instructions at
    #    https://github.com/facebook/create-react-app/issues/6708#issuecomment-488392836
    (
        cd webapp
        # This deletes everything from the first "caniuse-lite" line
        # to the following blank line, from yarn.lock.
        sed -i '/^caniuse-lite@/,/^$/d' yarn.lock
        yarn upgrade caniuse-lite browserlist
    )
    jenkins-jobs/safe_git.sh commit_and_push webapp -m "Automatic update of caniuse, via $0" yarn.lock
}


# Introspection, shell-script style!
ALL_JOBS=`grep -o '^[a-zA-Z0-9_]*()' "$0" | tr -d '()'`

# Let's make sure we didn't define two jobs with the same name.
duplicate_jobs=`echo "$ALL_JOBS" | sort | uniq -d`
if [ -n "$duplicate_jobs" ]; then
    echo "Defined multiple jobs with the same name:"
    echo "$duplicate_jobs"
    exit 1
fi


if [ "$1" = "-l" -o "$1" = "--list" ]; then
    echo "$ALL_JOBS"
    exit 0
elif [ -n "$1" ]; then          # they specified which jobs to run
    jobs_to_run="$@"
else
    jobs_to_run="$ALL_JOBS"
fi


set -x

# Sync the repos we're going to be pushing changes to.
# We change webapp in the 'automated-commits' branch.
jenkins-jobs/safe_git.sh sync_to_origin "git@github.com:Khan/webapp" "automated-commits"
jenkins-jobs/safe_git.sh sync_to_origin "git@github.com:Khan/network-config" "master"

( cd webapp && make deps )


failed_jobs=""
for job in $jobs_to_run; do
    (
        echo "--- Starting $job: `date`"
        $job
        echo "--- Finished $job: `date`"
    ) || failed_jobs="$failed_jobs $job"
done


if [ -n "$failed_jobs" ]; then
    echo "THE FOLLOWING JOBS FAILED: $failed_jobs"
    exit 1
else
    echo "All done!"
    exit 0
fi
