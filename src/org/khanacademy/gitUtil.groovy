// This should be run from a workspace that has checked out jenkins-tools.


// Submodules is the empty list (default) for "clone all submodules".
// Submodules is null for "clone no submodules".
// Otherwise it's a list of prefixes; we clone all submodules matching
//     some prefix.
def safeSyncToOrigin(repoToClone, commit, submodules=[]) {
    def submodulesString = '';
    if (submodules == null) {
       submodulesString = 'no-submodules';
    } else {
       submodulesString = submodules.join(' ');
    }
    sh 'jenkins-tools/build.lib ${repoToClone} ${commit} ${submodulesString}';
}
