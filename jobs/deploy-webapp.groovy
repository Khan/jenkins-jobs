// The pipeline script to deploy to www.khanacademy.org from Khan/webapp.
//
// "Deploying to production" is a complex process, and this is a complex
// script.  We use github-style deploys:
//    https://docs.google.com/a/khanacademy.org/document/d/1s7qvACA4Uq4ON6F4PWJ_eyBz9EJeTk-DJ6SysRrJcTI/edit
// For more high-level information on deploys, see
//    https://sites.google.com/a/khanacademy.org/forge/for-developers/deployment-guidelines
//
// Here are the steps in a deploy.  Some can happen in parallel:
//
// 1. Merge master into your branch (i.e. the branch to be deployed).
//    If specified, also merge the latest translations into your branch.
//
// 2. Determine what kind of deploy we are running: full, static, dynamic,
//    or tools-only.  This is determined by whether we have changed any
//    files that affect the server running on GAE, and whether we have
//    changed any files (or their dependencies) that are deployed to GCS.
//
// 3. Build all the artifacts to be deployed (differs depending on deploy
//    kind).
//
// 4. Run (python and javascript and other source code) tests.
//
// 5. Deploy new server-related code to GAE, if appropriate.
//
// 6. Deploy new statically-served files to GCS, if appropriate.
//
// 7. Run end-to-end tests on the newly deployed version.
//
// 8. Prompt the user to do manual testing and either continue or abort.
//
// 9. (Assuming 'continue')  "Prime" GAE to force it to start up
//    a few thousand instances of the new version we deployed.
//
// 10. Tell google to make our new version the default-serving version.
//
// 9-10b. Alternately to 9 and 10, for a static-only deploy, tell
//    our existing prod server about the new static content we've
//    deployed.
//
// 11. Run end-to-end tests again on now that our new version is default.
//     This can catch errors that only occur on a khanacdemy.org domain.
//
// 12. Do automated monitoring of our appengine logs to check for an
//     uptick in errors after the deploy.
//
// 13. Prompt the user to either finish up or abort.
//
// 14. (Assuming 'finish up')  Merge the deployed branch back into master,
//     and git-tag Khan/webapp with the new release label.


@Library("kautils")
// Classes we use, under jenkins-tools/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-tools/vars/.  This is just for documentation.
//import vars.exec
//import vars.kaGit
//import vars.notify
//import vars.onMaster
//import vars.withSecrets
//import vars.withTimeout


new Setup(steps

).addStringParam(
    "GIT_REVISION",
    """<b>REQUIRED</b>. The name of a branch to deploy (can't be master).
We will automatically merge master into the branch and then deploy it.""",
    ""

).addChoiceParam(
    "RUN_TESTS",
    """\
<ul>
  <li> <b>default</b>: Run relevant tests if they haven't previously
       passed at this commit. </li>
  <li> <b>yes</b>: Run all tests, even those that have already passed
       at this commit. </li>
  <li> <b>no</b>: Do not run tests before deploying (<b>dangerous!</b>
        -- do not use lightly). </li>
</ul>""",
    ["default", "yes", "no"]

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

).addBooleanParam(
    "MERGE_TRANSLATIONS",
    """<p>If set, merge the latest translations from origin/translations into
your branch before deploying.</p>

<p>This should normally be set.  However, if you need your deploy to
go a few minutes faster, or you want to exactly reproduce a previous
 deploy, you can unset this.</p>""",
    true

).addBooleanParam(
    "ALLOW_SUBMODULE_REVERTS",
    """When set, do not give an error if the new version you're deploying has
reverted one of the git submodules to an earlier state than what
exists on the current default.  Usually such reverts are an accident
(when someone ran \"git pull\" instead of \"git p\" for instance) so
we don't allow it.  If you are purposefully reverting substate, to
revert a bug for instance, you must set this flag.""",
    false

).addBooleanParam(
    "FORCE",
    """When set, force a deploy to GAE (AppEngine) even if the version has
already been deployed. Likewise, force a copy of <i>all</i> files to
GCS (Cloud Storage), even those the md5 checksum indicate are already
present on GCS.  Also force tests to be run even if they've already passed
before at this sha1.  Note that this does not override <code>DEPLOY</code>;
we only force GAE (or GCS) if we're actually deploying to it.""",
    false

).addStringParam(
    "MONITORING_TIME",
    """How many minutes to monitor after the new version is set as default on
all modules.""",
    "5"

).addBooleanParam(
    "SKIP_PRIMING",
    """If set to True, we will change the default version without priming any
of the new instances. THIS IS DANGEROUS, and will definitely cause
disruptions to users. Only to be used in case of urgent emergency.""",
    false

).addChoiceParam(
    "CLEAN",
    """\
<ul>
  <li> <b>some</b>: Clean the workspaces (including .pyc files) but
       not genfiles. </li>
  <li> <b>most</b>: Clean the workspaces and genfiles, excluding
       js/python modules. </li>
  <li> <b>all</b>: Full clean that results in a pristine working copy. </li>
  <li> <b>none</b>: Do not clean at all. </li>
</ul>""",
    ["some", "most", "all", "none"]

).addStringParam(
   "DEPLOYER_USERNAME",
   """Who asked to run this job, used to ping on slack.
If not specified, guess from the username of the person who started
this job in Jenkins.  Typically not set manually, but by hubot scripts
such as sun.  You can, but need not, include the leading `@`.""",
   ""

).addStringParam(
    "BUILD_USER_ID_FROM_SCRIPT",
    """(Deprecated form of DEPLOYER_USERNAME)""",
    ""

).apply();

