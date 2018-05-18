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
    "REVISION_DESCRIPTION",
    """Set by the buildmaster to give a more human-readable description
of the GIT_REVISION, especially if it is a commit rather than a branch.
Defaults to GIT_REVISION.""",
    ""

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

// The dynamic-deploy and static-deploy version-names.
GAE_VERSION = null;
GCS_VERSION = null;
// This is `GIT_TAG` but without the `gae-` prefix.
COMBINED_VERSION = null;

// This holds the arguments to _alert.  It a groovy struct imported at runtime.
alertMsgs = null;


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
//    _alert(alert.STARTING_DEPLOY, [deployType: "static", branch: GIT_COMMIT]);
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
   for (def i = 0; i < warningsInMinutes.size; i++) {
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
         } else if (i == warningsInMinutes.size - 1) {
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
      if (!DEPLOYER_USERNAME.startsWith("@") &&
          !DEPLOYER_USERNAME.startsWith("<@")) {
         DEPLOYER_USERNAME = "@${DEPLOYER_USERNAME}";
      }

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
                                params.GIT_REVISION)

         dir("webapp") {
            if (exec.outputOf(
                  ["git", "rev-list", "${params.GIT_REVISION}..master"])) {
               // We do an extra safety check, that GIT_REVISION
               // is a valid sha ahead of master.
               notify.fail("GIT_REVISION ${params.GIT_REVISION} is " +
                              "behind master!")
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

         // If this deploy fails, we want to roll back to the version
         // that was active when the deploy started.  That's now!
         ROLLBACK_TO = exec.outputOf(["deploy/current_version.py",
                                      "--git-tag"]);

         def gaeVersionName = exec.outputOf(["make", "gae_version_name"]);

         if ("static" in SERVICES && !("dynamic" in SERVICES)) {
            GAE_VERSION = exec.outputOf(["deploy/git_tags.py", "--gae",
                                         ROLLBACK_TO]);
            GCS_VERSION = gaeVersionName;
            DEPLOY_URL = "https://static-${GCS_VERSION}.khanacademy.org";
         } else {
            GAE_VERSION = gaeVersionName;
            GCS_VERSION = gaeVersionName;
            DEPLOY_URL = "https://${GAE_VERSION}-dot-khan-academy.appspot.com";
         }

         GIT_TAG = exec.outputOf(["deploy/git_tags.py",
                                  GAE_VERSION, GCS_VERSION]);
         // The same as GIT_TAG, but without the "gae-" prefix.
         COMBINED_VERSION = GIT_TAG.substring("gae-".length());
      }
   }
}


def promptForSetDefault() {
   withTimeout('5m') {
      // Send the changelog!
      withSecrets() {
         dir("webapp") {
            // Prints the diff ROLLBACK_TO..GIT_REVISION (i.e. changes since
            // the currently live version).
            exec(["deploy/chat_messaging.py", ROLLBACK_TO, GIT_REVISION,
                  // We omit the deployer username; the next message has an
                  // at-mention in it already.
                  "-o", SLACK_CHANNEL]);
         }
      }
      // The CMS endpoints must be handled on the vm module. However,
      // the rules in dispatch.yaml only match *.khanacademy.org,
      // so the routing doesn't work in dynamic deploys (which are
      // accessed through *.appspot.com) before the new version is
      // set as default (but static-only deploys do work). In dynamic
      // deploys, we therefore show a link directly to the vm module.
      // TODO(aasmund): Remove when we have a better vm deployment
      def maybeVmMessage = (
         ("dynamic" in SERVICES)
         ? "Note that if you want to test the CMS or the publish pages " +
           "(`/devadmin/content` or `/devadmin/publish`), " +
           "you need to do so on the " +
           "<https://${GAE_VERSION}-dot-vm-dot-khan-academy.appspot.com|" +
           "vm module> instead. "
         : "");
      _alert(alertMsgs.MANUAL_TEST_THEN_SET_DEFAULT,
             [deployUrl: DEPLOY_URL,
              maybeVmMessage: maybeVmMessage,
              setDefaultUrl: "${env.BUILD_URL}input/",
              abortUrl: "${env.BUILD_URL}stop",
              combinedVersion: COMBINED_VERSION,
              branch: params.REVISION_DESCRIPTION]);
   }

   // Remind people after 30m, 45m, and 55m, then timeout at 60m.
   _inputWithPrompts("Set default?", "SetDefault", [30, 45, 55, 60]);
}


