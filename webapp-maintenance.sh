#!/bin/bash -xe

# A Jenkins job that periodically runs some cleanup tasks on
# our webapp.
#
# Here are some of the cleanups we run:
#   deploy/pngcrush.py
#       compress images
#   clean up obsolete containers in /var/lib/docker
#   TODO: khan-exercises/local-only/update_local.sh
#       get khan-exercises matching webpp
#   TODO: vacuum unused indexes
#   TODO: store test times to use with the @tiny/@small/@large decorators
#   TODO: remove obsolete webapp deploy-branches on github
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

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd -P )"
source "${SCRIPT_DIR}/build.lib"
ensure_virtualenv

# This lets us commit messages without a test plan
export FORCE_COMMIT=1

cd "$WEBSITE_ROOT"

safe_pull .

pngcrush() {
    deploy/pngcrush.py
    {
        echo "Automatic compression of webapp images via $0"
        echo
        echo "| size % | old size | new size | filename"
        git status --porcelain | sort | while read status filename; do
            old_size=`git show HEAD:"$filename" | wc -c`
            new_size=`cat "$filename" | wc -c`
            ratio=`expr $new_size \* 100 / $old_size`
            echo "| $ratio% | $old_size | $new_size | $filename"
        done
    } | safe_commit_and_push . -a -F -
}

# Every week, make sure that /var/lib/docker's size is under control.
# We then re-do a docker run to re-create the docker images we
# actually still need (to make the next deploy faster).
clean_docker() {
    docker rm `docker ps -a | grep Exited | cut -f1 -d" "` || true
    docker rmi `docker images -aq` || true
    make docker-prod-staging-dir
}


pngcrush
clean_docker