currentBuild.displayName = ("${currentBuild.displayName} " +
                            "(${params.GIT_REVISION})");


// We set these to real values first thing below; but we do it within
// the notify() so if there's an error setting them we notify on slack.

// We purposefully hard-code this so people can't do sekret deploys. :-)
SLACK_CHANNEL = "#1s-and-0s-deploys";

// The `@<name>` we ping on slack as we go through the deploy.
DEPLOYER_USERNAME = null;

// The sha1 of the deploy (after merging in master and translations).
GIT_SHA1 = null;

// True if we should deploy to GCS/GAE (respectively).
DEPLOY_STATIC = null;
DEPLOY_DYNAMIC = null;

// The git-tagname to roll back to if this deploy fails.  It
// should be the git tag of the current-live deploy when this
// script is first invoked.
ROLLBACK_TO = null;

// The "permalink" url used to access code deployed at GIT_SHA1.
// (That is, version-dot-khan-academy.appspot.com, not www.khanacademy.org).
DEPLOY_URL = null;

// The dynamic-deploy and static-deploy version-names.
GAE_VERSION = null;
GCS_VERSION = null;


// This must be run in the workspace directory, inside a node.
def _callDeployPipeline(whichStage) {
   // While not necessary to always pass in all the args when calling
   // deploy-pipeline, I do it to make it easier to carve out bits
   // of deploy-pipeline to move somewhere else.
   def args = ["deploy/deploy_pipeline.py", whichStage,
               "--lockdir=../tmp/deploy.lockdir",
               "--deployer-username=${DEPLOYER_USERNAME}",
               "--git_revision=${GIT_REVISION}",
               "--jenkins_url=${env.JENKINS_URL}",
               "--chat-sender=Mr Monkey",
               "--slack_channel=${SLACK_CHANNEL}",
               "--icon_emoji=:monkey_face:",
               "--token=T${GIT_SHA1}",
               "--monitoring_time=${params.MONITORING_TIME}",
               "--jenkins-build-url=${env.BUILD_URL}"];
   if (params.SKIP_PRIMING) {
      args += ["--skip-priming"];
   }
   if (!DEPLOY_DYNAMIC) {
      args += ["--no-deploy-dynamic"];
   }
   if (!DEPLOY_STATIC) {
      args += ["--no-deploy-static"];
   }
   // We set the current build to UNSTABLE if promote() or monitor() fail.
   if (currentBuild.result == "UNSTABLE") {
      args += ["--num-failed-jobs=1"];
   }
   withSecrets() {   // secrets are needed because this talks to slack
      dir("webapp") {
         exec(args);
      }
   }
}


