#!/bin/sh -e

# If you only want to run a subset of tests, run this with the
# tests you want to run as arguments.  You can get a list by
# running this script with `-l` or `--list`.

ROOT=/tmp/safe_git_test_repos
SAFE_GIT="$PWD/safe_git.sh"


# $1: filename to check
# $2: expected contents.  Can use "\n" for newline.  Be sure to put it in
#     single-quotes!
_assert_file() {
    /bin/echo -ne "$2" | cmp - "$1" || {
        echo "$1: unexpected contents."
        echo "Expected:"
        echo "---"
        /bin/echo -ne "$2"
        echo "---"
        echo "Actual:"
        echo "---"
        cat "$1"
        echo "---"
        exit 1
    }
}


# $1: the dir of the repo you want to verify is at master.
# $2: the dir of the same repo in "origin"
_verify_at_master() {
    diff -r -x .git "$1" "$2"
}


# $1: filename (relative to the repo-root)
# $2 (optional): text to append to the filename each commit, defaults to $1
# $3 (optional): number of commits to do (defaults to 1)
create_git_history() {
    for i in `seq ${3-1}`; do
        echo "${2-$1}" >> "$1"
        git add "$1"
        git commit -m "$1: commit #$i"
    done
}



create_test_repos() {
    (
        mkdir -p origin
        cd origin

        git init subrepo1
        git init subrepo2
        git init subrepo3
        cd subrepo1
        create_git_history "foo" "foo subrepo1" 3
        create_git_history "bar" "bar subrepo1" 3
        cd ../subrepo2
        create_git_history "foo" "foo subrepo2" 3
        cd ../subrepo3
        create_git_history "foo" "foo subrepo3" 3
        cd ..

        git init repo
        cd repo
        git submodule add ../subrepo1
        git submodule add ../subrepo2
        git submodule add ../subrepo3
        git commit -a -m "Added subrepos"
        create_git_history "foo" "foo" 3
        create_git_history "bar" "bar" 3
    )
}


# Test that we overwrite local changes (that are not reflected in the remote).
test_safe_sync_to_origin__local_changes() {
    (
        mkdir -p safe_sync_to_test__local_changes
        cd safe_sync_to_test__local_changes
        export WORKSPACE_ROOT=.

        echo "--- Make sure sync_to_origin actually takes us to master"
        "$SAFE_GIT" sync_to_origin "$ROOT/origin/repo" "master"
        _verify_at_master repo ../origin/repo

        echo "--- Sync to a previous commit and make sure we go back"
        ( cd repo && git reset --hard HEAD^ )
        "$SAFE_GIT" sync_to_origin "$ROOT/origin/repo" "master"
        _verify_at_master repo ../origin/repo

        echo "--- Check in a change local-only and make sure sync_to_origin ignores it"
        sha1=`cd repo && git rev-parse HEAD`
        ( cd repo && create_git_history "foo" )
        "$SAFE_GIT" sync_to_origin "$ROOT/origin/repo" "$sha1"
        _verify_at_master repo ../origin/repo

        echo "--- Check in some submodule changes too"
        ( cd repo/subrepo1 && create_git_history "foo" )
        ( cd repo/subrepo2 && create_git_history "foo" )
        ( cd repo && git commit -am "Submodules" )
        "$SAFE_GIT" sync_to_origin "$ROOT/origin/repo" "$sha1"
        _verify_at_master repo ../origin/repo

        echo "--- Change some files without checking them in"
        # Likewise, check in a submodule change but don't update substate.
        echo "foo" >> repo/foo
        echo "foo" >> repo/subrepo1/foo
        ( cd repo/subrepo2 && create_git_history "foo" )
        "$SAFE_GIT" sync_to_origin "$ROOT/origin/repo" "$sha1"
        _verify_at_master repo ../origin/repo

        echo "--- Delete a submodule directory and make sure we get it back"
        rm -rf repo/subrepo2
        "$SAFE_GIT" sync_to_origin "$ROOT/origin/repo" "$sha1"
        _verify_at_master repo ../origin/repo

        echo "--- Add a directory and make sure it goes away"
        mkdir -p repo/empty_dir
        echo "new" repo/empty_dir/new
        "$SAFE_GIT" sync_to_origin "$ROOT/origin/repo" "$sha1"
        # TODO(csilvers): delete empty dirs
        #_verify_at_master repo ../origin/repo
    )
    echo "PASS: test_safe_sync_to__local_changes"
}


