#!/bin/bash

# The jobs run by weekly-maintenance.sh.
#
# NOTE: weekly-maintenance.sh runs the functions in the order listed
# in this file, so if the job times out, stuff at the end is least
# likely to have gotten run.  So think carefully about where you place
# new functions!  Probably you want to put your new function before
# `clean_package_files`.
#
# Here are some cleanups we'd like to add:
#   vacuum unused indexes

set -ex

# This lets us commit messages without a test plan
export FORCE_COMMIT=1


# Every week, make sure that /var/lib/docker's size is under control.
clean_docker() {
    docker rm `docker ps -a | grep Exited | cut -f1 -d" "` || true
    docker rmi `docker images -aq` || true
}


# Let's make sure size is under control on the publish worker too.
clean_publish_worker() {
    gcloud compute --project khan-internal-services ssh --zone us-central1-b publish-worker -- sh -x /var/publish/clean.sh
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

# The merge-branches jenkins job creates a git-tag to make sure the
# merged code isn't gc-ed by Jenkins.  It only needs to survive for
# the length of a deploy: maybe an hour or so.  We keep these tags
# around a week.
prune_buildmaster_tags() {
    (
        cd webapp
        # We delete tags 100 at a time to avoid overwhelming github.
        git tag -l 'buildmaster-*' \
            | grep -v \
                   -e buildmaster-'[0-9]*'-$(date -d "today" +%Y%m%d) \
                   -e buildmaster-'[0-9]*'-$(date -d "today -1 day" +%Y%m%d) \
                   -e buildmaster-'[0-9]*'-$(date -d "today -2 days" +%Y%m%d) \
                   -e buildmaster-'[0-9]*'-$(date -d "today -3 days" +%Y%m%d) \
                   -e buildmaster-'[0-9]*'-$(date -d "today -4 days" +%Y%m%d) \
                   -e buildmaster-'[0-9]*'-$(date -d "today -5 days" +%Y%m%d) \
                   -e buildmaster-'[0-9]*'-$(date -d "today -6 days" +%Y%m%d) \
            | xargs -n 100 git push --no-verify --delete origin
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

        # We don't need reflogs in jenkins, and they can cause trouble
        # when gc-ing, so we remove them.  See
        #    https://feeding.cloud.geek.nz/posts/error-while-running-git-gc/
        # (We didn't do this above because /mnt/jenkins/repositories isn't
        # a "working" github dir, just a source of .pack files.)
        # We also get rid of stale branches, which can also cause gc issues.
        git remote prune origin
        git reflog expire --all --stale-fix
        git gc
        )
    done
}


clean_ka_content_data() {
    for dir in `gsutil ls gs://ka-content-data gs://ka-revision-data | grep -v :$`; do
        ka_locale=`echo "$dir" | cut -d/ -f4`

        # This sorts by date.  Each line looks like:
        #  <size>  YYYY-MM-DDTHH:MM:SSZ  gs://ka-*-data/<ka-locale>/[snapshot|manifest]-<hash>.json
        files=`gsutil ls -l $dir | sort -k2 | grep -v ^TOTAL:`

        # We keep all snapshot/manifest files that fit either of these criteria:
        # 1) One of the last 10 snapshots/manifests
        # 2) snapshot/manifest was created within the last 60 days
        # Based on:
        # https://khanacademy.slack.com/archives/C49296Q7P/p1686587762692149?thread_ts=1686584563.057689&cid=C49296Q7P
        # Because we have both manifest and snapshot files in each dir,
        # we keep the most recent 20 files; that's the 10 most recent uploads.
        not_last_ten=`echo "$files" | tac | tail -n+20`
        sixty_days_ago_time_t=`date -d "-60 days" +%s`
        current_sha=`curl -s "https://www.khanacademy.org/_fastly/published-content-version/$ka_locale" | jq -r .publishedContentVersion`
        if [ -z "$current_sha" ]; then
            echo "ERROR: skipping locale '$ka_locale': it lacks a sha!"
            continue
        fi

        echo "$not_last_ten" | while read size date fname; do
            time_t=`date -d "$date" +%s`
            if [ "$time_t" -lt "$sixty_days_ago_time_t" ]; then
                # Very basic sanity-check: never delete files from today!
                if echo "$date" | grep -q `date +%Y-%m-%d`; then
                    echo "FATAL ERROR: Why are we trying to delete $fname??"
                    exit 1
                fi
                # And never delete the current publish-content-version.
                if echo "$fname" | grep -q "$current_sha"; then
                    echo "FATAL ERROR: Why are we trying to delete $fname?"
                    exit 1
                fi

                echo "Deleting obsolete snapshot/manifest file $fname"
                gsutil rm "$fname"
            fi
        done
    done
}

clean_graphql_gateway_schemas() {
    # Files have the format:
    #    csdl-YYMMDD-HHMM-######.json(.gz)
    #    csdl-znd-YYMMDD-HHMM-######.json(.gz)
    #    v2-YYMMDD-HHMM-######.json
    #    v2-znd-YYMMDD-HHMM-######.json
    # We keep everything from the last two months.  But if there
    # are less than 50 non-znd files from the last two months, we
    # don't delete anything, just in case there haven't been any
    # schema updates for many months.  (The 50 was picked
    # arbitrarily.)
    grep_cmd="grep"
    for days_ago in `seq 0 62`; do   # 62 days is 2 months
        grep_cmd="$grep_cmd -e -`date +%y%m%d -d "-$days_ago days"`-"
    done

    dir=gs://ka-webapp/graphql-gateway/data_graph_configs
    num_non_znd_files_to_keep=`gsutil ls "$dir" | $grep_cmd | grep -v znd- | wc -l`
    if [ "$num_non_znd_files_to_keep" -gt 50 ]; then
        gsutil ls "$dir" | $grep_cmd -v | gsutil -m rm -I
    fi
}

# TODO(FEI-4154): Fix this logic to be more robust.
# We're disabling clean_ka_static() for the time
# being to avoid deleting files we need by accident.
# clean_ka_static() {
#     # First we ask Fastly for the list of live static versions.
#     # (Buildmaster is responsible for pruning that list.)
#     active_versions=`webapp/deploy/list_static_versions.py`

#     files_to_keep=`mktemp -d`/files_to_keep
#     # The 'ls -l' output looks like this:
#     #    2374523  2016-04-21T17:47:23Z  gs://ka-static/_manifest.foo
#     gsutil ls -l 'gs://ka-static/_manifest.*.json' | grep _manifest | sort -k2r | while read line; do
#         # (Since we create the manifest-files, we know they don't
#         # have spaces in their name.)
#         manifest=`echo "$line" | awk '{print $3}'`
#         manifest_version=`echo "$manifest" | cut -d. -f2`  # _manifest.<v>.json
#         if echo "$active_versions" | grep -q "$manifest_version"; then
#             # This gets the keys (which is the url) to each dict-entry
#             # in the manifest file.  The manifest file might be
#             # uploaded compressed, so I use `zcat -f` to uncompress it
#             # if needed. (`-f` handles uncompressed data correctly too.)
#             gsutil cat "$manifest" | zcat -f | grep -o '"[^"]*":' | tr -d '":' \
#                 >> "$files_to_keep"
#             # We also keep the manifest file itself around -- we do so
#             # explicitly since it does not reference itself.  We also
#             # explicitly keep the version's toc-file, because it is
#             # copied on a static-only deploy, and the manifest's
#             # reference to it is not updated.
#             # TODO(benkraft): Update the manifest on copy, and remove
#             # at least the latter special case.
#             echo "$manifest" >> "$files_to_keep"
#             echo "/genfiles/manifests/toc-webpack-manifest-$manifest_version.json" >> "$files_to_keep"
#         fi
#     done

#     # We need to add the gs://ka-static prefix to match the gsutil ls output.
#     sed s,^/,gs://ka-static/, "$files_to_keep" \
#         | LANG=C sort -u > "$files_to_keep.sorted"

#     # Basic sanity check: make sure favicon.ico is in the list of files
#     # to keep.  If not, something has gone terribly wrong.
#     if ! grep -q "favicon.ico" "$files_to_keep.sorted"; then
#         echo "FATAL ERROR: The list of files-to-keep seems to be wrong"
#         exit 1
#     fi

#     # Configure the whitelist. Any files in the whitelist will be kept around
#     # in perpetuity.
#     # We need to keep topic icons around forever because older mobile clients
#     # continue to point to them. Thankfully, they don't change that often, and
#     # so we shouldn't expect an explosion of stale icons. We don't need to
#     # worry about keeping older manifests around, since the mobile clients
#     # download and ship with the most recent manifest.  We keep _manifest.json
#     # around since it's used by the static deploy process to reduce the number
#     # of files we need to upload to GCS (it contains a list of files that were
#     # uploaded during the last static deploy).
#     # We also need to keep around CKEditor, live-editor, and MathJax as we
#     # treat them as a static asset at this point. More information:
#     # https://khanacademy.atlassian.net/wiki/spaces/ENG/pages/1257046459/Static+JS+Third+Party+Library+Files
#     KA_STATIC_WHITELIST="-e genfiles/topic-icons/ -e ckeditor/ -e live-editor/ -e khan-mathjax-build/ -e /_manifest.json"

#     # Now we go through every file in ka-static and delete it if it's
#     # not in files-to-keep.  We ignore lines ending with ':' -- those
#     # are directories.  We also ignore any files in the whitelist.
#     # Finally, we keep any files touched recently: they were presumably
#     # deployed for a reason, perhaps due to an ongoing deploy whose
#     # manifest has not yet been uploaded.
#     # The 'ls -l' output looks like this:
#     #    2374523  2016-04-21T17:47:23Z  gs://ka-static/_manifest.foo
#     # TODO(csilvers): make the xargs 'gsutil -m' after we've debugged
#     # why that sometimes fails with 'file not found'.
#     yesterday_or_today="-e `date --utc +"%Y-%m-%d"`T -e `date --utc -d "-1 day" +"%Y-%m-%d"`T"
#     gsutil -m ls -r gs://ka-static/ \
#         | grep . \
#         | grep -v ':$' \
#         | grep -v $KA_STATIC_WHITELIST \
#         | grep -v $yesterday_or_today \
#         | LANG=C sort > "$files_to_keep.candidates"
#     # This prints files in 'candidates that are *not* in files_to_keep.
#     LANG=C comm -23 "$files_to_keep.candidates" "$files_to_keep.sorted" \
#         | tr '\012' '\0' \
#         | xargs -0r gsutil -m rm
# }


backup_network_config() {
    (
        cd network-config
        make deps
        make CONFIG=$HOME/s3-reader.cfg PROFILE=default GOOGLE_APPLICATION_CREDENTIALS=$HOME/gcloud-service-account.json
        git add .
    )
    # The subshell lists every directory we have a Makefile in.
    jenkins-jobs/safe_git.sh commit_and_push network-config -a -m "Automatic update of `ls network-config/*/Makefile | xargs -n1 dirname | xargs -n1 basename | xargs`"
}


# Delete unused queries from our GraphQL safelist.
clean_unused_graphql_safelist_queries() {
    # Let's back it up first.
    ( cd webapp; tools/datastore-get.sh -prod -format=json GraphQLQuery | gzip | gsutil cp - gs://ka_backups/graphql-safelist/`date +%Y%m%d`.json.gz )
    ( cd webapp; tools/prune_graphql_safelist.sh --prod )
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
        for d in `git grep -l caniuse-lite "*pnpm-lock.yaml" | xargs -n1 dirname`; do
            (
                cd "$d"
                # Use the official tool to update the browserslist and caniuse-lite packages.
                pnpm up caniuse-lite --no-save
            )
        done
    )
    jenkins-jobs/safe_git.sh commit_and_push webapp -m "Automatic update of caniuse, via $0" '*/pnpm-lock.yaml'
}


# weekly-maintenance.sh calls this script with exactly one arg, the job
# to run.  So let's run it!
$1
