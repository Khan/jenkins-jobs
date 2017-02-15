// A wrapper around build.lib, providing "safe" checkout tools for repos.
// This should be run from a workspace that has checked out jenkins-tools.

// Convert a list of args into a string that you can invoke from the shell.
def _shellEscape(lst) {
   def retval = "";
   // We have to use C-style iterators in jenkins pipeline scripts.
   for (i = 0; i < lst.size(); i++) {
      retval += "'" + lst.replace("'", "'\\''") + "' ";
   }
   return retval
}

// Turn a list of submodules into arguments to pass to build.lib functions.
// Submodules is the empty list (default) for "clone all submodules".
// Submodules is null for "clone no submodules".
// Otherwise it's a list of prefixes; we clone all submodules matching
//     some prefix.
def _submodulesArg(submodules) {
   if (submodules == null) {
      return 'no_submodules';
   } else {
      return _shellEscape(submodules);
   }
}

// Normally we check out repos using our own `safe-git` scripts, but
// those exist in the jenkins-tools repo so we can't use that script
// for that repo.  This is the command that bootstraps checking out
// jenkins-tools so we can then check out other repos.
// This is typically not called manually, but instead by onMaster/etc.
def checkoutJenkinsTools() {
   // TODO(csilvers): only do this once per workspace per script.
   dir("jenkins-tools") {
      git(url: "https://github.com/Khan/jenkins-tools",
          changelog: false, poll: false);
      // The built-in 'git' step is not great: it doesn't do
      // submodules, and it messes up the tracking with the remote.
      sh("git submodule update --init --recursive");
      sh("git branch --set-upstream-to origin/master master");
   }
}

def _buildTagFile(repo) {
   // `repo` is something like `git@github.com:Khan/webapp`.
   return "build_tag.${repo.split('/')[-1]}";
}

// true iff the current job had previously synced the given repo to
// the given commit using safeSync or safeSyncToOrigin.
def wasSyncedTo(repo, commit, syncFn) {
   try {
      actual = readFile(_buildTagFile(repo));
      return actual == "${syncFn} ${commit} ${env.BUILD_TAG}";
   } catch (e) {
      return false;     // build_tag file doesn't exist.
   }
}

// Submodules is as in _submodulesArg.
// Unless `force` is True, we are a noop if that dir is already synced
// to the given commit *by this same jenkins job*.
def safeSyncTo(repoToClone, commit, submodules=[], force=false) {
   if (!force && wasSyncedTo(repoToClone, commit, "safeSyncTo")) {
      return;
   }
   sh("jenkins-tools/build.lib safe_sync_to " +
      "${repoToClone} ${commit} ${_submodulesArg(submodules)}");
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
   sh("jenkins-tools/build.lib safe_sync_to_origin " +
      "${repoToClone} ${commit} ${_submodulesArg(submodules)}");
   writeFile(file: _buildTagFile(repoToClone),
             text: "safeSyncToOrigin ${commit} ${env.BUILD_TAG}");
}

// dir is the directory to run the pull in (can be in a sub-repo)
def safePull(dir) {
   sh("jenkins-tools/build.lib safe_pull ${dir}");
}

// dir is the directory to commit in (can be in a sub-repo)
// args are the arguments to git commit (we add '-a' automatically).
def safeCommitAndPush(dir, args) {
   sh("jenkins-tools/build.lib safe_commit_and_push " +
      "${dir} ${_shellEscape(args)}");
}
