// Send live traffic to a new (static or dynamic) version of webapp.

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
// 2-3b. Alternately to 9 and 10, for a static-only deploy, tell
//    our existing prod server about the new static content we've
//    deployed.
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
//import vars.notify
//import vars.withSecrets
//import vars.withTimeout


// We do not allow concurrent builds; this should in theory also be enforced by
// the buildmaster, but we do it too as an extra safety check.
new Setup(steps

).addStringParam(
    "GIT_REVISION",
    """<b>REQUIRED</b>. The sha1 to deploy.""",
    ""


).addStringParam(
    "SERVICES",
    """<p>A comma-separated list of services we wish to change to the new
version (see below for options), or the special value "auto", which says to
choose the services to set-default automatically based on what files have
changed.  For example, you might specify "dynamic,static" to force setting
default on both GAE and GCS.</p>

<p>Here are the services:</p>
<ul>
  <li> <b>static</b>: The static (e.g. js) version. </li>
  <li> <b>dynamic</b>: The dynamic (e.g. py) version. </li>
</ul>

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
    "JOB_PRIORITY",
    """The priority of the job to be run (a lower priority means it is run
    sooner). The Priority Sorter plugin reads this parameter in to reorder jobs
    in the queue accordingly. Should be set to 3 if the job is depended on by
    the currently deploying branch, otherwise 6. Legal values are 1
    through 11. See https://jenkins.khanacademy.org/advanced-build-queue/
    for more information.""",
    "6"

).addBooleanParam(
    "SKIP_TESTS",
    """If set to true, proceed to deploying this branch even if tests have not
    yet completed, or have failed. Only use this with a very good reason, such
    as a site outage.""",
    false

).apply();

REVISION_DESCRIPTION = params.REVISION_DESCRIPTION ?: params.GIT_REVISION;

currentBuild.displayName = ("${currentBuild.displayName} " +
                            "(${REVISION_DESCRIPTION})");


// We set these to real values first thing below; but we do it within
// the notify() so if there's an error setting them we notify on slack.

// We purposefully hard-code this so people can't do sekret deploys. :-)
SLACK_CHANNEL = "#1s-and-0s-deploys";

// The `@<name>` we ping on slack as we go through the deploy.
DEPLOYER_USERNAME = null;

// The `<display_name>` we use to talk about the deployer (does not ping).
PRETTY_DEPLOYER_USERNAME = null;

// The tag we will use to tag this deploy.
GIT_TAG = null;

// The list of services to which to deploy: currently a subset of
// ["dynamic", "static"].
SERVICES = null;

// The git-tagname to roll back to if this deploy fails.  It
// should be the git tag of the current-live deploy when this
// script is first invoked.
ROLLBACK_TO = null;

// The "permalink" url used to access code deployed.
// (That is, version-dot-khan-academy.appspot.com, not www.khanacademy.org).
DEPLOY_URL = null;

// The new service-version for any services we are deploying.
NEW_VERSION = null;

// This holds the arguments to _alert.  It a groovy struct imported at runtime.
alertMsgs = null;

// Remind people after 30m, 45m, and 55m, then timeout at 60m.
// Unless you ask for longer!  Then we give you 6 hours, pinging
// every hour for the most part.
// TODO(benkraft, INFRA-2228): Make this more configurable, especially after
// the fact rather than at queue-time.
_PROMPT_TIMES = (
   params.WAIT_LONGER
      // We still ping you every hour, just to make sure.
      ? [60, 120, 180, 240, 300, 330, 345, 355, 360]
      : [30, 45, 55, 60]);


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
   withSecrets() {     // to talk to slack
      sh("echo ${exec.shellEscape(msg)} | ${exec.shellEscapeList(args)}");
   }
}


def _inputWithPrompts(message, id, warningsInMinutes) {
   // We prompt (to remind people to do the input), at
   // warningsInMinutes[0..-1].  At the last warningsInMinutes we abort.
   for (def i = 0; i < warningsInMinutes.size(); i++) {
      def sleepTime = (warningsInMinutes[i] -
                       (i > 0 ? warningsInMinutes[i - 1] : 0));
      try {
         withTimeout("${sleepTime}m") {
            input(message: message, id: id);
         }
         return;      // we only get here if they clicked "ok" on the input
      } catch (e) {
         sleep(1);   // give the watchdog a chance to notice an abort
         if (currentBuild.result == "ABORTED") {
            // Means that we aborted while running this, which the
            // watchdog (in vars/notify.groovy) noticed.  We want to
            // continue with the abort process.
            throw e;
         } else if (i == warningsInMinutes.size() - 1) {
            // Means we're at the last warningsInMinutes.  We're done warning.
            throw e;
         } else {
            // Means we reached the next timeout, so say we're waiting.
            withTimeout('1m') {
               _alert(alertMsgs.STILL_WAITING,
                      [action: message,
                       minutesSoFar: warningsInMinutes[i],
                       minutesRemaining: (warningsInMinutes[-1] -
                                          warningsInMinutes[i])]);
            }
            // Now we'll continue with the `while` loop, and wait some more.
         }
      }
   }
}


def mergeFromMasterAndInitializeGlobals() {
   withTimeout('1h') {    // should_deploy builds files, which can take forever
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
         if (!params.GIT_REVISION ==~ /[0-9a-fA-F]{40}/) {
            notify.fail("GIT_REVISION was not a sha!");
         }

         kaGit.safeSyncToOrigin("git@github.com:Khan/webapp",
                                params.GIT_REVISION);

         dir("webapp") {
            def revList = exec.outputOf(
                  ["git", "rev-list", "${params.GIT_REVISION}..master"])
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
         sh("make deps");

         // Let's do a sanity check.
         def headSHA1 = exec.outputOf(["git", "rev-parse", "HEAD"]);
         if (params.GIT_REVISION != headSHA1) {
            notify.fail("Internal error: " +
                        "HEAD does not point to the deploy-branch");
         }

         def shouldDeployArgs = ["deploy/should_deploy.py"];

         // TODO(benkraft): Extract the resolution of services into a util
         if (params.SERVICES == "auto") {
            try {
               SERVICES = exec.outputOf(shouldDeployArgs).split("\n");
            } catch(e) {
               notify.fail("Automatic detection of what to deploy failed. " +
                           "You can likely work around this by setting " +
                           "SERVICES on your deploy; see " +
                           "${env.BUILD_URL}rebuild for documentation, and " +
                           "`sun: help flags` for how to set it.  If you " +
                           "aren't sure, ask dev-support for help!");
            }
         } else {
            SERVICES = params.SERVICES.split(",");
         }
         if (SERVICES == [""]) {
            // Either of the above could be [""], if we should deploy nothing.
            // We want to use [] instead: [""] would mean deploying a single
            // nameless service or something.
            SERVICES = [];
         }
         echo("Deploying to the following services: ${SERVICES.join(', ')}");

         NEW_VERSION = exec.outputOf(["make", "gae_version_name"]);
         DEPLOY_URL = "https://${NEW_VERSION}-dot-khan-academy.appspot.com";

         def currentVersionTag = exec.outputOf(
            ["deploy/current_version.py", "--git-tag"]);
         def currentVersionParts = exec.outputOf(
            ["deploy/git_tags.py", currentVersionTag]).split("\n");

         // If this deploy fails, we want to roll back to the version
         // that was active when the deploy started.  That's now!
         ROLLBACK_TO = currentVersionTag;

         // The new version will be like the old version, except replacing each
         // service in SERVICES with NEW_VERSION.
         def newVersionParts = [];
         for (def i = 0; i < currentVersionParts.size(); i++) {
            def serviceAndVersion = currentVersionParts[i];
            if (serviceAndVersion) {
               def service = serviceAndVersion.split("=")[0];
               if (service in SERVICES) {
                  newVersionParts += ["${service}=${NEW_VERSION}"];
               } else if (service == "static" && "dynamic" in SERVICES) {
                  // We deploy a "copied" static version anytime we deploy
                  // dynamic.
                  // TODO(benkraft): Handle this in a less bespoke way.
                  newVersionParts += ["${service}=${NEW_VERSION}"];
               } else {
                  newVersionParts += [serviceAndVersion];
               }
            }
         }
         // Now combine those into a git tag.
         GIT_TAG = exec.outputOf(["deploy/git_tags.py"] + newVersionParts);
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
         if (status in ["succeeded", "aborted", "failed", "killed"]) {
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
      msg += ("If you have aleady done `sun: set-default` then " +
              "click Proceed to continue with your deploy. Or, " +
              "complete your manual testing on  ${DEPLOY_URL} and let " +
              "us know when you're ready.");
   }
   input(message: msg, id: "ConfirmSetDefaultPrompt");
   return;
}

// TODO(jacqueline): Make this a shared func with verifying smoke tests.
def verifyPromptConfirmed(prompt, buildmasterFailures=0) {
   def status;
   while (status != "confirmed") {
      status = buildmaster.pingForPromptStatus(prompt,
                                               params.GIT_REVISION)

      // If sun is down (even after a retry), we ask people to manually
      // check the result of their smoke tests.
      if (!status) {
         if (buildmasterFailures == 0) {
            buildmasterFailures += 1;
         } else {
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
      }
   }
   return;
}

def _promoteServices() {  // call from webapp-root
    def cmd = ["deploy/set_default.py"];

    // If we're not deploying a new version of static content, the Sentry
    // version number should not be updated
    if (!('static' in SERVICES)) {
        cmd += ["--keep-error-version"];
    }

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
    exec(cmd);
}


def _promote() {
   withSecrets() {
      dir("webapp") {
         try {
            _promoteServices();

            // Once we finish (successfully) promoting, we tell buildmaster
            // that the default has been set.  (Currently this information
            // is only used in status; in the future it may be used for slack
            // messages as well.)
            buildmaster.notifyDefaultSet(params.GIT_REVISION, "finished");
         } catch (e) {
            sleep(1);   // give the watchdog a chance to notice an abort
            if (currentBuild.result == "ABORTED") {
               // Means that we aborted while running this, which
               // the watchdog (in vars/notify.groovy) noticed.
               // We want to continue with the abort process.
               throw e;
            }
            // Failure to promote is not a fatal error: we'll tell
            // people on slack so they can promote manually.  But
            // we don't want to abort the deploy, like a FAILURE would.
            echo("Marking unstable due to promotion failure: ${e}");
            currentBuild.result = "UNSTABLE";
         }
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
   withSecrets() {
      dir("webapp") {
         try {
            exec(cmd);
         } catch (e) {
            sleep(1);   // give the watchdog a chance to notice an abort
            if (currentBuild.result == "ABORTED") {
               // Means that we aborted while running this, which
               // the watchdog (in vars/notify.groovy) noticed.
               // We want to continue with the abort process.
               throw e;
            }
            // Failure to monitor is not a fatal error: we'll tell
            // people on slack so they can monitor manually.  But
            // we don't want to abort the deploy, like a FAILURE would.
            echo("Marking unstable due to monitoring failure: ${e}");
            currentBuild.result = "UNSTABLE";
         }
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
      echo("Failed to wait for new version: ${e}");
      _alert(alertMsgs.VERSION_NOT_CHANGED, []);
      return;
   }

   // Once we have started moving traffic, tell the buildmaster.
   // (This starts smoke tests, as well as appearing in status,
   // and perhaps more places in the future.)
   buildmaster.notifyDefaultSet(params.GIT_REVISION, "started");
}

def _switchDatastoreBigqueryAdapterJar() {
   // we switch the "$NewDeployVersion.jar" file to
   // gs://khanalytics/datastore_bigquery_adapter.jar to make it live
   try {
      withTimeout("10m") {
         dir("webapp/dataflow/datastore_bigquery_adapter") {
            withEnv(["VERSION=${NEW_VERSION}"]) {
               exec(["./gradlew", "switch_deploy_jar"])
            }
         }
      }
   } catch (e) {
      echo("Failed to switch datastore_bigquery_adapter.jar: ${e}");
      _alert(alertMsgs.DATASTORE_BIGQUERY_ADAPTER_JAR_NOT_SWITCHED,
             [newVersion: NEW_VERSION]);
      return;
   }
}

def setDefaultAndMonitor() {
   withTimeout('120m') {
      _alert(alertMsgs.SETTING_DEFAULT,
             [combinedVersion: GIT_TAG,
              abortUrl: "${env.BUILD_URL}stop"]);

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
           "switch-datastore-bigquery-adapter-jar":
               { _switchDatastoreBigqueryAdapterJar; },
         ]);
   }
}

def promptToFinish() {
   withTimeout('1m') {
      def logsUrl = (
         "https://console.developers.google.com/project/khan-academy/logs" +
         "?service=appengine.googleapis.com&key1=default&key2=${NEW_VERSION}");
      def interpolationArgs = [logsUrl: logsUrl,
                               combinedVersion: GIT_TAG,
                               finishUrl: "${env.BUILD_URL}input/",
                               abortUrl: "${env.BUILD_URL}stop",
                              ];
      // The build is unstable if monitoring detected problems (or died).
      if (currentBuild.result == "UNSTABLE") {
         _alert(alertMsgs.FINISH_WITH_WARNING, interpolationArgs);
      } else {
         _alert(alertMsgs.FINISH_WITH_NO_WARNING, interpolationArgs);
      }
   }

   // Remind people (normally 30m, 45m, 55m, then timeout at 60m, but see
   // _PROMPT_TIMES for details).
   _inputWithPrompts("Finish up?", "Finish", _PROMPT_TIMES);
}


def finishWithSuccess() {
   withTimeout('10m') {
      try {
         dir("webapp") {
            // Create the git tag (if we actually deployed something somewhere).
            if (SERVICES) {
               def existingTag = exec.outputOf(["git", "tag", "-l", GIT_TAG]);
               if (!existingTag) {
                  exec(["git", "tag", "-m",
                        "Deployed to appengine from branch " +
                        "${REVISION_DESCRIPTION}",
                        GIT_TAG, params.GIT_REVISION]);
               }
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
               exec(["git", "push", "--tags", "origin", "master"]);
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
         dir("webapp") {
            exec(["deploy/rollback.py",
                  "--bad=${GIT_TAG}", "--good=${ROLLBACK_TO}"]);
            // If the version we rolled back *to* is marked bad, warn
            // about that.
            def existingTag = exec.outputOf(["git", "tag", "-l",
                                             "${ROLLBACK_TO}-bad"]);
            if (existingTag) {
               _alert(alertMsgs.ROLLED_BACK_TO_BAD_VERSION,
                     [rollbackToAsVersion: rollbackToAsVersion]);
            }
            // rollback to datastore_bigquery_adapter.$dynamicVersion.jar
            def dynamicVersion = exec.outputOf(
               ["deploy/git_tags.py", "--service",
               "dynamic", ROLLBACK_TO]);
            dir("dataflow/datastore_bigquery_adapter") {
               withEnv(["VERSION=${dynamicVersion}"]) {
                  exec(["./gradlew", "rollback_jar"])
               }
            }
         }
      } catch (e) {
         echo("Auto-rollback failed: ${e}");
         _alert(alertMsgs.ROLLBACK_FAILED,
               [rollbackToAsVersion: rollbackToAsVersion,
                gitTag: GIT_TAG,
                rollbackTo: ROLLBACK_TO]);
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
                         what: 'deploy-webapp'],
           aggregator: [initiative: 'infrastructure',
                        when: ['SUCCESS', 'BACK TO NORMAL',
                        'FAILURE', 'ABORTED', 'UNSTABLE']]]) {
      stage("Merging in master") {
         mergeFromMasterAndInitializeGlobals();
      }

      if (SERVICES) {
         try {
            stage("Await first smoke test and set-default confirmation") {
               if (!params.SKIP_TESTS) {
                  verifySmokeTestResults('first-smoke-test');
               }
               verifyPromptConfirmed("set-default");
            }
         } catch (e) {
            echo("Deploy failed before setting default: ${e}");
            finishWithFailureNoRollback(e.toString())
            throw e;
         }

         try {
            stage("Promoting and monitoring") {
               setDefaultAndMonitor();
            }
            stage("Prompt 2") {
               if (!params.SKIP_TESTS) {
                  verifySmokeTestResults('second-smoke-test');
               }
               buildmaster.notifyWaiting('deploy-webapp', params.GIT_REVISION,
                                         'waiting Finish');
               promptToFinish();
            }
         } catch (e) {
            echo("FATAL ERROR promoting and monitoring and prompting: ${e}");
            finishWithFailure(e.toString());
            throw e;
         }
      }

      stage("Merging to master") {
         finishWithSuccess();
      }
   }
}
