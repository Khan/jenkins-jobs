// Determine what kinds of deploy we need to do for a given commit range.
//
// This is used by the buildmaster -- so it can tell the other jobs what work
// they need to do.  By "kinds of deploy" we mean static (javascript,
// stylesheets, and the like) or dynamic (upload to app engine); we might need
// to do one, both, or neither (a tools-only deploy).

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
   "GIT_REVISION",
   """<b>REQUIRED</b>. A commit sha we want to deploy.""",
   ""
).addStringParam(
   "BASE_REVISION",
   """The revision (commit-ish) against which to compare.  That is, we'll
return what you need to do to get the changes in GIT_REVISION to production,
assuming that production currently reflects BASE_REVISION.""",
   "master"
).apply();

currentBuild.displayName = (
   "${currentBuild.displayName} (${BASE_REVISION}..${GIT_REVISION})");

def checkArgs() {
   if (!params.GIT_REVISION) {
      notify.fail("The GIT_REVISION parameter is required.");
   }
}

def shouldDeploy() {
   kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", params.GIT_REVISION);
   dir("webapp") {
      sh("make deps");
      // TODO(benkraft): look for output == yes/no instead, and
      // if it's neither raise an exception.
      def deployStatic = (
         exec.statusOf(["deploy/should_deploy.py", "static",
                        "--from-commit", params.BASE_REVISION]) != 0);
      def deployDynamic = (
         exec.statusOf(["deploy/should_deploy.py", "dynamic",
                        "--from-commit", params.BASE_REVISION]) != 0);
      return [static: deployStatic, dynamic: deployDynamic];
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
      checkArgs();
      def deploysNeeded = shouldDeploy();
      buildmaster.notifyShouldDeploy(
         params.GIT_REVISION, 'success', deploysNeeded);
   } catch (e) {
      // We don't really care about the difference between aborted and failed;
      // we can't use notify because we want somewhat special semantics; and
      // without all the things notify does it's hard to tell the difference
      // between aborted and failed.  So we don't bother.
      buildmaster.notifyMergeResult(params.GIT_REVISION, 'failed', null);
      throw e;
   }
}
