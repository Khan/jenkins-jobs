// Upload a new (static or dynamic) version of webapp to App Engine and/or GCS

// Uploading a version is a complex process, and this is a complex script.
// For more high-level information on deploys, see
//    https://docs.google.com/document/d/1Zr0wwzbvPkmN_BFAsrMPZhccIb0HHJUgmLZfBUWYFvA/edit
// For more details on how this is used as a part of our build process, see the
// buildmaster (github.com/Khan/buildmaster) and its design docs:
//     https://docs.google.com/document/d/1utyUMMBOQvt4o3W_yl_89KdlNdAZUYsnSN_2FjWL-wA/edit#heading=h.kzjq9eunc7bh

// By the time we are run, the buildmaster has already merged master, the
// branch to be deployed, and perhaps some translations updates, and passed
// that merge commit as our GIT_REVISION.  It's also running tests in parallel
// (or already has).  Here's what we do, some of it in parallel:
//
// 1. Determine what kind of deploy we are running: full, static, dynamic,
//    or tools-only.  This is determined by whether we have changed any
//    files that affect the server running on GAE, and whether we have
//    changed any files (or their dependencies) that are deployed to GCS.
//
// 2. Build all the artifacts to be deployed (differs depending on deploy
//    kind).
//
// 3. Deploy new server-related code to GAE, if appropriate.
//
// 4. Deploy new statically-served files to GCS, if appropriate.
//
// 5. Kick off end-to-end tests on the newly deployed version.

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
//import vars.onWorker
//import vars.withSecrets
//import vars.withTimeout