def _promote() {
   def cmd = ["deploy/set_default.py",
              GAE_VERSION,
              "--slack-channel=${SLACK_CHANNEL}",
              "--deployer-username=${DEPLOYER_USERNAME}"];
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
                     string(name: 'SLACK_CHANNEL', value: SLACK_CHANNEL),
                     string(name: 'GIT_REVISION', value: params.GIT_REVISION),
                     booleanParam(name: 'FAILFAST', value: false),
                     string(name: 'DEPLOYER_USERNAME',
                            value: DEPLOYER_USERNAME),
                     string(name: 'REVISION_DESCRIPTION',
                            value: params.REVISION_DESCRIPTION),
                  ]);
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

   cmd = ["deploy/monitor.py", GAE_VERSION, GCS_VERSION,
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


def setDefaultAndMonitor() {
   withTimeout('120m') {
      _alert(alertMsgs.SETTING_DEFAULT,
             [combinedVersion: COMBINED_VERSION,
              abortUrl: "${env.BUILD_URL}stop"]);

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
   withTimeout('1m') {
      def logsUrl = (
         "https://console.developers.google.com/project/khan-academy/logs" +
         "?service=appengine.googleapis.com&key1=default&key2=${GAE_VERSION}");
      def interpolationArgs = [logsUrl: logsUrl,
                               combinedVersion: COMBINED_VERSION,
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

   // Remind people after 30m, 45m, and 55m, then timeout at 60m.
   // TODO(csilvers): let this timeout be configurable?  Then if you
   // want to run a new version live for a few hours to collect some
   // data, and automatically revert back to the previous version when
   // you're done, you could just set a timeout for '5h' or whatever
   // and let the timeout-trigger abort the deploy.
   _inputWithPrompts("Finish up?", "Finish", [30, 45, 55, 60]);
}


def finishWithSuccess() {
   withTimeout('10m') {
      try {
         dir("webapp") {
            // Create the git tag (if we actually deployed something somewhere).
            if ("dynamic" in SERVICES || "static" in SERVICES) {
               def existingTag = exec.outputOf(["git", "tag", "-l", GIT_TAG]);
               if (!existingTag) {
                  exec(["git", "tag", "-m",
                        "Deployed to appengine from branch " +
                        "${REVISION_DESCRIPTION}",
                        GIT_TAG, params.GIT_REVISION]);
               }
            }

            // When any of our datastore models or dataflow code changes, we
            // need to rebuild the binary we use to export out datastore models
            // to bigquery.
            // We should do this more selectively (i.e. only when specific
            // relevant files changed), but this is quick (< 30s), so to be
            // safe as a stopgap measure we just do it all the time.
            // TODO(colin): do this only in response to a SERVICES flag
            // set by a should_deploy rule, as for "dynamic" and "static" above.
            dir("dataflow/datastore_bigquery_adapter") {
                exec(["./gradlew", "build_and_upload_jar"])
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
                [combinedVersion: COMBINED_VERSION,
                 branch: REVISION_DESCRIPTION]);
         throw e;
      }

      _alert(alertMsgs.SUCCESS,
             [combinedVersion: COMBINED_VERSION, branch: REVISION_DESCRIPTION]);
      env.SENT_TO_SLACK = '1';
   }
}


def finishWithFailure(why) {
   if (currentBuild.result == "ABORTED") {
      why = "the deploy was manually aborted";   // a prettier error message
   } else {
      currentBuild.result = "FAILURE";
   }

   def rollbackToAsVersion = ROLLBACK_TO.substring("gae-".length());

   withTimeout('20m') {
      try {
         def currentGAEGitTag = exec.outputOf(
            // Don't trust git here -- we likely haven't merged to master yet
            // even if we did set default.
            ["webapp/deploy/current_version.py", "--git-tag", "--no-git"]);

         if (currentGAEGitTag != GIT_TAG) {
            echo("No need to roll back: our deploy did not succeed");
            echo("Us: ${GIT_TAG}, current: ${currentGAEGitTag}, " +
                 "rollback-to: ${ROLLBACK_TO}");
            _alert(alertMsgs.FAILED_WITHOUT_ROLLBACK,
                   [combinedVersion: COMBINED_VERSION,
                    branch: REVISION_DESCRIPTION,
                    why: why]);
            env.SENT_TO_SLACK = '1';
            return
         }
      } catch (e) {
         echo("Couldn't get current version: ${e}.  Rolling back to be safe.");
      }

      // Have to roll back.
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
         }
      } catch (e) {
         echo("Auto-rollback failed: ${e}");
         _alert(alertMsgs.ROLLBACK_FAILED,
                [rollbackToAsVersion: rollbackToAsVersion,
                 gitTag: GIT_TAG,
                 rollbackTo: ROLLBACK_TO]);
      }

      _alert(alertMsgs.FAILED_WITH_ROLLBACK,
             [combinedVersion: COMBINED_VERSION,
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

      if ("dynamic" in SERVICES || "static" in SERVICES) {
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