def mergeFromMasterAndInitializeGlobals() {
   onMaster('1h') {    // should_deploy builds files, which can take forever
      if (params.DEPLOYER_USERNAME) {
         DEPLOYER_USERNAME = params.DEPLOYER_USERNAME;
      } else if (params.BUILD_USER_ID_FROM_SCRIPT) {
         DEPLOYER_USERNAME = params.BUILD_USER_ID_FROM_SCRIPT.split('@')[0];
      } else {
         wrap([$class: 'BuildUser']) {
            // It seems like BUILD_USER_ID is typically an email address.
            DEPLOYER_USERNAME = env.BUILD_USER_ID.split("@")[0];
         }
      }
      if (!DEPLOYER_USERNAME.startsWith("@")) {
         DEPLOYER_USERNAME = "@${DEPLOYER_USERNAME}";
      }

      // Merge to master.
      // TODO(csilvers): have these return an error message instead
      // of alerting themselves, so we can use notify.fail().
      withEnv(["SLACK_CHANNEL=${SLACK_CHANNEL}",
               "DEPLOYER_USERNAME=${DEPLOYER_USERNAME}"]) {
         kaGit.safeSyncToOrigin("git@github.com:Khan/webapp",
                                params.GIT_REVISION);
         kaGit.safeMergeFromMaster("webapp", params.GIT_REVISION,
                                   ["third_party"]);
         if (params.MERGE_TRANSLATIONS) {
            // We only merge translations if we are on a branch.
            def rc = sh(
               script: ("git ls-remote --exit-code webapp " +
                        "origin/${exec.shellEscape(params.GIT_REVISION)}"),
               returnStatus: true);
            if (rc == 0) {
               // We have to source build.lib since it has a guard
               // requiring it to be run in webapp. :-(
               sh(". jenkins-tools/build.lib; cd webapp; " +
                  "safe_pull intl/translations; " +
                  "safe_update_submodule_pointer_to_master intl/translations");
            }
         }
      }

      dir("webapp") {
         sh("make python_deps");
      }

      dir("webapp") {
         GIT_SHA1 = exec.outputOf(["git", "rev-parse", params.GIT_REVISION]);
         // Let's do a sanity check.
         def headSHA1 = exec.outputOf(["git", "rev-parse", "HEAD"]);
         if (GIT_SHA1 != headSHA1) {
            notify.fail("Internal error: " +
                        "HEAD does not point to the deploy-branch");
         }

         if (params.DEPLOY == "default") {
            def output = exec.outputOf(["deploy/should_deploy.py", "static"]);
            DEPLOY_STATIC = (output == "yes");
         } else {
            DEPLOY_STATIC = (params.DEPLOY in ["static", "both"]);
         }

         if (params.DEPLOY == "default") {
            def output = exec.outputOf(["deploy/should_deploy.py", "dynamic"]);
            DEPLOY_DYNAMIC = (output == "yes");
         } else {
            DEPLOY_DYNAMIC = (params.DEPLOY in ["dynamic", "both"]);
         }

         // If this deploy fails, we want to roll back to the version
         // that was active when the deploy started.  That's now!
         ROLLBACK_TO = exec.outputOf(["deploy/current_version.py",
                                      "--git-tag"]);

         def gaeVersionName = exec.outputOf(["make", "gae_version_name"]);

         if (DEPLOY_STATIC && !DEPLOY_DYNAMIC) {
            // In this case, the GAE version stays the same as before
            // (so we can use ROLLBACK_TO) but the static version is new.
            GAE_VERSION = ROLLBACK_TO;
            GCS_VERSION = gaeVersionName;
            DEPLOY_URL = "https://static-${GCS_VERSION}.khanacademy.org";
         } else {
            GAE_VERSION = gaeVersionName;
            GCS_VERSION = gaeVersionName;
            DEPLOY_URL = "https://${GAE_VERSION}-dot-khan-academy.appspot.com";
         }
      }
   }
}


def sendStartMessage() {
   // TODO(csilvers): all we need to really do from this step is send
   // the message to slack (once nothing depends on the props file).
   onMaster("10m") {
      _callDeployPipeline("acquire-lock");

      // TODO(csilvers): this is just to update the props file again;
      // we can get rid of it once we no longer have a props file.
      _callDeployPipeline("check-merge");
   }
}