new Setup(steps

// We only need to lock out for promoting; builds are fine to do in parallel.
).allowConcurrentBuilds(

).addStringParam(
    "GIT_REVISION",
    """<b>REQUIRED</b>. The sha1 to deploy.""",
    ""

).addStringParam(
    "BASE_REVISION",
    """<p>Deploy everything that has happened since this revision.</p>

    <p>This only matters if SERVICES is "auto".  In that case, we deploy to
    static if there have been changes to static files since this revision.
    (So it must be a successfully built revision.)</p>""",
    ""

).addStringParam(
    "COPY_FROM_REVISION",
    """<p>Use this revision when copying static or dynamic version names.</p>

    <p>For the most part, this will be the same as BASE_REVISION, because
    most revisions will typically have a valid dynamic and static version to
    reference. However, in the case that the BASE_REVISION was a tools-only
    deploy, this will be the most recent non-tools-only revision.</p>""",
    ""

).addStringParam(
    "SERVICES",
    """<p>A comma-separated list of services we wish to deploy (see below for
options), or the special value "auto", which says to choose the services to
deploy automatically based on what files have changed.  For example, you might
specify "dynamic,static" to force a full deploy to GAE and GCS.</p>

<p>Here are the services:</p>
<ul>
  <li> <b>dynamic</b>: Upload dynamic (e.g. py) files to GAE. </li>
  <li> <b>static</b>: Upload static (e.g. js) files to GCS. </li>
  <li> <b>kotlin-routes</b>: webapp's services/kotlin-routes/. </li>
  <li> <b>content-editing</b>: webapp's services/content-editing/. </li>
</ul>

<p>You can specify the empty string to deploy to none of these services, like
if you just change the Makefile.  (Do not do this lightly!)  You may wonder:
why do you need to run this job at all if you're just changing the Makefile?
Well, it's the only way of getting files into the master branch, so you do a
'quasi' deploy that just merges to master.</p>

<p>TODO(benkraft): In principle we shouldn't need this job at all for that case
-- we should be able to skip straight to deploy-webapp.  But right now we don't
know that we can do that at the right time.</p>
""",
    "auto"

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
present on GCS.  Note that this does not override <code>SERVICES</code>;
we only force GAE (or GCS) if we're actually deploying to it.""",
    false

).addBooleanParam(
    "SKIP_PRIMING",
    """If set to True, we won't try to prefill any caches when deploying the
version.  This will likely cause the version to be unusable until such time as
priming is run (perhaps in deploy-webapp, unless the same option is set).""",
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
    through 11. See https://jenkins.khanacademy.org/advanced-build-queue/ for
    more information.""",
    "6"
).apply();

REVISION_DESCRIPTION = params.REVISION_DESCRIPTION ?: params.GIT_REVISION;

currentBuild.displayName = ("${currentBuild.displayName} " +
                            "(${REVISION_DESCRIPTION})");


// We set these to real values first thing below; but we do it within
// the notify() so if there's an error setting them we notify on slack.

// The `@<name>` we ping on slack as we go through the deploy.
DEPLOYER_USERNAME = null;

// The list of services to which to deploy: currently a subset of
// ["dynamic", "static", "kotlin-routes", "content-editing"].
SERVICES = null;

// The "permalink" url used to access code deployed.
// (That is, version-dot-khan-academy.appspot.com, not www.khanacademy.org).
DEPLOY_URL = null;

// The version-name corresponding to COPY_FROM_REVISION_VERSION
// (only set if COPY_FROM_REVISION is set).
COPY_FROM_REVISION_VERSION = null;

// The new version number (for whichever services will be deployed).
NEW_VERSION = null;

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
//    _alert(alertMsgs.SETTING_DEFAULT, [combinedVersion: COMBINED_VERSION,
//                                       abortUrl: "${env.BUILD_URL}stop"]);
//
// Should be run under a node in the workspace-root directory.
def _alert(def slackArgs, def interpolationArgs) {
   // NOTE(benkraft): We don't include any at-mention here, because in neither
   // case is it useful.  When we succeed, there may not yet be any action
   // required, so there's no need to ping.  When we fail, the buildmaster will
   // already do the at-mention, so there's no need to duplicate it.

   // Do string interpolation on the text.
   def msg = _interpolateString(slackArgs.text, interpolationArgs);

   args = ["jenkins-jobs/alertlib/alert.py",
           "--slack=${params.SLACK_CHANNEL}",
           "--chat-sender=Mr Monkey",
           "--icon-emoji=:monkey_face:",
           "--severity=${slackArgs.severity}",
          ];
   if (params.SLACK_THREAD) {
      args += ["--slack-thread=${params.SLACK_THREAD}"];
   }
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

      // TODO(csilvers): have these return an error message instead
      // of alerting themselves, so we can use notify.fail().
      withEnv(["SLACK_CHANNEL=${params.SLACK_CHANNEL}",
               "SLACK_THREAD=${params.SLACK_THREAD}",
               "DEPLOYER_USERNAME=${DEPLOYER_USERNAME}",
               "JOB_PRIORITY=${params.JOB_PRIORITY}"]) {
         kaGit.safeSyncToOrigin("git@github.com:Khan/webapp",
                                params.GIT_REVISION)
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
         // Diff against BASE_REVISION if set.  We only allow this when
         // building: for promotion the only correct thing to do is to diff
         // against the currently live version, and the consequences of doing
         // something else are greater, so we prohibit the dangerous thing.
         if (params.BASE_REVISION) {
            shouldDeployArgs += ["--from-commit", params.BASE_REVISION]
         }

         // We also set the COPY_FROM_REVISION_VERSION, for later.
         if (params.COPY_FROM_REVISION) {
            COPY_FROM_REVISION_VERSION = exec.outputOf(
               ["make", "gae_version_name",
                "VERSION_NAME_GIT_REVISION=${params.COPY_FROM_REVISION}"]);
         }

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

         // Phone home to buildmaster about the services we're deploying to
         buildmaster.notifyServices(params.GIT_REVISION,
                                   SERVICES.join(', ') ?: "tools-only");

         NEW_VERSION = exec.outputOf(["make", "gae_version_name"]);
         // Normally, the deploy url will be the new version's appspot URL --
         // we use these URLs for testing even for static versions.  But, if we
         // have a tools-only version, there is no such version anywhere on app
         // engine, and the URL won't work, so we fall back to the base
         // revision (i.e. either COPY_FROM_REVISION_VERSION or the live default).
         // Either way, we use an appspot URL, for consistency and to make sure
         // the appspot URL cases in e2e-test get tested.
         def urlVersion = NEW_VERSION;
         if (!SERVICES) {
            if (COPY_FROM_REVISION_VERSION) {
               urlVersion = COPY_FROM_REVISION_VERSION;
            } else {
               def urlVersions = exec.outputOf(["deploy/current_version.py",
                                                "--service", "dynamic"]
                                               ).split("\n").sort();
               // If there are multiple current versions, we grab the latest.
               urlVersion = urlVersions[-1];
            }
         }
         DEPLOY_URL = "https://${urlVersion}-dot-khan-academy.appspot.com";
      }
   }
}


// This should be called from within a node().
def deployToGAE() {
   if (!("dynamic" in SERVICES)) {
      return;
   }
   def args = ["deploy/deploy_to_gae.py",
               "--no-browser", "--no-up",
               "--slack-channel=${params.SLACK_CHANNEL}",
               "--deployer-username=${DEPLOYER_USERNAME}",
               // We don't send the changelog in a build-only context -- there
               // may be many builds afoot and it is too confusing.  We'll send
               // it in promote instead.
               "--suppress-changelog"];
   args += params.SLACK_THREAD ? [
      "--slack-thread=${params.SLACK_THREAD}"] : [];
   args += params.FORCE ? ["--force-deploy"] : [];
   args += params.SKIP_PRIMING ? ["--skip-priming"] : [];
   args += params.ALLOW_SUBMODULE_REVERTS ? ["--allow-submodule-reverts"] : [];

   withSecrets() {     // we need to deploy secrets.py.
      dir("webapp") {
         // Increase the the maximum number of open file descriptors.
         // This is necessary because kake keeps a lockfile open for
         // every file it's compiling, and that can easily be
         // thousands of files.  4096 is as much as linux allows.
         // We also use python -u to get maximally unbuffered output.
         // TODO(csilvers): do we need secrets for this part?
         sh("ulimit -S -n 4096; python -u ${exec.shellEscapeList(args)}");
      }
   }
}


// This should be called from within a node().
def deployToGCS() {
   // We always "deploy" to gcs, even if it's not in SERVICES, though
   // if it's not then the gcs-deploy is very simple.
   def args = ["deploy/deploy_to_gcs.py", NEW_VERSION,
               "--slack-channel=${params.SLACK_CHANNEL}",
               "--deployer-username=${DEPLOYER_USERNAME}",
               // Same as for deploy_to_gae, we suppress the changelog for now.
               "--suppress-changelog"];
   if (!("static" in SERVICES)) {
      if (COPY_FROM_REVISION_VERSION) {
         args += ["--copy-from=${COPY_FROM_REVISION_VERSION}"];
      } else {
         args += ["--copy-from=default"];
      }
   } else {
       // Since we're deploying new static code, we should upload the updated
       // sourcemap files to our error reporting system
       args += ["--upload-sourcemaps"]
   }

   args += params.SLACK_THREAD ? [
      "--slack-thread=${params.SLACK_THREAD}"] : [];
   args += params.FORCE ? ["--force"] : [];

   withSecrets() {     // TODO(csilvers): do we actually need secrets?
      dir("webapp") {
         // Increase the the maximum number of open file descriptors.
         // This is necessary because kake keeps a lockfile open for
         // every file it's compiling, and that can easily be
         // thousands of files.  4096 is as much as linux allows.
         // We also use python -u to get maximally unbuffered output.
         sh("ulimit -S -n 4096; python -u ${exec.shellEscapeList(args)}");
      }
   }
}


// This should be called from within a node().
def deployToKotlinRoutes() {
   if (!("kotlin-routes" in SERVICES)) {
      return;
   }

   withSecrets() {     // TODO(benkraft): do we actually need secrets?
      dir("webapp") {
         // HACK: If we sh() in a directory d, jenkins creates a sibling
         // directory d@tmp.  If d is a subdirectory of webapp, this
         // confuses deploy_to_gae.py's local changes check.  So instead
         // we have the shell do the cd.
         // TODO(colin): can we now run this from webapp root and avoid the
         // cd?
         withEnv(["VERSION=${NEW_VERSION}"]) {
            sh("cd services/kotlin-routes && ./gradlew appengineDeploy");
         }
      }
   }
}


// This should be called from within a node().
def deployToContentEditing() {
   if (!("content-editing" in SERVICES)) {
      return;
   }

   withSecrets() {     // TODO(benkraft): do we actually need secrets?
      dir("webapp") {
         // HACK: If we sh() in a directory d, jenkins creates a sibling
         // directory d@tmp.  If d is a subdirectory of webapp, this
         // confuses deploy_to_gae.py's local changes check.  So instead
         // we have the shell do the cd.
         // TODO(colin): can we now run this from webapp root and avoid the
         // cd?
         withEnv(["VERSION=${NEW_VERSION}"]) {
            sh("cd services/content-editing && ./gradlew deploy");
         }
      }
   }
}

// When any of our datastore models or dataflow code changes, we
// need to rebuild the binary we use to export out datastore models
// to bigquery.
// We should do this more selectively (i.e. only when specific
// relevant files changed), but this is quick (< 30s), so to be
// safe as a stopgap measure we just do it all the time.
// We do this at build time, to build jar file and upload it
// to "gs://khanalytics/datastore-bigquery-adapter-jar-versions/
// datastore_bigquery_adapter.$NewDeployVersion.jar"
// We will swtich the new deploy version to
// gs://khanalytics/datastore_bigquery_adapter.jar in "finishWithSuccess" step.
def deployToDataflowDatastoreBigqueryAdapter() {
   dir("webapp") {
      withEnv(["VERSION=${NEW_VERSION}"]) {
         sh("cd dataflow/datastore_bigquery_adapter && ./gradlew build_and_upload_jar");
      }
   }
}

// This should be called from within a node().
def deployAndReport() {
   if (SERVICES) {
      parallel(
         "deploy-to-gae": { deployToGAE(); },
         "deploy-to-gcs": { deployToGCS(); },
         "deploy-to-kotlin-routes": { deployToKotlinRoutes(); },
         "deploy-to-content-editing": { deployToContentEditing(); },
         "deploy-to-dataflow-datastore-bigquery-adapter":
            { deployToDataflowDatastoreBigqueryAdapter(); },
         "failFast": true,
      );
      _alert(alertMsgs.JUST_DEPLOYED,
             [deployUrl: DEPLOY_URL,
              version: NEW_VERSION,
              services: SERVICES.join(', '),
              branches: REVISION_DESCRIPTION,
              logsUrl: ("https://console.cloud.google.com/logs/viewer?" +
                        "project=khan-academy&resource=gae_app%2F" +
                        "version_id%2F" + NEW_VERSION)]);
   }
}


def sendChangelog() {
   withTimeout('5m') {
      // Send the changelog!
      withSecrets() {
         dir("webapp") {
            def currentVersionTag;
            if (!params.BASE_REVISION) {
               currentVersionTag = exec.outputOf(
                  ["deploy/current_version.py", "--git-tag"]);
            }
            // Prints the diff BASE_REVISION..GIT_REVISION (i.e. changes since
            // the currently live version).
            exec(["deploy/chat_messaging.py",
                  params.BASE_REVISION ?: currentVersionTag,
                  params.GIT_REVISION, "-o", params.SLACK_CHANNEL,
                  "-t", params.SLACK_THREAD]);
         }
      }
   }
}


def finishWithFailure(why) {
   withTimeout('20m') {
      _alert(alertMsgs.FAILED_WITHOUT_ROLLBACK,
             [version: NEW_VERSION,
              // TODO(benkraft): Report specifically which service failed.
              services: SERVICES.join(', ') ?: "tools-only",
              branch: REVISION_DESCRIPTION,
              why: why]);
      env.SENT_TO_SLACK = '1';
   }
}


// We use a build worker, because this is a very CPU-heavy job and we may want
// to run several at a time.
onWorker('build-worker', '4h') {
   notify([slack: [channel: params.SLACK_CHANNEL,
                   sender: 'Mr Monkey',
                   emoji: ':monkey_face:',
                   // We don't need to notify on start because the buildmaster
                   // does it for us; on success the we explicitly send
                   // alertMsgs.SUCCESS; and aborts usually just mean the
                   // buildmaster killed things and the user already knows or
                   // does not care.  (See also the catch(e) below.)
                   when: ['FAILURE', 'UNSTABLE']],
           buildmaster: [sha: params.GIT_REVISION,
                         what: 'build-webapp'],
           aggregator: [initiative: 'infrastructure',
                        when: ['SUCCESS', 'BACK TO NORMAL',
                        'FAILURE', 'ABORTED', 'UNSTABLE']]]) {
      stage("Merging in master") {
         mergeFromMasterAndInitializeGlobals();
      }

      try {
         stage("Deploying") {
            withTimeout('150m') {
               deployAndReport();
            }
         }
         // TODO(jacqueline): This may get spammy. Is there somewhere we can
         // move this so that it doesn't send for every build?
         stage("Send changelog") {
            sendChangelog();
         }
      } catch (e) {
         echo("FATAL ERROR deploying: ${e}");
         // Don't send to Slack on abort; see the notify call above for why.
         if (currentBuild.result != "ABORTED") {
            currentBuild.result = "FAILURE";
            finishWithFailure(e.toString());
         }
         throw e;
      }
   }
}
