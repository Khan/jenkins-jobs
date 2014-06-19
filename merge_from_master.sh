#!/bin/sh

# If the current revision that we're on is not already a superset of
# 'master', merge master in via rebasing.  If this is not a trivial
# merge, abort.  If the current revision is not the head of a branch,
# (that is, 'git status' shows 'no branch'), also abort, since merging
# in that situation is probably not a good idea.

git_commit=`git rev-parse HEAD`
master_commit=`git rev-parse master`

die() {
    echo "FATAL ERROR: $@"
    exit 1
}

# Make sure that $git_commit is a sha1 pointing to the head of
# a branch.
git_branch=`git show-ref --heads | grep -e "^$git_commit " | cut -d/ -f3`
[ -z "$git_branch" ] && {
    echo "The git commit '$git_commit' is not the head of a branch"
    echo "These are the heads we know about:"
    git show-ref --heads
    die "The git commit '$git_commit' is not the head of a branch"
}

[ "$git_branch" = "master" ] && {
    die "You must deploy from a branch; you can't deploy from master"
}

# If the current commit is not a super-set of master, try merging master in.
if [ `git merge-base $git_commit $master_commit` != $master_commit ]; then
    echo "Merging master into $git_branch"
    git checkout "$git_branch"     # we were a detached commit
    # The merge exits with rc > 0 if there were conflicts
    git merge master || {
        git merge --abort
        die "Merge conflicts: must merge master into $git_branch manually"
    }
    # There's a race condition if someone commits to this branch while
    # this script is running, but oh well.
    git push || {
        git reset --hard "$git_commit"   # undo the merge
        die "Someone committed to $git_branch while we've been deploying!"
    }
    echo "Done merging master into $git_branch"
fi
