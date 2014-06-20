#!/bin/sh -xe

# Given an argument that is either the name of a branch or a different
# kind of commit-ish (sha1, tag, etc), does two things:
#
# 1) Ensures that HEAD matches that argument -- that is, that you're
#    checked out where you expect to be -- and then does a
#    git checkout <branch> so we are no longer in a detached-head
#    state.
#
# 2) Check if the input sha1 is a superset of master (that is,
#    everything in master is part of this sha1's history too).
#    If not:
# 2a) If the argument is a branch-name, merge master into the branch.
# 2b) If the argument is another commit-ish, fail.

die() {
    echo "FATAL ERROR: $@"
    exit 1
}

[ -n "$1" ] || {
    die "USAGE: $1 <branch-name or other commit-ish, should equal HEAD>"
}

git_branch_or_commit="$1"
master_commit=`git rev-parse origin/master`
head_commit=`git rev-parse HEAD`

# This is a no-op in terms of where we point, but it may move us from
# a detached-head state to a head-at-branch state, and will definitely
# make sure the ref exists locally (so we can do 'git rev-parse
# branch' rather than needing 'git rev-parse origin/branch').
git checkout "$git_branch_or_commit"
# This may fail if it's a commit and not a branch; that's ok.
git pull origin "$git_branch_or_commit" || true

[ "$git_branch_or_commit" != "master" ] || {
    die "You must deploy from a branch; you can't deploy from master."
}

# Make sure that HEAD and branch-name are the same.
[ "$head_commit" = "`git rev-parse $git_branch_or_commit`" ] || {
    die "HEAD unexpectedly at `git rev-parse HEAD`, not $git_branch_or_commit"
}

# If the current commit is a super-set of master, we're done, yay!
if [ "`git merge-base $git_branch_or_commit $master_commit`" = "$master_commit" ]; then
    exit 0
fi

# Now we need to merge master into our branch.  First, make sure we
# *are* a branch.
git show-ref | grep -q refs/remotes/origin/$git_branch_or_commit || {
    die "$git_branch_or_commit is not a branch name on the remote, like these:" \
        "`git show-ref | grep refs/remotes/origin/`"
}

echo "Merging master into $git_branch_or_commit"
pre_merge_commit=`git rev-parse HEAD`

# The merge exits with rc > 0 if there were conflicts
git merge origin/master || {
    git merge --abort
    die "Merge conflict: must merge master into $git_branch_or_commit manually"
}
# There's a race condition if someone commits to this branch while
# this script is running, so check for that.
git push origin "$git_branch_or_commit" || {
    git reset --hard "$pre_merge_commit"   # undo the merge
    die "Someone committed to $git_branch while we've been deploying!"
}
echo "Done merging master into $git_branch_or_commit"
