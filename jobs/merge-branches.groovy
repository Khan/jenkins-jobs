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

).allowConcurrentBuilds(

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

).addStringParam(
   "SLACK_CHANNEL",
   "The slack channel to which to send failure alerts.",
   "#1s-and-0s-deploys"

).addStringParam(
   "SLACK_THREAD",
   """The slack thread (must be in SLACK_CHANNEL) to which to send failure
alerts.  By default we do not send in a thread.  Generally only set by the
buildmaster, to the 'thread_ts' or 'timestamp' value returned by the Slack
API.""", ""

).addStringParam(
  "JOB_PRIORITY",
  """The priority of the job to be run (a lower priority means it is run
sooner). The Priority Sorter plugin reads this parameter in to reorder jobs
in the queue accordingly. Should be set to 3 if the job is depended on by
the currently deploying branch, otherwise 6. Legal values are 1
through 11. See https://jenkins.khanacademy.org/advanced-build-queue/
for more information.""",
  "6"

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
   def allBranches = params.GIT_REVISIONS.split(/\+/);
   kaGit.quickClone("git@github.com:Khan/webapp", "webapp",
                    allBranches[0].trim());
   dir('webapp') {
      // We need to reset before fetching, because if a previous incomplete
      // merge left .gitmodules in a weird state, git will fail to read its
      // config, and even the fetch can fail.  This also avoids certain
      // post-merge-conflict states where git checkout -f doesn't reset as much
      // as you might think.
      exec(["git", "reset", "--hard"]);
   }
   kaGit.quickFetch("webapp");
   dir('webapp') {
      for (def i = 0; i < allBranches.size(); i++) {
         def branchSha1 = kaGit.resolveCommitish("git@github.com:Khan/webapp",
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
            // TODO(benkraft): Also send the output of the merge command that
            // failed.
            notify.fail("Failed to merge ${branchSha1} into " +
                        "${allBranches[0..<i].join(' + ')}: ${e}");
         }
      }
      // We need to at least tag the commit, otherwise github may prune it.
      // (We can skip this step if something already points to the commit; in
      // fact we want to to avoid Phabricator paying attention to this commit.)
      // TODO(benkraft): Prune these tags eventually.
      if (exec.outputOf(["git", "tag", "--points-at", "HEAD"]) == "" &&
          exec.outputOf(["git", "branch", "-r", "--points-at", "HEAD"]) == "") {
         tag_name = ("buildmaster-${params.COMMIT_ID}-" +
                     "${new Date().format('yyyyMMdd-HHmmss')}");
         exec(["git", "tag", tag_name, "HEAD"]);
         exec(["git", "push", "--tags", "origin"]);
      }
      def sha1 = exec.outputOf(["git", "rev-parse", "HEAD"]);
      echo("Resolved ${params.GIT_REVISIONS} --> ${sha1}");
      return sha1;
   }
}

onMaster('1h') {
   notify([slack: [channel: params.SLACK_CHANNEL,
                   thread: params.SLACK_THREAD,
                   sender: 'Mr Monkey',
                   emoji: ':monkey_face:',
                   when: ['FAILURE', 'UNSTABLE']],
           aggregator: [initiative: 'infrastructure',
                        when: ['SUCCESS', 'BACK TO NORMAL',
                               'FAILURE', 'ABORTED', 'UNSTABLE']]]) {
      try {
         checkArgs();
         def sha1 = mergeBranches();
         buildmaster.notifyMergeResult(params.COMMIT_ID, 'success', sha1);
      } catch (e) {
         // We don't really care about the difference between aborted and failed;
         // we can't use notify because we want somewhat special semantics; and
         // without all the things notify does it's hard to tell the difference
         // between aborted and failed.  So we don't bother.
         buildmaster.notifyMergeResult(params.COMMIT_ID, 'failed', null);
         throw e;
      }
   }
}
