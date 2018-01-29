#!/bin/bash -xe

# A Jenkins job that periodically runs some cleanup tasks on
# our webapp.
#
# Here are some of the cleanups we run:
#   deploy/pngcrush.py
#       compress images
#   clean up obsolete containers in /var/lib/docker
#   clean up genfiles directories in every repo
#   delete obsolete files on GCS (ka-static and ka_translations)
#   remove obsolete webapp deploy-branches on github
#   TODO: vacuum unused indexes
#   TODO: store test times to use with the @tiny/@small/@large decorators
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

# This lets us commit messages without a test plan
export FORCE_COMMIT=1

# Sync the repos we're going to be pushing changes to.
jenkins-jobs/safe_git.sh sync_to_origin "git@github.com:Khan/webapp" "master"
jenkins-jobs/safe_git.sh sync_to_origin "git@github.com:Khan/network-config" "master"

( cd webapp && make deps )


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


# Every week, make sure that /var/lib/docker's size is under control.
# We then re-do a docker run to re-create the docker images we
# actually still need (to make the next deploy faster).
clean_docker() {
    docker rm `docker ps -a | grep Exited | cut -f1 -d" "` || true
    docker rmi `docker images -aq` || true
    ( cd webapp && make docker-prod-staging-dir )
}


# Delete unused queries from our GraphQL whitelist.
clean_unused_queries() {
    curl --retry 3 https://www.khanacademy.org/api/internal/graphql_whitelist/clean
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

# Every week, we prune invalid branches that creep into our repos somehow.
# See http://stackoverflow.com/questions/6265502/getting-rid-of-does-not-point-to-a-valid-object-for-an-old-git-branch
clean_invalid_branches() {
    find $HOME/jobs/*/jobs -maxdepth 4 -name ".git" -type d | while read dir; do
        (
        dir=`dirname "$dir"`
        echo "Cleaning invalid branches in $dir"
        cd "$dir"

        find .git/refs -type f | while read ref; do
            id=`cat "$ref"`
            if git rev-parse -q --verify "$id" >/dev/null && \
               ! git rev-parse -q --verify "$id^{commit}" >/dev/null; then
                echo "Removing ref $ref with missing commit $id"
                rm "$ref"
            fi
        done

        cat .git/packed-refs | awk '/refs\// {print $2}' | while read ref; do
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
        cd $HOME/jobs/deploy/jobs/deploy-webapp/workspace/webapp
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


# Clean up some gcs directories that have too-complicated cleanup
# rules to use the gcs lifecycle rules.
clean_ka_translations() {
    for dir in `gsutil ls gs://ka_translations`; do
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
    # First we find the manifest files for the last week.  (Plus we
    # always keep the last 3.)  We'll keep any file listed in there.

    # We also keep the manifest files for any version still on appengine.
    # (audit_gae_versions.py has a bunch of other text it emits besides
    # the active versions, but it's ok to have extra stuff.)
    active_versions=`webapp/tools/audit_gae_versions.py -n`

    week_ago_time_t=`date -d "-7 days" +%s`
    manifests_seen=0
    files_to_keep=`mktemp -d`/files_to_keep
    # The 'ls -l' output looks like this:
    #    2374523  2016-04-21T17:47:23Z  gs://ka-static/_manifest.foo
    gsutil ls -l 'gs://ka-static/_manifest.*.json' | grep _manifest | sort -k2r | while read line; do
        date=`echo "$line" | awk '{print $2}'`
        time_t=`date -d "$date" +%s`
        # (Since we create the manifest-files, we know they don't
        # have spaces in their name.)
        manifest=`echo "$line" | awk '{print $3}'`
        manifest_version=`echo "$manifest" | cut -d. -f2`  # _manifest.<v>.json
        if [ "$time_t" -gt "$week_ago_time_t" -o $manifests_seen -lt 3 ] \
            || echo "$active_versions" | grep -q "$manifest_version"; then
            # This gets the keys (which is the url) to each dict-entry
            # in the manifest file.  The manifest file might be
            # uploaded compressed, so I use `zcat -f` to uncompress it
            # if needed. (`-f` handles uncompressed data correctly too.)
            gsutil cat "$manifest" | zcat -f | grep -o '"[^"]*":' | tr -d '":' \
                >> "$files_to_keep"
            # We also keep the manifest file itself around.  This
            # shouldn't be necessary -- it includes itself as an
            # entry -- but in the case where we "copy" a static version
            # (webapp's deploy.deploy_to_gcs.symlink_static_content)
            # we don't update that self-reference.  (And it's safer
            # anyway.)  We also explicitly keep the version's toc-file,
            # because it is also copied, and the manifest's reference
            # to it is similarly not updated.
            # TODO(benkraft): Update the manifest on copy, and remove
            # these special cases.
            echo "$manifest" >> "$files_to_keep"
            echo "/genfiles/manifests/toc-$manifest_version.json" >> "$files_to_keep"
            manifests_seen=`expr $manifests_seen + 1`
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
    # TODO(csilvers): make the xargs 'gsutil -m' after we've debugged
    # why that sometimes fails with 'file not found'.
    gsutil -m ls -r gs://ka-static/ \
        | grep . \
        | grep -v ':$' \
        | grep -v $KA_STATIC_WHITELIST \
        | LANG=C sort > "$files_to_keep.candidates"
    # This prints files in 'candidates that are *not* in files_to_keep.
    LANG=C comm -23 "$files_to_keep.candidates" "$files_to_keep.sorted" \
        | tr '\012' '\0' \
        | xargs -0r gsutil rm
}

backup_network_config() {
    # TODO(csilvers): figure out how to automate the runs of the other dirs too
    NETWORK_CONFIG_BACKUP_DIRS="bigquery dns fastly gce gcs network_configs s3"
    for dir in $NETWORK_CONFIG_BACKUP_DIRS; do
        (
            cd "network-config/$dir"
            make ACCOUNT=storage-read@khanacademy.org CONFIG=$HOME/s3-reader.cfg PROFILE=default
        )
    done

    ( cd network-config; git add . )
    jenkins-jobs/safe_git.sh commit_and_push network-config -a -m "Automatic update of $NETWORK_CONFIG_BACKUP_DIRS"
}


clean_docker
clean_genfiles
clean_invalid_branches
turn_old_branches_into_tags
clean_ka_translations
clean_ka_static
backup_network_config
clean_unused_queries
svgcrush
pngcrush
