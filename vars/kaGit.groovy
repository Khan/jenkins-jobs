// A wrapper around build.lib, providing "safe" checkout tools for repos.
// This should be run from a workspace that has checked out jenkins-tools.

// Turn a list of submodules into arguments to pass to build.lib functions.
// Submodules is the empty list (default) for "clone all submodules".
// Submodules is null for "clone no submodules".
// Otherwise it's a list of prefixes; we clone all submodules matching
//     some prefix.
def _submodulesArg(submodules) {
   if (submodules == null) {
      return 'no_submodules';
   } else {
      return submodules.join(' ');
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

// Submodules is as in _submodulesArg.
def safeSyncTo(repoToClone, commit, submodules=[]) {
   sh("jenkins-tools/build.lib safe_sync_to " +
      "${repoToClone} ${commit} ${_submodulesArg(submodules)}");
}

// Submodules is as in _submodulesArg.
def safeSyncToOrigin(repoToClone, commit, submodules=[]) {
   sh("jenkins-tools/build.lib safe_sync_to_origin " +
      "${repoToClone} ${commit} ${_submodulesArg(submodules)}");
}


// Returns True iff the repo in the given subdirectory is at the
// given git commit-ish.  Note it does *not* check that the
// workspace is clean or anything like that, just that rev-parse
// matches.  TODO(csilvers): do this automatically in build.lib instead.
def isAtCommit(subdir, commit) {
   dir(subdir) {
      def rc = sh(script: ("[ \"`git rev-parse HEAD`\" = " +
                           "\"`git rev-parse '${commit}'`\" ]"),
                  returnStatus: true)
      return rc == 0;
   }
}
