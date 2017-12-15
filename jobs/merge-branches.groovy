// Merge a number of branches of webapp, and push the merge commit to github.
//
// This is used by the buildmaster -- such that the rest of Jenkins, for the
// most part, can only ever worry about fixed SHAs, and never have to worry
// about making the right merge commit or letting the buildmaster know about
// it.

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.buildmaster
//import vars.exec
//import vars.kaGit
//import vars.notify
//import vars.withTimeout

new Setup(steps
).addStringParam(
   "GIT_REVISIONS",
   """<b>REQUIRED</b>. A plus-separated list of commit-ishes to merge, like
"master + yourbranch + mybranch + sometag + deadbeef1234".""",
   ""
).addStringParam(
   "COMMIT_ID",
   """<b>REQUIRED</b>. The buildmaster's commit ID for the commit we will
create.""",
   ""
).apply();

currentBuild.displayName = "${currentBuild.displayName} (${params.COMMIT_ID}: ${params.GIT_REVISIONS})";


def checkArgs() {
   if (!params.GIT_REVISIONS) {
      notify.fail("The GIT_REVISIONS parameter is required.");
   } else if (!params.COMMIT_ID) {
      notify.fail("The COMMIT_ID parameter is required.");
   }
}


def mergeBranches() {
   // We don't use kaGit for many of the ops here, and use lower-level ops
   // where we do. We can afford this because we don't need to update
   // submodules at each step, and we don't need a fully clean checkout.  All
   // we need is enough to merge.  This saves us a *lot* of time traversing all
   // the submodules on each branch, and being careful to clean at each step.
   kaGit.quickClone("git@github.com:Khan/webapp", "webapp",
                    params.GIT_REVISION);
   dir('webapp') {
      def allBranches = params.GIT_REVISIONS.split(/\+/);
      exec(["git", "fetch", "--prune", "--tags", "--progress", "origin"]);
      for (def i = 0; i < allBranches.size(); i++) {
         def sha1 = kaGit.resolveCommitish("git@github.com:Khan/webapp",
                                           allBranches[i].trim());
         if (i == 0) {
            // TODO(benkraft): If there's only one branch, skip the checkout and
            // tag/return sha1 immediately.
            exec(["git", "checkout", "-f", sha1]);
         } else {
            exec(["git", "merge", sha1]);
         }
      }
      // We need to at least tag the commit, otherwise github may prune it.
      // TODO(benkraft): Prune these tags eventually.
      tag_name = ("buildmaster-${params.DEPLOY_ID}-" +
                  "${new Date().format('yyyyMMdd-HHmmss')}");
      exec(["git", "tag", tag_name, "HEAD"]);
      exec(["git", "push", "--tags", "origin"]);
      echo("Resolved ${params.GIT_REVISIONS} --> ${sha1}");
      return sha1;
   }
}

// TODO(benkraft): Update channel when we are done testing.
notify([slack: [channel: '#bot-testing',
                sender: 'Mr Monkey',
                emoji: ':monkey_face:',
                when: ['SUCCESS', 'FAILURE', 'UNSTABLE', 'ABORTED']],
        aggregator: [initiative: 'infrastructure',
                     when: ['SUCCESS', 'BACK TO NORMAL',
                            'FAILURE', 'ABORTED', 'UNSTABLE']],
        timeout: "5m"]) {
   try {
      withTimeout('5m') {
         def sha1 = mergeBranches();
         buildmaster.notifyMergeResult(params.COMMIT_ID, 'success', sha1);
      }
   } catch (e) {
      // We don't really care about the difference between aborted and failed;
      // we can't use notify because we want somewhat special semantics; and
      // without all the things notify does it's hard to tell the difference
      // between aborted and failed.  So we don't bother.
      buildmaster.notifyMergeResult(params.COMMIT_ID, 'failed', null);
   }
}
