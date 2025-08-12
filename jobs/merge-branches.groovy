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

).addStringParam(
   "REVISION_DESCRIPTION",
   """Set by the buildmaster to give a more human-readable description
of the GIT_REVISION, especially if it is a commit rather than a branch.""",
   ""

).addStringParam(
   "BUILDMASTER_DEPLOY_ID",
   """Set by the buildmaster, can be used by scripts to associate jobs
that are part of the same deploy.  Write-only; not used by this script.""",
   ""

).apply();

currentBuild.displayName = "${currentBuild.displayName} (${params.COMMIT_ID}: ${params.GIT_REVISIONS}) (${params.REVISION_DESCRIPTION})";


def checkArgs() {
   if (!params.GIT_REVISIONS) {
      notify.fail("The GIT_REVISIONS parameter is required.");
   } else if (!params.COMMIT_ID) {
      notify.fail("The COMMIT_ID parameter is required.");
   }
}


def getGaeVersionName() {
   dir('webapp') {
     def gae_version_name = exec.outputOf(["make", "gae_version_name"]);
     echo("Found gae version name: ${gae_version_name}");
     return gae_version_name;
   }
}


onMaster('1h') {
   notify([slack: [channel: params.SLACK_CHANNEL,
                   thread: params.SLACK_THREAD,
                   sender: 'Mr Monkey',
                   emoji: ':monkey_face:',
                   when: ['FAILURE', 'UNSTABLE']]]) {
      try {
         checkArgs();
         tagName = ("buildmaster-${params.COMMIT_ID}-" +
                     "${new Date().format('yyyyMMdd-HHmmss')}");
         def sha1 = kaGit.mergeRevisions(params.GIT_REVISIONS, tagName, 
                                         params.REVISION_DESCRIPTION);
         def gae_version_name = getGaeVersionName();
         buildmaster.notifyMergeResult(params.COMMIT_ID, 'success',
                                       sha1, gae_version_name);
      } catch (e) {
         // We don't really care about the difference between aborted and failed;
         // we can't use notify because we want somewhat special semantics; and
         // without all the things notify does it's hard to tell the difference
         // between aborted and failed.  So we don't bother.
         buildmaster.notifyMergeResult(params.COMMIT_ID, 'failed', null, null);
         throw e;
      }
   }
}
