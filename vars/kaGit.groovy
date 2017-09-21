// A wrapper around safe_git.sh, providing "safe" checkout tools for repos.
// This should be run from a workspace that has checked out jenkins-jobs.
// You should always run kaGit commands from workspace-root.

// We use these user-defined steps from vars/:
//import vars.exec
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
      git(url: "https://github.com/Khan/jenkins-jobs",
          changelog: false, poll: false);
      // The built-in 'git' step is not great: it doesn't do
      // submodules, and it messes up the tracking with the remote.
      sh("git submodule update --init --recursive");
      sh("git branch --set-upstream-to origin/master master");
   }
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
         sha1 = lsRemoteOutput.split("\t")[0];
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
// the given commit using safeSyncTo or safeSyncToOrigin.
def wasSyncedTo(repo, commit, syncFn) {
   try {
      actual = readFile(_buildTagFile(repo));
      return actual == "${syncFn} ${commit} ${env.BUILD_TAG}";
   } catch (e) {
      return false;     // build_tag file doesn't exist.
   }
}

// Submodules is as in _submodulesArg`.
// Unless `force` is True, we are a noop if that dir is already synced
// to the given commit *by this same jenkins job*.
def safeSyncTo(repoToClone, commit, submodules=[], force=false) {
   if (!force && wasSyncedTo(repoToClone, commit, "safeSyncTo")) {
      return;
   }
   exec(["jenkins-jobs/safe_git.sh", "sync_to", repoToClone, commit] +
        _submodulesArg(submodules));
   // Document who synced this repo and to where, for future reference.
   writeFile(file: _buildTagFile(repoToClone),
             text: "safeSyncTo ${commit} ${env.BUILD_TAG}");
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
// args are the arguments to git commit (we add '-a' automatically).
def safeCommitAndPush(dir, args) {
   // Automatic commits from jenkins don't need a test plan.
   withEnv(["FORCE_COMMIT=1"]) {
      exec(["jenkins-jobs/safe_git.sh", "commit_and_push", dir] + args);
   }
}

// repoDir is the root of the repo that holds the submodule.
// submoduleDir is the dir of the submodule, relative to repodDir.
// args are the arguments to git commit (we add '-a' automatically).
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
   // so it needs secrets.
   withSecrets() {
      exec(["jenkins-jobs/safe_git.sh", "merge_from_branch",
            dir, commitToMergeInto, branchToMerge]
           + _submodulesArg(submodules));
   }
}


// Submodules is as in _submodulesArg`.
def safeMergeFromMaster(dir, commitToMergeInto, submodules=[]) {
   // This job talks directly to slack on error (for better or worse),
   // so it needs secrets.
   withSecrets() {
      exec(["jenkins-jobs/safe_git.sh", "merge_from_master",
            dir, commitToMergeInto] + _submodulesArg(submodules));
   }
}
