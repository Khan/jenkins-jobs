#!/bin/sh

# This script lets you run various high-level git operations in a
# "safe" way.  For instance, destructive_checkout() does
# `git reset --hard` + `git clean`, in both the main repo and
# all submodules.  This is a very "safe" reset!  Similarly for
# other operations, which do pull, push, merge, and clone, all
# in a way that's super-safe (though not super-speedy).
#
# This script also automatically supports a few wrinkles to the
# way we use git:
# 1) It uses git new-workdir to share objects across repos.
#    This is a big win on jenkins, where we have a dozen
#    directories that all have cloned Khan/webapp.  Using
#    safe_git.sh, we make sure they each share a single copy
#    of .git/objects.  safe_git.sh not only implements that,
#    it does all the locking to make sure it's safe.
#
# USAGE: safe_git.sh <command> <args>, where <command> is one
# of the function names defined below.  While we don't enforce
# this, you should not use a command that starts with an
# underscore; those are private to this file.

set -ex


: ${WORKSPACE_ROOT:=.}
# Make this path absolute, so clients can chdir with impunity.
WORKSPACE_ROOT=`cd "$WORKSPACE_ROOT" && pwd`

# Where the shared git objects (used by git new-workdir) live.
: ${REPOS_ROOT:=/var/lib/jenkins/repositories}