def runTests() {
   if (params.RUN_TESTS == "no") {
      return;
   }
   def TEST_TYPE = (params.RUN_TESTS == "default" ? "relevant" : "all");
   build(job: 'webapp-test',
         parameters: [
            string(name: 'GIT_REVISION', value: GIT_SHA1),
            string(name: 'TEST_TYPE', value: TEST_TYPE),
            string(name: 'MAX_SIZE', value: "medium"),
            booleanParam(name: 'FAILFAST', values: false),
            string(name: 'SLACK_CHANNEL', value: SLACK_CHANNEL),
            booleanParam(name: 'FORCE', value: params.FORCE),
         ]);
}


def deployToGAEAndGCS() {
   if (DEPLOY_STATIC || DEPLOY_DYNAMIC) {
      onMaster('120m') {
         withEnv(["DEPLOY_VERSION=default",
                  "DEPLOY_STATIC=${DEPLOY_STATIC}",
                  "DEPLOY_DYNAMIC=${DEPLOY_DYNAMIC}",
                  "DEPLOYER_USERNAME=${DEPLOYER_USERNAME}",
                  "SKIP_PRIMING=${params.SKIP_PRIMING}",
                  "SLACK_CHANNEL=${SLACK_CHANNEL}",
                  "SUBMODULE_REVERTS=${params.ALLOW_SUBMODULE_REVERTS}",
                  "CLEAN=${params.CLEAN}",
                  "FORCE=${params.FORCE}"]) {
            withSecrets() {     // secrets are needed to talk to slack
               // TODO(csilvers): separate out deploy-static and
               // deploy-dynamic into separate scripts.
               sh("jenkins-tools/deploy.sh");
            }
            // TODO(csilvers): check for "Precompilation failed." in the logs?
         }
      }
   }
}


def promptForSetDefault() {
   // TODO(csilvers): do the alerting here.  manual-test has the wrong links.
   onMaster('1m') {
      _callDeployPipeline("manual-test");
   }
   withTimeout('1h') {   // we give people 1 hour to say "set-default".
      input(message: "Set default?", id: "SetDefault");
   }
}


def _promote() {
   onMaster('25m') {
      def cmd = ["deploy/set_default.py",
                 GAE_VERSION,
                 "--slack-channel=${SLACK_CHANNEL}",
                 "--deployer_username=${DEPLOYER_USERNAME}"];
      if (GCS_VERSION && GCS_VERSION != GAE_VERSION) {
         cmd += ["--static-content-version=${GCS_VERSION}"];
      }
      if (params.SKIP_PRIMING) {
         cmd += ["--no-priming"];
      }

      withSecrets() {
         dir("webapp") {
            try {
               exec(cmd);

               // Once we finish (successfully) promoting, let's run
               // the e2e tests again.  I'd rather do this at the top
               // level, but since we run in a `parallel` it's better
               // to do this here so we don't have to wait for the
               // monitor job to finish before running this.  We could
               // set `wait` to false, but I think it's better to wait
               // for e2e's before saying set-default is finished,
               // just like we wait for the other kind of monitoring.
               build(job: 'e2e-test',
                     propagate: false,  // e2e errors are not fatal for deploy
                     parameters: [
                        string(name: 'URL',
                               value: "https://www.khanacademy.org"),
                        string(name: 'SLACK_CHANNEL', SLACK_CHANNEL),
                        string(name: 'GIT_REVISION', value: GIT_SHA1),
                        booleanParam(name: 'FAILFAST', values: false),
                        string(name: 'DEPLOYER_USERNAME',
                               value: DEPLOYER_USERNAME),
                     ]);
            } catch (e) {
               // Failure to promote is not a fatal error: we'll tell
               // people on slack so they can promote manually.  But
               // we don't want to abort the deploy, like a FAILURE would.
               currentBuild.result = "UNSTABLE";
            }
         }
      }
   }
}


