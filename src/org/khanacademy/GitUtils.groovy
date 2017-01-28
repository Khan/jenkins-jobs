// A wrapper around build.lib, providing "safe" checkout tools for repos.
// This should be run from a workspace that has checked out jenkins-tools.

package org.khanacademy;


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

// Submodules is as in _submodulesArg.
def safeSyncTo(repoToClone, commit, submodules=[]) {
   sh ("jenkins-tools/build.lib safe_sync_to " +
       "${repoToClone} ${commit} ${_submodulesArg(submodules)}");
}

// Submodules is as in _submodulesArg.
def safeSyncToOrigin(repoToClone, commit, submodules=[]) {
   sh ("jenkins-tools/build.lib safe_sync_to_origin " +
       "${repoToClone} ${commit} ${_submodulesArg(submodules)}");
}