# Default Slack channel to use for alerting.
: ${SLACK_CHANNEL:=#bot-testing}

# Alias needed for OS X.
type timeout >/dev/null 2>&1 || timeout() { gtimeout "$@"; }


# Sanity check that we're in the right place, the working directory
# above the website source directory.  This is hard to do in general
# -- the make-check-worker workspace, in particular, doesn't look like
# any of the others -- so we try to catch the most common error, that
# we're inside some git repo or another.
(
    cd "$WORKSPACE_ROOT"
    if git rev-parse 2>/dev/null; then
        echo "$WORKSPACE_ROOT is a git repo, not the workspace dir"
        exit 1
    fi
) || exit 1

# Send an alert to Slack and the logs.  You must have secrets decrypted.
# The alertlib subrepo in webapp must be checked out for this to work.
# $1: severity level
# $2+: message
# If $DEPLOYER_USERNAME is set, then that is prepended to the alert message.
_alert() {
    severity="$1"
    shift
    if echo "$@" | grep -q '<[^ ].*>'; then    # a hack, but a pretty good one
       html=--html
    else
       html=
    fi
    if [ -n "$DEPLOYER_USERNAME" ]; then
        msg="$DEPLOYER_USERNAME: $@"
    else
        msg="$@"
    fi
    echo "$msg" \
        | "$WORKSPACE_ROOT"/jenkins-jobs/alertlib/alert.py \
              --severity="$severity" $html \
              --slack "$SLACK_CHANNEL" --logs
}


# The filename to use as a lock in order to serialize fetches.
# TODO(csilvers): have there be a lock per repo, rather than one
# global lock.  This is tricky with submodules, where you can both
# fetch in them directly and indirectly via a 'git submodule update'.
_flock_file() {
    echo "$REPOS_ROOT/flock.fetch"
}

# Call this from within the repo that you want to do the fetching.
_fetch() {
    # We use flock to protect against two clients trying to fetch in
    # the same dir at the same time.  This is because different
    # clients will both, in the end, be fetching into $REPOS_ROOT.
    timeout 120m flock -w 7230 "`_flock_file`" git fetch --prune --tags --progress origin
}

# Like fetch, but call from the workspace root.
# $1: the repo directory
simple_fetch() {
    ( cd "$1" && _fetch )
}

# $1: the branch we're in.  We assume this branch also exists on the remote.
_rebase() {
    timeout 10m git rebase "origin/$1" || {
        timeout 10m git rebase --abort
        exit 1
    }
}

# $1: the commit-ish to check out to.
# NOTE: this does a bunch of 'git reset --hard's and equivalent.
# Do not call this if you have stuff you want to commit.
_destructive_checkout() {
    if ! timeout 10m git checkout -f "$1" -- ; then
        _alert error "'$1' is not a valid git revision"
        exit 1
    fi
    timeout 1m git clean -ffd
    # No need to init, or resync, or recurse here: we just want to
    # make sure that when we visit changed subrepos, they're at the
    # right version.  (We handle the recursion ourselves below.)
    # However, the first time this is called it might need to check
    # out all the submodules, so we give it a bit of time.
    timeout 10m git submodule update -f

    # Now we need to clean up subrepos.  Most subrepos are usually ok,
    # so I use `git status` to only clean up the ones that need it.  I
    # use `status -z` so git doesn't try to shell-escape for us.
    timeout 10m git status --porcelain -z | tr '\0' '\012' | cut -b4- \
    | while read f; do
        [ -d "$f" ] && ( cd "$f" && _destructive_checkout HEAD )
    done

    # TOOD(csilvers): if any submodules disappeared as part of this
    # `git checkout -f`, and thus submodules had .gitignored files
    # in them (such as .pyc files), then the submodule's `.git` file
    # won't be deleted by `git clean` even though it should be.  It is
    # also invisible to `git status`.  Ideally we should find such
    # `.git` files and nuke them manually.  Or we should consider
    # using `git clean -ffdx` above.
}

# $* (optional): submodules to update.  If left out, update all submodules.
#    If the string 'no_submodules', update no submodules.  Can be a
#    directory, in which case we update all submodules under that dir.
_update_submodules() {
    if [ "$*" = "no_submodules" ]; then
        return
    fi
    # If we ourselves are a submodule, we don't have any submodules to update.
    if git rev-parse --git-dir | fgrep -q .git/modules; then
        return
    fi

    # It's not really safe to call git new-workdir on each submodule,
    # since it doesn't deal well with submodules appearing and
    # disappearing between branches.  So we hard-code a few of the big
    # submodules that have been around a long time and aren't going
    # anywhere, and use git new-workdir on those, and use 'normal'
    # submodules for everything else.
    new_workdir_repos=""
    normal_repos="$*"
    if [ -z "$normal_repos" ]; then        # means 'all the repos'
        normal_repos="`git submodule status | awk '{print $2}'`"
    fi

    if echo "$normal_repos" | grep -e intl -e intl/translations; then
       new_workdir_repos="intl/translations $new_workdir_repos"
       normal_repos="`echo $normal_repos | tr " " "\012" | grep -v intl`"
    fi
    if echo "$normal_repos" | grep -e khan-exercises; then
       new_workdir_repos="khan-exercises $new_workdir_repos"
       normal_repos="`echo $normal_repos | tr " " "\012" | grep -v khan-exercises`"
    fi

    # Handle the repos we (possibly) need to make workdirs for.
    if [ -n "$new_workdir_repos" ]; then
        repo_dir="`pwd`"
        ( flock 9        # use fd 9 for locking (see the end of this paren)
          # Get to the shared repo (inside $REPOS_ROOT).  We follow the
          # existing symlinks inside main_repo/.git/ to get there.
          cd `readlink -f .git/config | xargs -n1 dirname | xargs -n1 dirname`

          timeout 10m git submodule sync --recursive
          timeout 60m git submodule update --init --recursive -- $new_workdir_repos
          for path in $new_workdir_repos; do
              [ -f "$repo_dir/$path/.git" ] || git new-workdir "`pwd`/$path" "$repo_dir/$path"
          done
        ) 9>"`_flock_file`"
    fi

    # Now update the 'normal' repos.
    if [ -n "$normal_repos" ]; then
        timeout 10m git submodule sync --recursive
        timeout 60m git submodule update --init --recursive -- $normal_repos
    fi

    # Finally, we need to fix the submodule HEADs in the workdir.
    timeout 10m git submodule update -- "$@"
}

# Clone the given repo if it doesn't already exist.
# This is just like git clone, except we make a new workdir of the shared
# central repo.
# $1: repo to clone (a la sync_to)
# $2: directory into which to clone it
# $2: commit-ish to check out at.  Note that we don't do submodules
#     or the like; that's up to you.
clone() {
    repo="$1"
    shift
    repo_workspace="$1"
    shift
    commit="$1"
    shift
    (
    if ! [ -d "$repo_workspace" ]; then
        # The git objects/etc live under REPOS_ROOT (all workspaces
        # share the same objects).
        repo_dir="$REPOS_ROOT/`basename "$repo"`"
        # Clone or update into repo-dir, the canonical home.
        if [ -d "$repo_dir" ]; then
            ( cd "$repo_dir" && _fetch )
        else
            timeout 60m git clone "$repo" "$repo_dir"
        fi
        # Now create our workspace!
        timeout 10m git new-workdir "$repo_dir" "$repo_workspace" "$commit"
    fi
    )
}


# checks out the given commit-ish, fetching (or cloning) first.
# The repo is always checked out under $WORKSPACE_ROOT and there
# is no way to specially set the directory name.
# $1: repo to clone
# $2: commit-ish to check out at.  If necessary, does a pull from
#     origin first.
# $3+ (optional): submodules to update to that commit as well.  If
#     left out, update all submodules.  If the string 'no_submodules',
#     update no submodules.
sync_to() {
    repo="$1"
    shift
    commit="$1"
    shift
    (
    repo_workspace="$WORKSPACE_ROOT/`basename "$repo"`"
    if [ -d "$repo_workspace" ]; then
        cd "$repo_workspace"
        _fetch
        _destructive_checkout "$commit"
    else
        clone "$repo" "$repo_workspace" "$commit"
        cd "$repo_workspace"
    fi

    # Merge from origin if need be.
    if timeout 10m git ls-remote --exit-code . origin/"$commit"; then
        _rebase "$commit"
    fi

    _update_submodules "$@"
    )
}

# Like sync_to, but if the commit-ish exists on origin -- e.g.
# it's a branch that's been pushed to github -- sync to origin/commit
# and set commit to be that.  This is useful when we only care about
# what exists on github, because no local changes are expected.
# $1: repo to clone
# $2: commit-ish to check out at.  If origin/commit-ish exists,
#     sync to that instead of commit-ish.  (This is usually true,
#     especially when commit-ish is a branch name.)
# $3+ (optional): submodules to update to that commit as well.  If
#     left out, update all submodules.  If the string 'no_submodules',
#     update no submodules.
sync_to_origin() {
    repo="$1"
    shift
    commit="$1"
    shift

    repo_workspace="$WORKSPACE_ROOT/`basename "$repo"`"
    if timeout 10m \
       git ls-remote --exit-code "$repo_workspace" origin/"$commit"; then
        orig_commit="$commit"    # sync_to overwrites '$commit', ugh
        sync_to "$repo" "origin/$commit" "$@"
        # Make it so our local branch matches what's on origin
        (
            cd "$repo_workspace"
            git branch -f "$orig_commit" origin/"$orig_commit"
            git checkout "$orig_commit"
        )
    else
        sync_to "$repo" "$commit" "$@"
    fi
}

# $1: directory to run the pull in (can be in a sub-repo)
# $2: branch to pull
# $3+ (optional): submodules to pull as well.  If left out, update all
#     submodules.  If the string 'no_submodules', update no submodules.
# NOTE: this does a git reset, and always changes the branch to master!
# It also always inits and updates listed subrepos.
pull_in_branch() {
    (
    cd "$1"
    shift
    branch="$1"
    shift
    _destructive_checkout "$branch"
    _fetch
    _rebase "$branch"
    _update_submodules "$@"
    )
}

# Does a pull after switching to the 'master' branch.
# $1: directory to run the pull in (can be in a sub-repo)
pull() {
    dir="$1"
    shift
    pull_in_branch "$dir" "master" "$@"
}

# $1: directory to run the push in (can be in a sub-repo)
push() {
    (
    cd "$1"
    branch=`git rev-parse --symbolic-full-name HEAD | sed 's,^.*/,,'`
    # In case there have been any changes since the script began, we
    # do 'pull; push'.  On failure, we undo all our work.
    _fetch
    _rebase "$branch" || {
        timeout 10m git reset --hard HEAD^
        exit 1
    }
    _update_submodules

    # Ensure we push using SSH to use Jenkins' configured SSH keys.
    ssh_origin=`git config --get remote.origin.url | sed 's,^https://github.com/,git@github.com:,'`
    timeout 60m git push "$ssh_origin" "$branch" || {
        timeout 10m git reset --hard HEAD^
        exit 1
    }
    )
}

# This updates our repo to point to the current master of the given subrepo.
# $1: the directory of the repository
# $2: the directory of the submodule relative to "$1"
update_submodule_pointer_to_master() {
    (
    cd "$1"
    shift
    dir="$1"
    shift
    branch=`git rev-parse --symbolic-full-name HEAD | sed 's,^.*/,,'`
    pull_in_branch . "$branch"
    ( cd "$dir" && timeout 10m git checkout master )
    timeout 10m git add "$dir"
    if git commit --dry-run | grep -q -e 'no changes added' -e 'nothing to commit' -e 'nothing added'; then
        echo "No need to update substate for $dir: no new content created"
    else
        timeout 10m git commit -m "$dir substate [auto]"
        push .
    fi
    )
}


# $1: the directory to commit in (can be in a sub-repo)
# $2+: arguments to 'git commit'
# If "$1" is a sub-repo, this function *must* be called from within
# the main repo that includes the sub-repo.
# NOTE: you must be sure to `git add` any new files first!
commit_and_push() {
    dir="$1"
    shift
    (
    cd "$dir"
    if [ -z "`git status --porcelain | head -n 1`" ]; then
        echo "No changes, skipping commit"
    else
        timeout 10m git commit "$@"
    fi
    )
    push "$dir"
}

# $1: the directory of the main repository
# $2: the directory of the submodule relative to "$1"
# $3+: arguments to 'git commit'
commit_and_push_submodule() {
    repo_dir=$1
    shift
    submodule_dir=$1
    shift
    commit_and_push "$repo_dir/$submodule_dir" "$@"

    update_submodule_pointer_to_master "$repo_dir" "$submodule_dir"
}


# Merge one branch into another.
# $1: the directory to run the merge in
# $2: the commit-ish (branch or sha1) into which to merge the other branch.
#     If it's a sha1, it must be a superset of "branch" ($3).  We check out
#     $2 in the working dir $1 in order to do the merge.
# $3: the commit-ish (branch or sha1) to merge into $2.
# $4+ (optional): submodules to update after the merge.  If left out, update
#     all submodules.  If the string 'no_submodules', update no submodules.
# If $2 is not HEAD, then we push it to master
# TODO(benkraft): wrap this whole assembly in failure-handling, so that if
# something unexpected fails, at least it doesn't fail silently.
merge_from_branch() {
    dir="$1"
    shift
    merge_into="$1"
    shift
    merge_from="$1"
    shift
    (
    cd "$dir"

    # If merge-from is a commit-ish, we'll use the version at origin.
    if git ls-remote --exit-code . "origin/$merge_from"; then
        git fetch origin "+refs/heads/$merge_from:refs/remotes/origin/$merge_from"
        merge_from="origin/$merge_from"
    fi

    # Set our local merge_into to be the same as the origin merge_into.  This
    # is needed in cases when a previous deploy set the local (jenkins)
    # merge_into to commit X, but subsequent commits have moved the remote
    # (github) version of the merge_into to commit Y.  This also moves us
    # from a (potentially) detached-head state to a head-at-branch state.
    # Finally, it makes sure the ref exists locally, so we can do
    # 'git rev-parse branch' rather than 'git rev-parse origin/branch'
    # (though only if we're given a branch rather than a commit as $2).
    if [ "$merge_into" != "HEAD" ] && \
           git ls-remote --exit-code . "origin/$merge_into"; then
        git fetch origin "+refs/heads/$merge_into:refs/remotes/origin/$merge_into"
        # The '--' is needed if merge_into is both a branch and
        # directory, e.g. 'sat'.  '--' says 'treat it as a branch'.
        git checkout "$merge_into" --
        git reset --hard "origin/$merge_into"
    else
        if ! git checkout "$merge_into" -- ; then
            _alert error "'$merge_into' is not a valid git revision"
            exit 1
        fi
    fi

    head_commit="`git rev-parse HEAD`"
    merge_from_commit="`git rev-parse "$merge_from"`"

    # Sanity check: HEAD should be at the revision we want to deploy from.
    if [ "$head_commit" != "`git rev-parse "$merge_into"`" ]; then
        _alert error "HEAD unexpectedly at '$head_commit', not '$merge_into'"
        exit 1
    fi

    # If the current commit is a super-set of merge_from, we're done, yay!
    base="`git merge-base "$merge_into" "$merge_from_commit"`"
    if [ "$base" = "$merge_from_commit" ]; then
        echo "$merge_into is a superset of $merge_from, no need to merge"
        exit 0
    fi

    # The merge exits with rc > 0 if there were conflicts
    echo "Merging $merge_from into $merge_into"
    if ! git merge "$merge_from"; then
        git merge --abort
        _alert error "Merge conflict: must merge $merge_from into $merge_into manually."
        exit 1
    fi

    # There's a race condition if someone commits to this branch while
    # this script is running, so check for that.
    if [ "$merge_into" != "HEAD" ]; then
        if ! git push origin "$merge_into"; then
            git reset --hard "$head_commit"
            _alert error "Someone committed to $merge_into while we've been deploying!"
            exit 1
        fi
    fi

    _update_submodules "$@"

    echo "Done merging $merge_from into $merge_into"
    )
}

# Checks out a branch and merges master into it
merge_from_master() {
    dir="$1"
    shift
    git_revision="$1"
    shift
    merge_from_branch "$dir" "$git_revision" "master" "$@"
}


[ -n "$1" ] || { echo "USAGE: $0 <command> <args>"; exit 1; }
"$@"
