// Send live traffic to a new version of webapp.

// Sending traffic to a version is a complex process, and this is a complex
// script.  For more high-level information on deploys, see
//    https://docs.google.com/document/d/1Zr0wwzbvPkmN_BFAsrMPZhccIb0HHJUgmLZfBUWYFvA/edit
// For more details on how this is used as a part of our build process, see the
// buildmaster (github.com/Khan/buildmaster) and its design docs:
//     https://docs.google.com/document/d/1utyUMMBOQvt4o3W_yl_89KdlNdAZUYsnSN_2FjWL-wA/edit#heading=h.kzjq9eunc7bh

// By the time we run, a new version has already been uploaded to App Engine
// and/or Google Cloud Storage with the relevant set of changes, end-to-end
// tests have been run on it, and unit tests have been run on the corresponding
// code.  Here's what we do, some of it in parallel:
//
// 1. Prompt the user to do manual testing and either continue or abort.
//
// 2. (Assuming 'continue')  "Prime" GAE to force it to start up
//    a few thousand instances of the new version we deployed.
//
// 3. Tell google to make our new version the default-serving version.
//
// 4. Run end-to-end tests again on now that our new version is default.
//    This can catch errors that only occur on a khanacdemy.org domain.
//
// 5. Do automated monitoring of our appengine logs to check for an
//    uptick in errors after the deploy.
//
// 6. Prompt the user to either finish up or abort.
//
// 7. (Assuming 'finish up')  Merge the deployed branch back into master,
//    and git-tag Khan/webapp with the new release label.

// TODO(benkraft): A lot of the initialization and alerting logic is duplicated
// with build-webapp; share it instead.


@Library("kautils")
// Standard classes we use.
import groovy.json.JsonBuilder;
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.clean
//import vars.exec
//import vars.kaGit
//import vars.logs
//import vars.notify
//import vars.withSecrets
//import vars.withTimeout
//import vars.withVirtualenv