# Test that changes to the remote are reflected faithfully locally.
test_safe_sync_to_origin__upstream_changes() {
    (
        mkdir -p safe_sync_to_test__upstream_changes
        cd safe_sync_to_test__upstream_changes
        export WORKSPACE_ROOT=.

        "$SAFE_GIT" sync_to_origin "$ROOT/origin/repo" "master"
        _verify_at_master repo ../origin/repo

        echo "--- Update a file"
        ( cd ../origin/repo && create_git_history "foo" )
        "$SAFE_GIT" sync_to_origin "$ROOT/origin/repo" "master"
        _verify_at_master repo ../origin/repo

        echo "--- Add a file"
        ( cd ../origin/repo && create_git_history "baz" )
        "$SAFE_GIT" sync_to_origin "$ROOT/origin/repo" "master"
        _verify_at_master repo ../origin/repo

        echo "--- Delete a file"
        ( cd ../origin/repo && git rm bar && git commit -am "deleted bar" )
        "$SAFE_GIT" sync_to_origin "$ROOT/origin/repo" "master"
        _verify_at_master repo ../origin/repo

        echo "--- Update substate"
        ( cd ../origin/subrepo1 && create_git_history "foo" 2 )
        ( cd ../origin/repo/subrepo1 && git pull &&
            cd .. && git commit -am "Submodules" )
        "$SAFE_GIT" sync_to_origin "$ROOT/origin/repo" "master"
        _verify_at_master repo ../origin/repo

        echo "--- Rollback some substate"
        ( cd ../origin/subrepo1 && git reset --hard HEAD^ )
        ( cd ../origin/repo/subrepo1 && git pull &&
            cd .. && git commit -am "Submodules" )
        "$SAFE_GIT" sync_to_origin "$ROOT/origin/repo" "master"
        _verify_at_master repo ../origin/repo

        echo "--- Add a new submodule"
        ( cd ../origin/repo && git submodule add ../subrepo3 subrepo3_again &&
            git commit -am "New submodule" )
        "$SAFE_GIT" sync_to_origin "$ROOT/origin/repo" "master"
        _verify_at_master repo ../origin/repo

        echo "--- Delete a submodule"
        ( cd ../origin/repo && git rm subrepo3 && git commit -am "Nix subrepo" )
        "$SAFE_GIT" sync_to_origin "$ROOT/origin/repo" "master"
        _verify_at_master repo ../origin/repo

        echo "--- Change what a submodule points to"
        ( cd ../origin/repo &&
            sed -i -e 's,url = ../subrepo2,url = ../subrepo1,' .gitmodules &&
            git submodule sync && git submodule update &&
            cd subrepo2 && git checkout master && git pull && cd - &&
            git commit -am "Repointed submodule" &&
            git submodule update --init --recursive )
        "$SAFE_GIT" sync_to_origin "$ROOT/origin/repo" "master"
        _verify_at_master repo ../origin/repo
    )
}


ALL_TESTS="
test_safe_sync_to_origin__local_changes
test_safe_sync_to_origin__upstream_changes
"

if [ "$1" = "-l" -o "$1" = "--list" ]; then
    echo "Tests you can run:"
    echo "$ALL_TESTS"
    exit 0
elif [ -n "$1" ]; then          # they specified which tests to run
    tests_to_run="$@"
else
    tests_to_run="$ALL_TESTS"
fi


rm -rf "$ROOT"
mkdir -p "$ROOT"
cd "$ROOT"

# Set the envvars that safe_git.sh looks at
export WORKSPACE_ROOT="$ROOT"
export REPOS_ROOT="$ROOT/repositories"
export SLACK_CHANNEL=.
mkdir -p "$WORKSPACE_ROOT" "$REPOS_ROOT"

create_test_repos
for test in $tests_to_run; do
    $test
done

echo "All done!  PASS"
