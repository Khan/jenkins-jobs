// A wrapper around safe_git.sh, providing "safe" checkout tools for repos.
// This should be run from a workspace that has checked out jenkins-jobs.
// You should always run kaGit commands from workspace-root.

// We use these user-defined steps from vars/:
//import vars.exec
//import vars.notify
//import vars.withSecrets

// Turn a list of submodules into arguments to pass to safe_git.sh functions.
// Submodules is the empty list (default) for "clone all submodules".
// Submodules is null for "clone no submodules".
// Otherwise it's a list of prefixes; we clone all submodules matching
//     some prefix.
def _submodulesArg(submodules) {
   if (submodules == null) {
      return ['no_submodules'];
   } else {
      return submodules;
   }
}

// Normally we check out repos using our own `safe-git` scripts, but
// those exist in the jenkins-jobs repo so we can't use that script
// for that repo.  This is the command that bootstraps checking out
// jenkins-jobs so we can then check out other repos.
// This is typically not called manually, but instead by onMaster/etc.
def checkoutJenkinsTools() {
   // TODO(csilvers): only do this once per workspace per script.
   dir("jenkins-jobs") {
      // we see a lot of connection timeout to github:433
      // before we figure out the root cause, we use retry {}
      // to tempoary avoid timeout error.
      // TODO(Kai): need to find out the root cause of timeout
       retry {
         git(url: "https://github.com/Khan/jenkins-jobs",
            changelog: false, poll: false);
         // The built-in 'git' step is not great: it doesn't do
         // submodules, and it messes up the tracking with the remote.
         sh("git submodule update --init --recursive");
         sh("git branch --set-upstream-to origin/master master");
       }
   }
}

// Helper function that sorts a list of string by their length.
// This is needed to correctly figure the branch name from a
// hash.  See resolveCommitish for more details.
// https://stackoverflow.com/questions/78348144/how-to-sort-array-with-values-property-in-jenkins-groovy
// https://www.jenkins.io/doc/book/pipeline/cps-method-mismatches/
@NonCPS  // for list.sort
def _sortBySize(l) {
    l.sort { it.size() }
}

// Turn a commit-ish into a sha1.  If a branch name, we assume the
// branch exists on the remote and get the sha1 from there.  Otherwise
// if the input looks like a sha1 we just return it verbatim.
// Otherwise we error.
def resolveCommitish(repo, commit) {
   def sha1 = null;
   stage("Resolving commit") {
      timeout(1) {
         def lsRemoteOutput = exec.outputOf(["git", "ls-remote", "-q",
                                             repo, commit]);
         // There could be more than one match: e.g. searching for `john`
         // matches both `refs/head/john` and `refs/head/deploy/john`.
         // We take the shortest match, which is the most exact match.
         // TODO(csilvers): verify that the shortest match is actually
         // what was asked for, if the input is a tag or branch.  Otherwise
         // if you have two branches named `foo/suffix` and `bar/suffix`
         // but no branch named `suffix`, this will silently return
         // `bar/suffix` rather than giving a "branch not found" error.
         lines = _sortBySize(lsRemoteOutput.split("\n"));
         sha1 = lines[0].split("\t")[0];
      }
   }
   if (sha1) {
      echo("'${commit}' resolves to ${sha1}");
      return sha1;
   }
   // If this looks like a sha1 already, return it.
   // TODO(csilvers): complain to slack?
   if (commit ==~ /[0-9a-fA-F]{5,}/) {
      return commit;
   }
   error("Cannot find '${commit}' in repo '${repo}'");
}

def _buildTagFile(repo) {
   // `repo` is something like `git@github.com:Khan/webapp`.
   return "build_tag.${repo.split('/')[-1]}";
}

// true iff the current job had previously synced the given repo to
// the given commit using safeSyncToOrigin.
def wasSyncedTo(repo, commit, syncFn) {
   if (!fileExists(_buildTagFile(repo))) {
      return false;
   }
   try {
      actual = readFile(_buildTagFile(repo));
      return actual == "${syncFn} ${commit} ${env.BUILD_TAG}";
   } catch (e) {
      notify.rethrowIfAborted(e);
      return false;     // build_tag file was just deleted.
   }
}

// Clone a repo, using our existing workdirs if possible.
// This is very low-level, and does not update submodules!  You probably want
// safeSyncToOrigin, below, instead.
def quickClone(repoToClone, directory, commit) {
   exec(["jenkins-jobs/safe_git.sh", "clone", repoToClone, directory, commit]);
}

// Fetch a repo, with locking.
// This is very low-level, and does not update submodules!  You probably want
// safeSyncToOrigin, below, instead.
def quickFetch(directory) {
   exec(["jenkins-jobs/safe_git.sh", "simple_fetch", directory]);
}

// Submodules is as in _submodulesArg.
// Unless `force` is True, we are a noop if that dir is already synced
// to the given commit *by this same jenkins job*.
def safeSyncToOrigin(repoToClone, commit, submodules=[], force=false) {
   if (!force && wasSyncedTo(repoToClone, commit, "safeSyncToOrigin")) {
      return;
   }
   exec(["jenkins-jobs/safe_git.sh", "sync_to_origin", repoToClone, commit] +
        _submodulesArg(submodules));
   writeFile(file: _buildTagFile(repoToClone),
             text: "safeSyncToOrigin ${commit} ${env.BUILD_TAG}");
}

