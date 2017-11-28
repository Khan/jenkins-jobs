// The pipeline script to prepare a Khan/webapp branch for deployment.

// In order to make deploying faster, we want to do a lot of the work that is
// involved in a deploy ahead of time: things like making genfiles, testing,
// uploading, deploying to a non-default. We can, for example, do this on
// branches in the queue, and for commonly-deployed branches.
//
// This job can be triggered by Sun commands (like deploy, finish, queue,
// remove, abort, rollback), and by pushing to watched commonly-deployed
// branches.
//
// As soon as currentlyDeployingBranch is available, we will kick off two
// pre-deploy-processing jobs: one for the branch we're trying to deploy,
// merged with the current deploying branch + latest translations, and one with
// the current deploying branch + master + latest translations (in case the
// current deploy fails).
//
// The status of this job is recorded in the deploy state store, which will
// then be used in the deploy job to fast-forward the deploy process to the
// "here's a non-default version of the site to test against" stage.


@Library("kautils")
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.kaGit

new Setup(steps

).addStringParam(
    "GIT_REVISION",
    """<b>REQUIRED</b>. The name of a branch to pre-deploy (can't be master).
Can also be a list of branches to deploy separated by `+` ('br1+br2+br3').
We will automatically merge these branches (plus translations) into a new
branch based off master or the currently deploying branch, and pre-deploy
it.""", ""

).addChoiceParam(
    "DEPLOY",
    """\
<ul>
  <li> <b>default</b>: Deploy to static if there have been changes to
       the static files since the last deploy, and/or to dynamic if
       there have been changes to the dynamic files since
       the last deploy.  For tools-only changes (e.g. to Makefile), do
       not deploy at all. </li>
  <li> <b>static</b>: Deploy static (e.g. js) files to GCS, but do not
       deploy to GAE.  Only select this if you know your changes do not
       affect the server code in any way! </li>
  <li> <b>dynamic</b>: Deploy dynamic (e.g. py) files to GAE, but do
       not update GCS.  Only select this if your changes do not affect
       user-facing code (js, images) in any way!, and you're
       confident, the existing-live user-facing code will work with your
       changes. </li>
  <li> <b>both</b>: Deploy to both GCS and GAE. </li>
  <li> <b>none</b>: Do not deploy to GCS or GAE (<b>dangerous!</b> --
       do not use lightly).  Select this for tools-only changes. </li>
</ul>

<p>You may wonder: why do you need to run this job at all if you're
just changing the Makefile?  Well, it's the only way of getting files
into the master branch, so you do a 'quasi' deploy that still runs
tests/etc but doesn't actually deploy.</p>
""",
    ["default", "both", "static", "dynamic", "none"]

).addStringParam(
   "DEPLOYER_USERNAME",
   """Who asked to run this job, used to ping on slack.
If not specified, guess from the username of the person who started
this job in Jenkins.  Typically not set manually, but by hubot scripts
such as sun.  You can, but need not, include the leading `@`.""",
   ""

).apply();


def preDeployBranch(branches, currentlyDeployingBranch) {
   def dateStr = new Date().format('yyyyMMdd-HHmmss');

   // Maps branch we're pre-processing to which branch it should be based off
   def branchesMapping = [
      ["deploy-${dateStr}-prev-deploy-success", currentlyDeployingBranch],
      ["deploy-${dateStr}-prev-deploy-fail", "master"],
   ];
   for (mapping in branchesMapping){
      def (preProcessingBranch, baseBranch) = mapping;
      kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", baseBranch);
      dir("webapp") {
         exec(["git", "checkout", "-b", preProcessingBranch]);
         exec(["git", "push", "-f", "--set-upstream", "origin",
               preProcessingBranch]);
      }
      def allBranches = branches.split(/\+/);
      if (params.MERGE_TRANSLATIONS) {
         // Jenkins jobs only update intl/translations in the
         // "translations" branch.
         allBranches += ["translations"];
      }
      for (def i = 0; i < allBranches.size(); i++) {
         kaGit.safeMergeFromBranch("webapp", preProcessingBranch,
               allBranches[i].trim());
      }
      // TODO: Kick off pre-processing
   }

}