def _monitor() {
   if (params.MONITORING_TIME == "0") {
      return;
   }

   onMaster('30m') {
      cmd = ["deploy/monitor.py", GAE_VERSION, GCS_VERSION,
             "--monitor=${params.MONITORING_TIME}",
             "--slack-channel=${SLACK_CHANNEL}",
             "--monitor-error-is-fatal"];
      withSecrets() {
         dir("webapp") {
            try {
               exec(cmd);
            } catch (e) {
               // Failure to monitor is not a fatal error: we'll tell
               // people on slack so they can monitor manually.  But
               // we don't want to abort the deploy, like a FAILURE would.
               currentBuild.result = "UNSTABLE";
            }
         }
      }
   }
}


def setDefaultAndMonitor() {
   onMaster('120m') {
      _callDeployPipeline("set-default-start");

      // Note that while we start these jobs at the same time, the
      // monitor script has code to wait until well after the
      // promotion has finished before declaring monitoring finished.
      // The reason we do these in parallel -- and don't just do
      // _monitor() after _promote() -- is that not all instances
      // switch to the new version at the same time; we want to start
      // monitoring as soon as the first instance switches, not after
      // the last one does.
      parallel(
         [ "promote": { _promote(); },
           "monitor": { _monitor(); },
         ]);
   }
}


def promptToFinish() {
   onMaster('1m') {
      _callDeployPipeline("set-default-end");
   }
   // TODO(csilvers); let this timeout be configurable?  Then if you
   // want to run a new version live for a few hours to collect some
   // data, and automatically revert back to the previous version when
   // you're done, you could just set a timeout for '5h' or whatever
   // and let the timeout-trigger abort the deploy.
   withTimeout('1h') {   // we give people 1 hour to say "finish".
      input(message: "Finish up?", id: "Finish");
   }
}


def finishWithSuccess() {
   onMaster('10m') {
      _callDeployPipeline("finish-with-success");
   }
}


def finishWithFailure() {
   onMaster('20m') {
      _callDeployPipeline("finish-with-rollback");
   }
}


notify([slack: [channel: '#1s-and-0s-deploys',
                sender: 'Mr Monkey',
                emoji: ':monkey_face:',
                // We don't need to notify on success because
                // deploy_pipeline.py does it for us.
                when: ['BUILD START','FAILURE', 'UNSTABLE', 'ABORTED']]]) {
   stage("Merging in master") {
      mergeFromMasterAndInitializeGlobals();
   }

   stage("Starting") {
      sendStartMessage();
   }

   stage("Deploying and testing") {
      parallel(
         "deploy": { deployToGAEAndGCS(); },
         "test": { runTests(); },
         "failFast": true,
      )
   }

   // (Note: we run the e2e tests even for tools-only deploys, to make
   // sure the deploy doesn't break the e2e test system.)  In theory
   // we can start the e2e tests as soon as deployToGAEAndGCS
   // finishes, but since the e2e tests use the same machines as the
   // (unit) tests, we might as well just wait until both complete
   // before doing this.  TODO(csilvers): remove "wait: false"?  It
   // means people would have to wait for e2e tests to finish before
   // being able to set default, and we'd have to refactor
   // `deploy_pipeline manual-test`.
   stage("First e2e test") {
      build(job: 'e2e-test',
            wait: false,
            propagate: false,  // e2e errors are not fatal for a deploy
            parameters: [
               string(name: 'URL', value: DEPLOY_URL),
               string(name: 'SLACK_CHANNEL', value: SLACK_CHANNEL),
               string(name: 'GIT_REVISION', value: GIT_SHA1),
               booleanParam(name: 'FAILFAST', value: false),
               string(name: 'DEPLOYER_USERNAME', value: DEPLOYER_USERNAME),
            ]);
   }

   if (DEPLOY_STATIC || DEPLOY_DYNAMIC) {
      try {
         stage("Prompt 1") {
            promptForSetDefault();
         }
         stage("Promoting and monitoring") {
            setDefaultAndMonitor();
         }
         stage("Prompt 2") {
            promptToFinish();
         }
      } catch(e) {
         finishWithFailure();
         throw e;
      }
   }

   stage("Merging to master") {
      finishWithSuccess();
   }
}