// dir is the directory to run the pull in (can be in a sub-repo)
def safePull(dir) {
   exec(["jenkins-jobs/safe_git.sh", "pull", dir]);
}

// dir is the directory to run the pull in (can be in a sub-repo)
// branch is the branch to pull.  Submodules is as in _submodulesArg`.
def safePullInBranch(dir, branch, submodules=[]) {
   exec(["jenkins-jobs/safe_git.sh", "pull_in_branch", dir, branch] +
        _submodulesArg(submodules));
}

// dir is the directory to commit in (*cannot* be a submodule).
// args are the arguments to git commit.
def safeCommitAndPush(dir, args) {
   // Automatic commits from jenkins don't need a test plan.
   withEnv(["FORCE_COMMIT=1"]) {
      exec(["jenkins-jobs/safe_git.sh", "commit_and_push", dir] + args);
   }
}

// repoDir is the root of the repo that holds the submodule.
// submoduleDir is the dir of the submodule, relative to repodDir.
// args are the arguments to git commit.
def safeCommitAndPushSubmodule(repoDir, submoduleDir, args) {
   // Automatic commits from jenkins don't need a test plan.
   withEnv(["FORCE_COMMIT=1"]) {
      exec(["jenkins-jobs/safe_git.sh", "commit_and_push_submodule",
            repoDir, submoduleDir] + args);
   }
}

// repoDir is the root of the repo that holds the submodule.
// submoduleDir is the dir of the submodule, relative to repoDir.
def safeUpdateSubmodulePointerToMaster(repoDir, submoduleDir) {
    // Automatic commits from jenkins don't need a test plan.
    withEnv(["FORCE_COMMIT=1"]) {
        exec(["jenkins-jobs/safe_git.sh", "update_submodule_pointer_to_master",
              repoDir, submoduleDir]);
    }
}

// Submodules is as in _submodulesArg`.
def safeMergeFromBranch(dir, commitToMergeInto, branchToMerge, submodules=[]) {
   // This job talks directly to slack on error (for better or worse),
   // so it needs that secret.
   withSecrets.slackAlertlibOnly() {
      exec(["jenkins-jobs/safe_git.sh", "merge_from_branch",
            dir, commitToMergeInto, branchToMerge]
           + _submodulesArg(submodules));
   }
}


// Submodules is as in _submodulesArg`.
def safeMergeFromMaster(dir, commitToMergeInto, submodules=[]) {
   // This job talks directly to slack on error (for better or worse),
   // so it needs that secret.
   withSecrets.slackAlertlibOnly() {
      exec(["jenkins-jobs/safe_git.sh", "merge_from_master",
            dir, commitToMergeInto] + _submodulesArg(submodules));
   }
}


// Merges multiple branches together into a single commit.
//
// Arguments:
// - gitRevisions: string containing one or more branche names separated by "+"
// - tagName: string to tag the resulting commit with.  We need to tag the
//   result of the merge so git doesn't prune it.
//
// Notes:
// - Used by merge-granches.groovy and deploy-znd.groovy.
def mergeBranches(gitRevisions, tagName) {
   def allBranches = gitRevisions.split(/\+/);
   quickClone("git@github.com:Khan/webapp", "webapp",
                    allBranches[0].trim());
   dir('webapp') {
      // We need to reset before fetching, because if a previous incomplete
      // merge left .gitmodules in a weird state, git will fail to read its
      // config, and even the fetch can fail.  This also avoids certain
      // post-merge-conflict states where git checkout -f doesn't reset as much
      // as you might think.
      exec(["git", "reset", "--hard"]);
   }
   // Get rid of all old branches; if they were dangling they'd break fetch.
   exec(["jenkins-jobs/safe_git.sh", "clean_branches", "webapp"]);
   quickFetch("webapp");
   dir('webapp') {
      for (def i = 0; i < allBranches.size(); i++) {
         def branchSha1 = resolveCommitish("git@github.com:Khan/webapp",
                                           allBranches[i].trim());
         try {
            if (i == 0) {
               // TODO(benkraft): If there's only one branch, skip the checkout
               // and tag/return sha1 immediately.
               // Note that this is a no-op when we did a fresh clone above.
               exec(["git", "checkout", "-f", branchSha1]);
            } else {
               // TODO(benkraft): This puts the sha in the commit message
               // instead of the branch; we should just write our own commit
               // message.
               exec(["git", "merge", branchSha1]);
            }
         } catch (e) {
            notify.rethrowIfAborted(e);
            // TODO(benkraft): Also send the output of the merge command that
            // failed.
            notify.fail("Failed to merge ${branchSha1} into " +
                        "${allBranches[0..<i].join(' + ')}: ${e}");
         }
      }
      // We need to at least tag the commit, otherwise github may prune it.
      // (We can skip this step if something already points to the commit; in
      // fact we want to to avoid Phabricator paying attention to this commit.)
      // These tags ar pruned weekly in weekly-maintenance.sh.
      if (exec.outputOf(["git", "tag", "--points-at", "HEAD"]) == "" &&
          exec.outputOf(["git", "branch", "-r", "--points-at", "HEAD"]) == "") {
         exec(["git", "tag", tagName, "HEAD"]);
         exec(["git", "push", "--tags", "origin"]);
      }
      def sha1 = exec.outputOf(["git", "rev-parse", "HEAD"]);
      echo("Resolved ${gitRevisions} --> ${sha1}");
      return sha1;
   }
}