// We do not allow concurrent builds; this should in theory also be enforced by
// the buildmaster, but we do it too as an extra safety check.
new Setup(steps

// Sometimes we want to debug a deploy a week or two later.  Let's
// keep a lot of these.
).resetNumBuildsToKeep(
   500,

).addStringParam(
    "GIT_REVISION",
    """<b>REQUIRED</b>. The sha1 to deploy.""",
    ""


).addStringParam(
    "SERVICES",
    """<p>A comma-separated list of services we wish to change to the new
version (see below for options), or the special value "auto", which says to
choose the services to set-default automatically based on what files have
changed.  For example, you might specify "ai-guide,users" to force setting
default on both the ai-guide and users services.</p>

<p>You can specify the empty string to do no version-switching, like if you
just change the Makefile.  (Do not do this lightly!)  You may wonder: why do
you need to run this job at all if you're just changing the Makefile?  Well,
it's the only way of getting files into the master branch, so you do a 'quasi'
deploy that just merges to master.</p>
""",
    "auto"

).addStringParam(
    "MONITORING_TIME",
    """How many minutes to monitor after the new version is set as default on
all modules.""",
    "5"

).addBooleanParam(
    "WAIT_LONGER",
    """Allow up to 6 hours, instead of 1 hour, for set-default and finish.""",
    false

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
   """The user id of who asked to run this job, used to ping on slack.
If not specified, guess from the username of the person who started
this job in Jenkins.  Typically not set manually, but by hubot scripts
such as sun. Should be of the form <@U1337H4KS>.""",
   ""

).addStringParam(
    "PRETTY_DEPLOYER_USERNAME",
    """The slack display name/real name of who asked to run this job. This
should be the human-readable version of DEPLOYER_USERNAME, and does not
have a leading `@`.""",
    ""

).addStringParam(
    "REVISION_DESCRIPTION",
    """Set by the buildmaster to give a more human-readable description
of the GIT_REVISION, especially if it is a commit rather than a branch.
Defaults to GIT_REVISION.""",
    ""

).addStringParam(
   "BUILDMASTER_DEPLOY_ID",
   """Set by the buildmaster, can be used by scripts to associate jobs
that are part of the same deploy.  Write-only; not used by this script.""",
   ""

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

REVISION_DESCRIPTION = params.REVISION_DESCRIPTION ?: params.GIT_REVISION;

currentBuild.displayName = ("${currentBuild.displayName} " +
                            "(${REVISION_DESCRIPTION})");


// We set these to real values first thing below; but we do it within
// the notify() so if there's an error setting them we notify on slack.

// We purposefully hard-code this so people can't do secret deploys. :-)
SLACK_CHANNEL = "#1s-and-0s-deploys";

// The `@<name>` we ping on slack as we go through the deploy.
DEPLOYER_USERNAME = null;

// The `<display_name>` we use to talk about the deployer (does not ping).
PRETTY_DEPLOYER_USERNAME = null;

// The tag we will use to tag this deploy.
GIT_TAG = null;
V3_GIT_TAG = null;   // The tag we're going to start using one day

// A dict holding the active version-string for each version after
// this deploy finishes.  This is equal to (some representation of)
// GIT_REVISION for any services we are currently deploying, and the
// existing current-version string for all other services.
VERSION_DICT = [:];

// The list of services to deploy.
SERVICES = null;

// The git-tagname to roll back to if this deploy fails.  It
// should be the git tag of the current-live deploy when this
// script is first invoked.
ROLLBACK_TO = null;

// The "permalink" url used to access code deployed.
// (That is, prod-version.khanacademy.org, not www.khanacademy.org).
DEPLOY_URL = null;

// The deploy-version.  This will be the new service-version for any
// services we are deploying.
NEW_VERSION = null;

// This holds the arguments to _alert.  It a groovy struct imported at runtime.
alertMsgs = null;


// Sometimes jobs that are aborted by a user don't really abort because
// Jenkins job is in an uninterruptible status. Most likely due the abort
// happened while the job is making an HTTP request. In those case, we will
// throw this exception to really really abort the jobs to prevent getting
// stuck with jobs not aborting.
public class AbortDeployJob extends Exception {}


@NonCPS     // for replaceAll()
def _interpolateString(def s, def interpolationArgs) {
   // Arguments to replaceAll().  `all` is the entire regexp match,
   // `keyword` is the part that matches our one parenthetical group.
   def interpolate = { all, keyword -> interpolationArgs[keyword]; };
   def interpolationPattern = "%\\(([^)]*)\\)s";
   return s.replaceAll(interpolationPattern, interpolate);
}

// This sends a message to slack.  `slackArgs` is a dict saying how to
// format the message; it should be a constant from
// jobs/deploy-webapp_slackmsgs.groovy.  The text in `slackArgs` may
// include interpolation placeholders like `%(foo)s`.  In that that
// case, `interpolationArgs` are used to resolve those placeholders.
// It should be a dict whose keys are the placeholder keywords and
// whose values are the proper values for this alert.  Example:
//    _alert(alertMsgs.SETTING_DEFAULT, [combinedVersion: GIT_TAG,
//                                       abortUrl: "${env.BUILD_URL}stop"]);
//
// Should be run under a node in the workspace-root directory.
def _alert(def slackArgs, def interpolationArgs) {
   def msg = "${DEPLOYER_USERNAME}: ${slackArgs.text}";
   def intro = slackArgs.simpleMessage ? "" : "Hey ${DEPLOYER_USERNAME},";

   // Do string interpolation on msg.
   msg = _interpolateString(msg, interpolationArgs);

   args = ["jenkins-jobs/alertlib/alert.py",
           "--slack=${SLACK_CHANNEL}",
           "--chat-sender=Mr Monkey",
           "--icon-emoji=:monkey_face:",
           "--severity=${slackArgs.severity}",
           "--slack-intro=${intro}",
          ];
   if (slackArgs.simpleMessage) {
      args += ["--slack-simple-message"];
   }
   if (slackArgs.attachments) {
      // As promised in the docstring from deploy-webapp_slackmsgs.groovy,
      // We add `text` as a fallback for attachments.
      for (def i = 0; i < slackArgs.attachments.size(); i++) {
         if (!slackArgs.attachments[i].fallback) {
            slackArgs.attachments[i].fallback = slackArgs.text;
         }
      }
      def attachmentString = new JsonBuilder(slackArgs.attachments).toString();
      // Do string interpolation on attachments.
      attachmentString = _interpolateString(attachmentString,
                                            interpolationArgs);
      args += ["--slack-attachments=${attachmentString}"];
   }
   withSecrets.slackAlertlibOnly() {
      sh("echo ${exec.shellEscape(msg)} | ${exec.shellEscapeList(args)}");
   }
}

// This method filters a common 'DEPLOYER_USERNAME' into a series of comma
// seperated slack user id's for the purpose of passing into alert.py's
// '--slack' argument. For Example:
// DEPLOYER_USERNAME: <@UMZGEUH09> (cc <@UN5UC0EM6>)
// becomes: UMZGEUH09,UN5UC0EM6,
// TODO(dbraley): Add parsing of `@` usernames and `#` channels if needed
@NonCPS // for pattern & matcher
def _userIdsFrom(def deployUsernameBlob) {
   // Regex to specifically grab the ids, which should start with U and be
   // some number of capital letters and numbers. Ids can also start with
   // W (special users), T (teams), or C (channels).
    def pattern = /<@([UTWC][0-9A-Z]+)>/;
    def match = (deployUsernameBlob =~ pattern);

    allUsers = "";

    for (n in match) {
        allUsers += "${n[1]},";
    }

    return allUsers;
}

// Sends a survey to the deployer and anyone cc'ed to help infrastructure
// understand why a deployment was aborted. Also sends it to
// #dev-support-stream so we can follow up on 'missed' surveys.
//
// #infrastructure-platform: <#C01120CNCS0>
def _sendAbortedDeploymentSurvey() {
    def msg = ":robot_hearthands: It looks like you recently aborted a " +
        "deployment. If there is still work to do for monitoring, please do " +
        "that *BEFORE* responding to this message. However, when you have a " +
        "few minutes <#C01120CNCS0> would appreciate if you could fill out this " +
        "short survey to help us understand what happened. Your response " +
        "helps us refine the deployment system. " +
        "<https://docs.google.com/forms/d/e/1FAIpQLSczr-9iOrdI1kaFNCAamoLMJ1cDGehURFpLV-OMcHSLIa3Rkg/viewform?usp=pp_url&entry.1550451339=${BUILD_NUMBER}|click me>";

    def userIds = _userIdsFrom("${DEPLOYER_USERNAME}");
    userIds += "#dev-support-stream";

    args = ["jenkins-jobs/alertlib/alert.py",
        "--slack=${userIds}",
        "--chat-sender=Mr Monkey",
        "--icon-emoji=:monkey_face:",
        "--severity=info",
        ];

    withSecrets.slackAlertlibOnly() {
        sh("echo ${exec.shellEscape(msg)} | ${exec.shellEscapeList(args)}");
    }
}

def mergeFromMaster() {
   withTimeout('1h') {
      // In principle we should fetch from workspace@script which is where this
      // script itself is loaded from, but that doesn't exist on build-workers
      // and our checkout of jenkins-jobs will work fine.
      alertMsgs = load("${pwd()}/jenkins-jobs/jobs/deploy-webapp_slackmsgs.groovy");

      if (params.DEPLOYER_USERNAME) {
         DEPLOYER_USERNAME = params.DEPLOYER_USERNAME;
      } else {
         wrap([$class: 'BuildUser']) {
            // It seems like BUILD_USER_ID is typically an email address.
            DEPLOYER_USERNAME = env.BUILD_USER_ID.split("@")[0];
         }
      }

      PRETTY_DEPLOYER_USERNAME = params.PRETTY_DEPLOYER_USERNAME;

      // Create the deploy branch and merge in the requested branch.
      // TODO(csilvers): have these return an error message instead
      // of alerting themselves, so we can use notify.fail().
      withEnv(["SLACK_CHANNEL=${SLACK_CHANNEL}",
               "DEPLOYER_USERNAME=${DEPLOYER_USERNAME}"]) {
         // If we are only doing some stages, we have already done our
         // merging -- which is good because the buildmaster expects us to
         // pass back a sha.
         // TODO(benkraft): Verify it's actually a valid sha in this repo.
         // (We could write kaGit.isSha.)  This is a little tricky because
         // we may not yet have cloned the repo.
         if (!(params.GIT_REVISION ==~ /[0-9a-fA-F]{40}/)) {
            notify.fail("GIT_REVISION was not a sha!");
         }

         kaGit.safeSyncToOrigin("git@github.com:Khan/webapp",
                                params.GIT_REVISION);

         dir("webapp") {
            def revList = exec.outputOf(
                  ["git", "rev-list", "${params.GIT_REVISION}..origin/master"])
            if (revList) {
               // We do an extra safety check, that GIT_REVISION
               // is a valid sha ahead of master.
               notify.fail("GIT_REVISION ${params.GIT_REVISION} is " +
                           "behind master!  Output: ${revList}");
            }
         }
      }

      dir("webapp") {
         clean(params.CLEAN);
      }
   }
}

def initializeGlobals() {
   withTimeout('1h') {    // should_deploy builds files, which can take forever
      dir("webapp") {
         // Let's do a sanity check.
         def headSHA1 = exec.outputOf(["git", "rev-parse", "HEAD"]);
         if (params.GIT_REVISION != headSHA1) {
            notify.fail("Internal error: " +
                        "HEAD does not point to the deploy-branch");
         }

         // TODO(benkraft): Extract the resolution of services into a util
         if (params.SERVICES == "auto") {
            // Slack is temporarily needed while should_deploy is doing
            // side-by-side testing of the go_code_analysis.go.
            // TODO(csilvers): remove this once should_deploy.py does
            //                 not directly import alertlib anymore.
            withSecrets.slackAlertlibOnly() {
               try {
                  SERVICES = exec.outputOf(["deploy/should_deploy.py"]).split("\n");
               } catch(e) {
                  notify.fail("Automatic detection of what to deploy failed. " +
                              "You can likely work around this by setting " +
                              "SERVICES on your deploy; see " +
                              "${env.BUILD_URL}rebuild for documentation, and " +
                              "`sun: help flags` for how to set it.  If you " +
                              "aren't sure, ask dev-support for help!");
               }
            }
         } else {
            SERVICES = params.SERVICES.split(",");
         }
         if (SERVICES == [""]) {
            // Either of the above could be [""], if we should
            // deploy nothing.  We want to use [] instead: [""]
            // would mean deploying a single nameless service or
            // something.
            SERVICES = [];
         }
         echo("Deploying to the following services: ${SERVICES.join(', ')}");

         NEW_VERSION = exec.outputOf(["make", "gae_version_name"]);
         DEPLOY_URL = "https://prod-${NEW_VERSION}.khanacademy.org";
         GIT_TAG = "gae-${NEW_VERSION}";

         // Test coverage in webapp/deploy/git_tags_test.py
         // DeployTagsTest mimics the git tag workflow used
         // here. If any changes are made to the steps below, make
         // the same changes there as well so we are testing the
         // way our deploy git tagging works.
         def currentVersionTag = exec.outputOf(
            ["deploy/current_version.py", "--git-tag"]);
         def currentVersionParts = exec.outputOf(
            ["deploy/git_tags.py", "--parse", currentVersionTag]).split("\n");

         // If this deploy fails, we want to roll back to the version
         // that was active when the deploy started.  That's now!
         ROLLBACK_TO = currentVersionTag;

         // Construct a dict from service to version, using NEW_VERSION
         // for any service in SERVICES.  We'll emit this in the git tag.
         for (def i = 0; i < currentVersionParts.size(); i++) {
            def serviceAndVersion = currentVersionParts[i];
            if (serviceAndVersion) {
               def service = serviceAndVersion.split("=")[0];
               def oldVersion = serviceAndVersion.split("=")[1];
               VERSION_DICT[service] = oldVersion;
            }
         }
         for (def i = 0; i < SERVICES.size(); i++) {
            VERSION_DICT[SERVICES[i]] = NEW_VERSION;  // I AM YELLING!
         }
      }
   }
}


def _manualSmokeTestCheck(job){
   def msg = ("Usually we can do this step for you, but when sun is down, " +
              "you must manually confirm that your ${job} is done and all tests " +
              "have passed!\n\n")

   if (job == 'first-smoke-test') {
      msg += ("If you haven't aleady started these tests after your build ended, " +
              "go to https://jenkins.khanacademy.org/job/deploy/job/e2e-test/" +
              "build?URL=${DEPLOY_URL} to kick off your smoke tests.\n\n")
   } else {
      msg += ("If you haven't aleady started these tests after default was set, " +
              "go to https://jenkins.khanacademy.org/job/deploy/job/e2e-test/build " +
              "to kick off your smoke tests (you can just use the default URL).\n\n")
   }
   msg += ("Then after your tests have passed (don't worry there's usually time " +
          "for at least one retry), click Proceed to continue with your deploy.")
   // We've given up to 60m to confirm smoke tests have passed. In
   // a sun outage, this should be enough to cover this step, even
   // if it requires rerunning an smoke test job.
   input(message: msg, id: "ConfirmE2eSuccess");
   return;
}


def deployFastlyStaging() {
   withTimeout('10m') {
      withSecrets.slackAndStackdriverAlertlibOnly() {
         exec(["make", "-C", "webapp/services/fastly-khanacademy-compute",
               "deploy-staging",
               "ALREADY_RAN_TESTS=1",
               "DEPLOY_VERSION=${NEW_VERSION}"]);
         exec(["make", "-C", "webapp/services/fastly-khanacademy-compute",
               "set-default-staging",
               "DEPLOY_VERSION=${NEW_VERSION}"]);
      }
   }
}

def verifySmokeTestResults(jobName, buildmasterFailures=0) {
   withTimeout('60m') {
      def status;
      while (status != "succeeded") {
         status = buildmaster.pingForStatus(jobName,
                                            params.GIT_REVISION)

         // If sun is down (even after a retry), we ask people to manually
         // check the result of their smoke tests.
         if (!status) {
            if (buildmasterFailures == 0) {
               buildmasterFailures += 1;
            } else {
               _alert(alertMsgs.BUILDMASTER_OUTAGE,
                      [step: "${jobName} is complete",
                       logsUrl: env.BUILD_URL])
               _manualSmokeTestCheck(jobName)
               return;
            }
         }

         // We care about consecutive failures, so if we get a status after
         // we've previously logged a buildmaster failure, reset to 0.
         if (status && buildmasterFailures == 1) {
            buildmasterFailures = 0;
         }

         // For now, we're making smoke tests non-blocking, but
         // if we improve the flakiness of smoke tests in the future
         // this should be changed to only allow the deploy job to
         // continue if a job has succeeded.
         if (status in ["succeeded", "aborted", "failed", "killed", "skipped"]) {
            return;
         } else {
           // continue pinging once a minute until smoke tests succeed
           // or until we time out
           sleep(60);
         }
      }
   }
}


def _manualPromptCheck(prompt){
   def msg = "Looks like buildmaster is not responding!\n\n";
   if (prompt == 'set-default') {
      msg += ("If you have aleady done `sun: set default` then " +
              "click Proceed to continue with your deploy. Or, " +
              "complete your manual testing on  ${DEPLOY_URL} and let " +
              "us know when you're ready.");
   } else if (prompt == 'finish-up') {
      msg += ("If you have aleady done `sun: finish up` then " +
              "click Proceed to continue with your deploy. Or, " +
              "double check your monitoring errors and let " +
              "us know when you're ready.");
   }
   input(message: msg, id: "ConfirmSetDefaultPrompt");
   return;
}

// This funciton will check if the state of the current job has changed
// but somehow the thread is still running. So we will throw an exception
// to trigger the jobs to abort. Let the caller decide if this exception
// should bubble up the stack or not.
def _maybeAbortJob(oldBuildResult, newBuildResult, reason) {
   // NOTE(miguel): Build status changed, let's check if the job was
   // aborted.  We should not be in an aborted state here but we have a
   // theory that the job was aborted but jenkins did not successfully
   // abort the thread because it was in the middle of uninterruptible
   // work like making an HTTP request. If that's the case we are going
   // to cause the job to abort from here to prevent new deploy-webapp
   // jobs from getting stuck.
   if (oldBuildResult != newBuildResult && newBuildResult == "ABORTED") {
      // Jenkins would throw a hudson.AbortException.  But to make
      // things more clear that the job is aborting itself we will
      // throw a different exception.  This exception will (should)
      // be handle by the caller to ensure we clean things up.
      throw new AbortDeployJob("Deploy was aborted. " + reason)
   }
}

// TODO(jacqueline): Make this a shared func with verifying smoke tests.
def verifyPromptConfirmed(prompt, buildmasterFailures=0) {
   if (currentBuild.result == "ABORTED") {
      // TODO(miguel): perhaps throw exception once we understand better why
      // we got into this function with an aborted job.
      echo("Job is aborted but we are still trying to verify prompt status "+
           "${prompt}. That's not great at all. Ping @deploy-support to "+
           "take a look.")
   }

   def status;
   while (status != "confirmed") {
      def buildResult = currentBuild.result
      status = buildmaster.pingForPromptStatus(prompt,
                                               params.GIT_REVISION)

      _maybeAbortJob(
         buildResult,
         currentBuild.result,
         "Build status changed while talking to buildmaster.",
      )

      // Because we want to know if the status of the build changed at
      // different points in this logic, we need to keep track of the
      // status of the job before meaningful actions such as before and
      // after an HTTP request happens or before and after the thread sleeps
      buildResult = currentBuild.result

      // If sun is down (even after a retry), we ask people to manually
      // check the result of their smoke tests.
      if (!status) {
         if (buildmasterFailures == 0) {
            buildmasterFailures += 1;
         } else {
            _alert(alertMsgs.BUILDMASTER_OUTAGE,
                   [step: "${prompt} prompt is confirmed",
                    logsUrl: env.BUILD_URL])
            _manualPromptCheck(prompt)
            return;
         }
      }

      // We care about consecutive failures, so if we get a status after
      // we've previously logged a buildmaster failure, reset to 0.
      if (status && buildmasterFailures == 1) {
         buildmasterFailures = 0;
      }

      // Continue pinging every 10 seconds until the prompt is confirmed
      if (status in ["unacknowledged", "acknowledged"]) {
        sleep(10);

         _maybeAbortJob(
            buildResult,
            currentBuild.result,
            "Build status changed while the thread was sleeping.",
         )
      }
   }
   return;
}

def _promoteServices() {  // call from webapp-root
    def cmd = ["deploy/set_default.py"];

    cmd += ["--previous-tag-name=${ROLLBACK_TO}",
             "--slack-channel=${SLACK_CHANNEL}",
             "--deployer-username=${DEPLOYER_USERNAME}",
             "--pretty-deployer-username=${PRETTY_DEPLOYER_USERNAME}"];

    if (params.SKIP_PRIMING) {
        cmd += ["--no-priming"];
    }

    def services = [];
    for (def i = 0; i < SERVICES.size(); i++) {
        services += ["${SERVICES[i]}=${NEW_VERSION}"]
    }

    cmd += [services.join(",")];
    // We talk to slack (you can tell via the --slack-channel arg above),
    // but we also talk to stackdriver to tell it monitoring statistics.
    // So we need both secrets.
    withSecrets.slackAndStackdriverAlertlibOnly() {
       exec(cmd);
    }
}


def _promote() {
   dir("webapp") {
      try {
         _promoteServices();

         // Once we finish (successfully) promoting, we tell buildmaster
         // that the default has been set.  (Currently this information
         // is only used in status; in the future it may be used for slack
         // messages as well.)
         buildmaster.notifyDefaultSet(params.GIT_REVISION, "finished");
      } catch (e) {
         notify.rethrowIfAborted(e);
         // Failure to promote is not a fatal error: we'll tell
         // people on slack so they can promote manually.  But
         // we don't want to abort the deploy, like a FAILURE would.
         echo("Marking unstable due to promotion failure: ${e}");
         currentBuild.result = "UNSTABLE";
      }
   }
}


def _monitor() {
   if (params.MONITORING_TIME == "0") {
      return;
   }

   cmd = ["deploy/monitor.py", NEW_VERSION,
          "--services=${SERVICES.join(',')}",
          "--monitor=${params.MONITORING_TIME}",
          "--slack-channel=${SLACK_CHANNEL}",
          "--monitor-error-is-fatal"];
   dir("webapp") {
      // We talk to slack (you can tell via the --slack-channel arg above),
      // but we also talk to stackdriver to tell it monitoring statistics.
      // So we need both secrets.
      withSecrets.slackAndStackdriverAlertlibOnly() {
         try {
            exec(cmd);
         } catch (e) {
            notify.rethrowIfAborted(e);
            // Failure to monitor is not a fatal error: we'll tell
            // people on slack so they can monitor manually.  But
            // we don't want to abort the deploy, like a FAILURE would.
            echo("Marking unstable due to monitoring failure: ${e}");
            currentBuild.result = "UNSTABLE";
         }
         // Once we finish monitoring, we tell buildmaster. We do not
         // differentiate between finishing with success vs finishing with
         // failure as that is not a blocker for continuing a deploy.
         // Notifying buildmaster of completion is used to trigger finish up.
         buildmaster.notifyMonitoringStatus(params.GIT_REVISION, "finished");
      }
   }
}


def _waitForSetDefaultStart() {
   try {
      withTimeout("1h") {
         dir("webapp") {
            exec(["deploy/wait_for_default.py", NEW_VERSION,
                  "--services=${SERVICES.join(',')}"]);
         }
      }
   } catch (e) {
      notify.rethrowIfAborted(e);
      echo("Failed to wait for new version: ${e}");
      _alert(alertMsgs.VERSION_NOT_CHANGED, []);
      return;
   }

   // Once we have started moving traffic, tell the buildmaster.
   // (This starts smoke tests, as well as appearing in status,
   // and perhaps more places in the future.)
   buildmaster.notifyDefaultSet(params.GIT_REVISION, "started");
}

def setDefaultAndMonitor() {
   withTimeout('120m') {
      _alert(alertMsgs.SETTING_DEFAULT,
             [combinedVersion: GIT_TAG,
              abortUrl: "${env.BUILD_URL}stop",
              logsUrl: logs.logViewerUrl(NEW_VERSION)]);

      // Note that while we start these jobs at the same time, the
      // monitor script has code to wait until well after the
      // promotion has finished before declaring monitoring finished.
      // The reason we do these in parallel -- and don't just do
      // _monitor() after _promote() -- is that not all instances
      // switch to the new version at the same time; we want to start
      // monitoring as soon as the first instance switches, not after
      // the last one does.  Similarly, we want to start notify the
      // buildmaster that set-default is underway while waiting for
      // it to finish.
      parallel(
         [ "promote": { _promote(); },
           "monitor": { _monitor(); },
           "wait-and-start-tests": { _waitForSetDefaultStart(); },
         ]);
   }
}

def finishWithSuccess() {
   withTimeout('10m') {
      try {
         dir("webapp") {
            // Create the git tag (if we actually deployed something somewhere).
            def existingTag = exec.outputOf(["git", "tag", "-l", GIT_TAG]);
            if (!existingTag) {
               // It's important we use toPrettyString for our
               // json to make parsing this easier.  In particular,
               // we know the end of the dict comes when we see
               // `^\S` (that is, the next unindented line).
               exec(["git", "tag", "-m",
                     "Deployed by ${PRETTY_DEPLOYER_USERNAME} " +
                     "from branch ${REVISION_DESCRIPTION}\n\n" +
                     "These services were deployed: ${SERVICES}\n\n" +
                     "v1 version dict: " +
                     "${new JsonBuilder(VERSION_DICT).toPrettyString()}",
                     GIT_TAG, params.GIT_REVISION]);
            }

            // Set our local version of master to be the same as the
            // origin master.  This is needed in cases when a previous
            // deploy set the local (jenkins) master to commit X, but
            // subsequent commits have moved the remote (github)
            // version of master to commit Y.  It also makes sure the
            // ref exists locally, so we can do the merge.
            exec(["git", "fetch", "origin",
                  "+refs/heads/master:refs/remotes/origin/master"]);
            exec(["git", "checkout", "master"]);
            exec(["git", "reset", "--hard", "origin/master"]);
            def headCommit = exec.outputOf(["git", "rev-parse", "HEAD"]);

            // The merge exits with rc > 0 if there were conflicts.
            echo("Merging ${REVISION_DESCRIPTION} into master");
            try {
               // TODO(benkraft): Mention REVISION_DESCRIPTION in the merge
               // message too, although this is usually a fast-forward.
               exec(["git", "merge", params.GIT_REVISION]);
            } catch (e) {
               echo("FATAL ERROR merging ${REVISION_DESCRIPTION}: ${e}");
               // Best-effort attempt to abort.  We ignore the status code.
               exec.statusOf(["git", "merge", "--abort"]);
               throw e;
            }

            // There's a race condition if someone commits to master
            // while this script is running, so check for that.
            try {
               // Push both the master branch and our new tag.
               exec(["git", "push", "origin",
                     "master", "+refs/tags/${GIT_TAG}:refs/tags/${GIT_TAG}"]);
            } catch (e) {
               echo("FATAL ERROR running 'git push': ${e}");
               // Best-effort attempt to reset.  We ignore the status code.
               exec.statusOf(["git", "reset", "--hard", headCommit]);
               throw e;
            }

            echo("Done merging ${REVISION_DESCRIPTION} into master");
         }
      } catch (e) {
         echo("FATAL ERROR merging to master: ${e}");
         _alert(alertMsgs.FAILED_MERGE_TO_MASTER,
                [combinedVersion: GIT_TAG,
                 branch: REVISION_DESCRIPTION]);
         throw e;
      }

      _alert(alertMsgs.SUCCESS,
             [combinedVersion: GIT_TAG, branch: REVISION_DESCRIPTION]);
      env.SENT_TO_SLACK = '1';
   }
}


def finishWithFailureNoRollback(why) {
   if (currentBuild.result == "ABORTED") {
      why = "the deploy was manually aborted";   // a prettier error message
      // Send Aborted Deployment Survey
      _sendAbortedDeploymentSurvey();
   } else {
      currentBuild.result = "FAILURE";
   }

    _alert(alertMsgs.FAILED_WITHOUT_ROLLBACK,
           [version: GIT_TAG,
            branch: REVISION_DESCRIPTION,
            services: SERVICES.join(', '),
            why: why]);
    env.SENT_TO_SLACK = '1';
}


def finishWithFailure(why) {
   if (currentBuild.result == "ABORTED") {
      why = "the deploy was manually aborted";   // a prettier error message
      // Send Aborted Deployment Survey
      _sendAbortedDeploymentSurvey();
   } else {
      currentBuild.result = "FAILURE";
   }

   // If our deploy fails after set-default, we always try a rollback, in
   // case traffic is split. If it turns out traffic was never migrated to
   // the new version our rollback script will determine that and exit early.
   def rollbackToAsVersion = ROLLBACK_TO.substring("gae-".length());

   withTimeout('40m') {
      try {
         _alert(alertMsgs.ROLLING_BACK,
                [rollbackToAsVersion: rollbackToAsVersion,
                 gitTag: GIT_TAG]);
         // rollback.py sends to slack and also to stackdriver for monitoring.
         withSecrets.slackAndStackdriverAlertlibOnly() {
            dir("webapp") {
               // During the rollback, we need the local version of code to be
               // of the good version for some of the rollback operation
               // e.g. update dispatch.yaml
               exec(["git", "checkout", "tags/${ROLLBACK_TO}"]);

               // We use --bad-dict instead of --bad here because
               // there's no git tag yet for the bad version:
               // it's only created when we end a deploy with
               // finishWithSuccess.
               exec(["deploy/rollback.py", "--good=${ROLLBACK_TO}",
                     "--bad-dict=${new JsonBuilder(VERSION_DICT).toString()}"]);
               // If the version we rolled back *to* is marked bad, warn
               // about that.
               def existingTag = exec.outputOf(["git", "tag", "-l",
                                                "${ROLLBACK_TO}-bad"]);
               if (existingTag) {
                  _alert(alertMsgs.ROLLED_BACK_TO_BAD_VERSION,
                         [rollbackToAsVersion: rollbackToAsVersion]);
               }
            }
         }
      } catch (e) {
         echo("Auto-rollback failed: ${e}");
         _alert(alertMsgs.ROLLBACK_FAILED,
                [rollbackToAsVersion: rollbackToAsVersion,
                 gitTag: GIT_TAG,
                 rollbackTo: ROLLBACK_TO]);
         notify.rethrowIfAborted(e);
      }

      _alert(alertMsgs.FAILED_WITH_ROLLBACK,
             [combinedVersion: GIT_TAG,
              branch: REVISION_DESCRIPTION,
              rollbackToAsVersion: rollbackToAsVersion,
              why: why]);
      env.SENT_TO_SLACK = '1';
   }
}


// We do promotes on master, to ease debugging and such.  Promote isn't
// CPU-bound, and we can have only one at a time, so it's not a problem.
onMaster('4h') {
   notify([slack: [channel: '#1s-and-0s-deploys',
                   sender: 'Mr Monkey',
                   emoji: ':monkey_face:',
                   // We don't need to notify on start because the buildmaster
                   // does it for us; on success the we explicitly send
                   // alertMsgs.SUCCESS.
                   when: ['FAILURE', 'UNSTABLE', 'ABORTED']],
           buildmaster: [sha: params.GIT_REVISION,
                         what: 'deploy-webapp']]) {
      try {
         stage("Merging in master") {
            mergeFromMaster();
         }
         stage("Initializing globals") {
            withVirtualenv.python3() {
               initializeGlobals();
            }
         }
      } catch (e) {
            echo("FATAL ERROR merging in master: ${e}");
            withVirtualenv.python3() {
               finishWithFailure(e.toString());
            }
            throw e;
      }

      try {
         stage("Deploy fastly-staging if needed") {
            // We only ever do this in this job, which applies to the
            // first deploy in the queue exclusively (rather than in
            // build-webapp.groovy, which happens in parallel to
            // every(ish) deploy in the queue), since we only have one
            // staging service in fastly.
            if ('fastly-khanacademy-compute' in SERVICES) {
               deployFastlyStaging();
            }
         }
         stage("Await first smoke test") {
            // NOTE: the first-smoke-test run has been going on for a
            // while, and until now it will have hit old code in the
            // fastly staging-service, not the new code that we just
            // deployed above.  This is unavoidable because we can run
            // multiple first-smoke-test runs at the same time (one
            // for every deploy in the deploy queue), but we only have
            // one "staging" fastly service, which we reserve for the
            // deploy at the front of the deploy queue.
            // TODO(csilvers): figure out a way to handle this better.
            if (SERVICES) {
               verifySmokeTestResults('first-smoke-test');
            }
         }
         stage("set-default confirmation") {
            verifyPromptConfirmed("set-default");
         }
      } catch (e) {
         echo("Deploy failed before setting default: ${e}");
         finishWithFailureNoRollback(e.toString())
         throw e;
      }

      if (SERVICES) {
         try {
            stage("Promoting and monitoring") {
               withVirtualenv.python3() {
                  setDefaultAndMonitor();
               }
            }
            // Unlike above, we do not need to verify second smoke tests
            // have finished. Buildmaster does that for us before prompting
            // a deployer to finish up.
            stage("Await finish-up confirmation") {
               verifyPromptConfirmed("finish-up");
            }
         } catch (e) {
            echo("FATAL ERROR promoting and monitoring and prompting: ${e}");
            withVirtualenv.python3() {
               finishWithFailure(e.toString());
            }
            throw e;
         }
      }

      stage("Merging to master") {
         finishWithSuccess();
      }
   }
}
