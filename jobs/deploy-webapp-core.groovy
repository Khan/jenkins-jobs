// The pipeline script to deploy to www.khanacademy.org from Khan/webapp.

// NOTE(benkraft): This is currently a script in transition!  Apologies for the
// mess.
// The comments that follow describe the old method, which runs this entire
// script, and which calls it via deploy-webapp; the new buildmaster-driven
// deploys will set flags to only use parts of it (see STAGES, below), and call
// this job directly so they can run concurrently when appropriate.  See
// the design:
//    https://docs.google.com/document/d/1utyUMMBOQvt4o3W_yl_89KdlNdAZUYsnSN_2FjWL-wA/edit#heading=h.kzjq9eunc7bh
// for more details.
// TODO(benkraft): Clean things up -- likely by splitting this job up rather
// than using STAGES -- after we've moved over to the new process entirely.

// "Deploying to production" is a complex process, and this is a complex
// script.  We use github-style deploys:
//    https://docs.google.com/a/khanacademy.org/document/d/1s7qvACA4Uq4ON6F4PWJ_eyBz9EJeTk-DJ6SysRrJcTI/edit
// For more high-level information on deploys, see
//    https://sites.google.com/a/khanacademy.org/forge/for-developers/deployment-guidelines

// Here are the steps in a deploy.  Some can happen in parallel:
//
// 1. Create a new branch off master, named after this deploy.  Merge
//    in your branch (i.e. the branch to be deployed.)  If specified,
//    also merge the latest translations into your branch.
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


// NOTE(benkraft): While we have this wrapper-script situation, please make
// sure to update deploy-webapp's params to match any changes you make here,
// unless they are only for the buildmaster's use.
new Setup(steps

// We only need to lock out for promoting.  deploy-webapp takes care of that.
).allowConcurrentBuilds(

).addStringParam(
    "GIT_REVISION",
    """<b>REQUIRED</b>. The name of a branch to deploy (can't be master).
Can also be a list of branches to deploy separated by `+` ('br1+br2+br3').
We will automatically merge these branches (plus translations if specified)
into a new branch based off master, and deploy it.""",
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
    "STAGES",
    """\
<ul>
  <li> <b>all</b>: Do a full deploy.  If you're doing things manually, you want
       this option. </li>
  <li> <b>build</b>: Only build the version; don't promote it to default, run
       tests, or do any of the other attendant bits.  This should generally
       only be set by deploys done through the buildmaster, never manually.
       </li>
  <li> <b>promote</b>: Only promote the version to default.  Caller must ensure
       it has already been built, passed tests, etc.  This should generally
       only be set by deploys done through the buildmaster, never manually.
       </li>
</ul>

<p>If set to a value other than "all", GIT_REVISION must be a sha1, rather than
a list of branches.  (The buildmaster will have already done the merge.)
Finally, we never delete versions in this case; we leave that to the
buildmaster.  This overrides the value of RUN_TESTS.</p>""",
    ["all", "build", "promote"]

).addStringParam(
    "BASE_REVISION",
    """<p>Deploy everything that has happened since this revision.</p>

    <p>This only matters if DEPLOY is "default".  In that case, we deploy to
    static if there have been changes to static files since this revision.
    (So it must be a successfully built revision.)  At present, it is ignored
    when STAGES is "all"; the only valid value would be that of the currently
    deployed version.</p>""",
    ""

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

// The tag we will create and deploy.  It is a merge of master,
// the branch(es) the user asked to deploy, and (probably) translations.
DEPLOY_TAG = null;

// The tag we will use to tag this deploy.
GIT_TAG = null;

// True if we should deploy to GCS/GAE (respectively).
DEPLOY_STATIC = null;
DEPLOY_DYNAMIC = null;

// The git-tagname to roll back to if this deploy fails.  It
// should be the git tag of the current-live deploy when this
// script is first invoked.
ROLLBACK_TO = null;

// The "permalink" url used to access code deployed at DEPLOY_TAG.
// (That is, version-dot-khan-academy.appspot.com, not www.khanacademy.org).
DEPLOY_URL = null;

// Same as params.BASE_REVISION, but only if we want to trust it.
// We ignore it when STAGES != "build"; see its docstring for why.
BASE_REVISION = null;
// The version-name corresponding to BASE_REVISION
// (only set if BASE_REVISION is set).
BASE_REVISION_VERSION = null;

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
      alertMsgs = load("${pwd()}/../workspace@script/jobs/deploy-webapp_slackmsgs.groovy");

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

      if (params.STAGES == "build" && params.BASE_REVISION) {
         BASE_REVISION = params.BASE_REVISION;
         BASE_REVISION_VERSION = exec.outputOf(
            ["make", "gae_version_name",
             "VERSION_NAME_GIT_REVISION=${BASE_REVISION}"]);
      }

      DEPLOY_TAG = "deploy-${new Date().format('yyyyMMdd-HHmmss')}";

      // Create the deploy branch and merge in the requested branch.
      // TODO(csilvers): have these return an error message instead
      // of alerting themselves, so we can use notify.fail().
      withEnv(["SLACK_CHANNEL=${SLACK_CHANNEL}",
               "DEPLOYER_USERNAME=${DEPLOYER_USERNAME}"]) {
         if (params.STAGES != "all") {
            // If we are only doing some stages, we have already done our
            // merging -- which is good because the buildmaster expects us to
            // pass back a sha.
            // TODO(benkraft): Verify it's actually a valid sha in this repo.
            // (We could write kaGit.isSha.)  This is a little tricky because
            // we may not yet have cloned the repo.
            if (!params.GIT_REVISION ==~ /[0-9a-fA-F]{40}/) {
               notify.fail("STAGES != 'all' " +
                           "but GIT_REVISION was not a sha!");
            }

            if (params.STAGES == "promote" && exec.outputOf(
                  ["git", "rev-list", "${params.GIT_REVISION}..master"])) {
               // For promote, we do an extra safety check, that
               // GIT_REVISION is a valid sha ahead of master.
               notify.fail("STAGES == 'promote', but GIT_REVISION " +
                           "${params.GIT_REVISION} is is behind master!")
            }

            kaGit.safeSyncTo("git@github.com:Khan/webapp", params.GIT_REVISION)

         } else {
            kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", "master");
            // detatch HEAD so there's no chance of accidentally updating
            // master by doing a `git push`.
            dir("webapp") {
               sh("git checkout --detach");
            }

            def allBranches = params.GIT_REVISION.split(/\+/);
            if (params.MERGE_TRANSLATIONS) {
               // Jenkins jobs only update intl/translations in the
               // "translations" branch.
               allBranches += ["translations"];
            }

            for (def i = 0; i < allBranches.size(); i++) {
               kaGit.safeMergeFromBranch("webapp", "HEAD",
                                         allBranches[i].trim());
            }
         }

         // We need to at least tag the commit, otherwise github may prune
         // it.  For now, we do this even for STAGES != "all", for
         // consistency and because it doesn't hurt.  In this case we'll end up
         // with several different tags on the commit (one from merge-branches,
         // one from when we run build, and one from when we run promote), but
         // it's not a big deal.
         // TODO(benkraft): Consolidate these tags, when everything is using
         // the buildmaster if not before.
         dir("webapp") {
            exec(["git", "tag", DEPLOY_TAG, "HEAD"]);
            exec(["git", "push", "--tags", "origin"]);
         }
      }

      dir("webapp") {
         clean(params.CLEAN);
         sh("make deps");

         // Let's do a sanity check.
         def deploySHA1 = exec.outputOf(["git", "rev-parse", DEPLOY_TAG]);
         def headSHA1 = exec.outputOf(["git", "rev-parse", "HEAD"]);
         if (deploySHA1 != headSHA1) {
            notify.fail("Internal error: " +
                        "HEAD does not point to the deploy-branch");
         }

         def shouldDeployArgs = ["deploy/should_deploy.py"];
         // Diff against BASE_REVISION if set.  We only allow this when
         // building: for promotion the only correct thing to do is to diff
         // against the currently live version, and the consequences of doing
         // something else are greater, so we prohibit the dangerous thing.
         if (BASE_REVISION) {
            shouldDeployArgs += ["--from-commit", BASE_REVISION]
         }

         if (params.DEPLOY == "default") {
            // TODO(csilvers): look for output == yes/no instead, and
            // if it's neither raise an exception.
            def rc = exec.statusOf(shouldDeployArgs + ["static"]);
            DEPLOY_STATIC = (rc != 0);
         } else {
            DEPLOY_STATIC = (params.DEPLOY in ["static", "both"]);
         }

         if (params.DEPLOY == "default") {
            def rc = exec.statusOf(shouldDeployArgs + ["dynamic"]);
            DEPLOY_DYNAMIC = (rc != 0);
         } else {
            DEPLOY_DYNAMIC = (params.DEPLOY in ["dynamic", "both"]);
         }

         // If this deploy fails, we want to roll back to the version
         // that was active when the deploy started.  That's now!
         ROLLBACK_TO = exec.outputOf(["deploy/current_version.py",
                                      "--git-tag"]);

         def gaeVersionName = exec.outputOf(["make", "gae_version_name"]);

         if (DEPLOY_STATIC && !DEPLOY_DYNAMIC) {
            // In this case, we want to use the GAE version of the
            // BASE_REVISION, or that of the currently active version (namely
            // ROLLBACK_TO), if BASE_REVISION is unset.
            if (BASE_REVISION) {
               GAE_VERSION = BASE_REVISION_VERSION;
            } else {
               GAE_VERSION = exec.outputOf(["deploy/git_tags.py", "--gae",
                                            ROLLBACK_TO]);
            }
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


def sendStartMessage() {
   if (!(params.STAGES in ["build", "all"])) {
      // This only applies if we are building.
      return;
   }

   def deployType;
   if (DEPLOY_STATIC && DEPLOY_DYNAMIC) {
      deployType = "";
   } else if (DEPLOY_STATIC) {
      deployType = "static (js)-only ";
   } else if (DEPLOY_DYNAMIC) {
      deployType = "dynamic (python)-only ";
   } else {
      deployType = "tools-only ";
   }

   withTimeout("1m") {
      _alert(alertMsgs.STARTING_DEPLOY,
             [deployType: deployType,
              branch: "${DEPLOY_TAG} (containing ${params.GIT_REVISION})"]);
   }
}


def runTests() {
   if (params.RUN_TESTS == "no" || params.STAGES != "all") {
      return;
   }
   def TEST_TYPE = (params.RUN_TESTS == "default" ? "relevant" : "all");
   build(job: 'webapp-test',
         parameters: [
            string(name: 'GIT_REVISION', value: DEPLOY_TAG),
            string(name: 'TEST_TYPE', value: TEST_TYPE),
            string(name: 'MAX_SIZE', value: "medium"),
            booleanParam(name: 'FAILFAST', value: false),
            string(name: 'SLACK_CHANNEL', value: SLACK_CHANNEL),
            booleanParam(name: 'FORCE', value: params.FORCE),
         ]);
}


// This should be called from within a node().
def deployToGAE() {
   if (!DEPLOY_DYNAMIC) {
      return;
   }
   def args = ["deploy/deploy_to_gae.py",
               "--no-browser", "--no-up",
               "--slack-channel=${SLACK_CHANNEL}",
               "--deployer-username=${DEPLOYER_USERNAME}"];
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
   // We always "deploy" to gcs, even for python-only deploys, though
   // for python-only deploys the gcs-deploy is very simple.
   def args = ["deploy/deploy_to_gcs.py", GCS_VERSION];
   if (!DEPLOY_STATIC) {
      if (BASE_REVISION) {
         // Copy from the specified version.  Note that this may not be a
         // "real" static version (if BASE_REVISION was also a python-only
         // deploy) but we can copy it just fine either way.
         args += ["--copy-from=${BASE_REVISION_VERSION}"];
      } else {
         args += ["--copy-from=default"];
      }
   }
   // We make sure deploy_to_gcs messages slack only if deploy_to_gae won't be.
   if (DEPLOY_DYNAMIC) {
      args += ["--slack-channel=", "--deployer-username="];
   } else {
      args += ["--slack-channel=${SLACK_CHANNEL}",
               "--deployer-username=${DEPLOYER_USERNAME}"];
   }
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
def deployAndReport() {
   if (!(params.STAGES in ["build", "all"])) {
      return;
   }

    parallel(
        "deploy-to-gae": { deployToGAE(); },
        "deploy-to-gcs": { deployToGCS(); },
        "failFast": true,
    );
    _alert(alertMsgs.JUST_DEPLOYED,
           [deployUrl: DEPLOY_URL,
            version: COMBINED_VERSION]);

    // (Note: we run the e2e tests even for tools-only deploys, to make
    // sure the deploy doesn't break the e2e test system.)
    // TODO(csilvers): remove "wait: false"?  It means people would have to
    // wait for e2e tests to finish before being able to set default, and
    // we'd have to refactor `deploy_pipeline manual-test`.
    stage("First e2e test") {
        build(job: 'e2e-test',
              wait: false,
              propagate: false,  // e2e errors are not fatal for a deploy
              parameters: [
                  string(name: 'URL', value: DEPLOY_URL),
                  string(name: 'SLACK_CHANNEL', value: SLACK_CHANNEL),
                  string(name: 'GIT_REVISION', value: DEPLOY_TAG),
                  booleanParam(name: 'FAILFAST', value: false),
                  string(name: 'DEPLOYER_USERNAME', value: DEPLOYER_USERNAME),
              ]);
    }
}


def spawnDeleteVersions() {
    if (params.STAGES != "all") {
        return;
    }

    stage("Spawn version deletion job") {
        build(job: 'audit-gae-versions',
              wait: false,       // the whole point of using a separate job!
              propagate: false,  // errors are nonfatal
              parameters: [string(name: 'GIT_REVISION', value: DEPLOY_TAG)]);
    }
}

def promptForSetDefault() {
   // TOOD(benkraft): Figure out if we should bother sending this message for
   // STAGES="promote".  For now, we do, to be extra explicit.
   withTimeout('1m') {
      // The CMS endpoints must be handled on the vm module. However,
      // the rules in dispatch.yaml only match *.khanacademy.org,
      // so the routing doesn't work in dynamic deploys (which are
      // accessed through *.appspot.com) before the new version is
      // set as default (but static-only deploys do work). In dynamic
      // deploys, we therefore show a link directly to the vm module.
      // TODO(aasmund): Remove when we have a better vm deployment
      def maybeVmMessage = (
         DEPLOY_DYNAMIC
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
              branch: DEPLOY_TAG]);
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
                     string(name: 'GIT_REVISION', value: DEPLOY_TAG),
                     booleanParam(name: 'FAILFAST', value: false),
                     string(name: 'DEPLOYER_USERNAME',
                            value: DEPLOYER_USERNAME),
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
      dir("webapp") {
         // Create the git tag (if we actually deployed something somewhere).
         if (DEPLOY_STATIC || DEPLOY_DYNAMIC) {
            def existingTag = exec.outputOf(["git", "tag", "-l", GIT_TAG]);
            if (!existingTag) {
               exec(["git", "tag", "-m",
                     "Deployed to appengine from branch " +
                     "${params.GIT_REVISION} (via branch ${DEPLOY_TAG})",
                     GIT_TAG, DEPLOY_TAG]);
            }
         }
         try {
            def branchName = "${DEPLOY_TAG} (${params.GIT_REVISION})";

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
            echo("Merging ${branchName} into master");
            try {
               exec(["git", "merge", DEPLOY_TAG]);
            } catch (e) {
               echo("FATAL ERROR running 'git merge ${DEPLOY_TAG}': ${e}");
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

            echo("Done merging ${branchName} into master");
         } catch (e) {
            echo("FATAL ERROR merging to master: ${e}");
            _alert(alertMsgs.FAILED_MERGE_TO_MASTER,
                   [combinedVersion: COMBINED_VERSION,
                    branch: params.GIT_REVISION]);
            throw e;
         }
      }

      _alert(alertMsgs.SUCCESS,
             [combinedVersion: COMBINED_VERSION, branch: params.GIT_REVISION]);
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
                    branch: params.GIT_REVISION,
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
              branch: params.GIT_REVISION,
              rollbackToAsVersion: rollbackToAsVersion,
              why: why]);
      env.SENT_TO_SLACK = '1';
   }
}


notify([slack: [channel: '#1s-and-0s-deploys',
                sender: 'Mr Monkey',
                emoji: ':monkey_face:',
                // We don't need to notify on success because
                // deploy_pipeline.py does it for us.
                when: ['BUILD START','FAILURE', 'UNSTABLE', 'ABORTED']],
        buildmaster: [shaCallback: { params.GIT_REVISION },
                      shouldNotifyCallback: { params.STAGES != "all" },
                      // For now, the buildmaster sees this as a build --
                      // deploy will come later.
                      what: (params.STAGES == 'promote' ?
                             'deploy-webapp' : 'build-webapp')],
        aggregator: [initiative: 'infrastructure',
                     when: ['SUCCESS', 'BACK TO NORMAL',
                            'FAILURE', 'ABORTED', 'UNSTABLE']],
        timeout: "4h"]) {
   stage("Merging in master") {
      mergeFromMasterAndInitializeGlobals();
   }

   stage("Starting") {
      sendStartMessage();
   }

   try {
      stage("Deploying and testing") {
         withTimeout('120m') {
            parallel(
               "deploy-and-report": { deployAndReport(); },
               // This may be a no-op, if RUN_TESTS is "no" or STAGES != "all".
               "test": { runTests(); },
               "failFast": true,
            );
         }
      }
   } catch (e) {
      // TODO(benkraft): This can be simplified if STAGES == "build".
      echo("FATAL ERROR deploying and testing: ${e}");
      finishWithFailure(e.toString());
      throw e;
   } finally {
      // We do this here so as to give us as much time as possible before
      // deleting the old versions, but making sure it still happens even in
      // failure cases, and happens before the very end of the job (when it is
      // more likely to run in parallel to the next deploy, potentially causing
      // us to delete a version sooner than necessary).
      spawnDeleteVersions();
   }

   if (!(params.STAGES in ["all", "promote"])) {
      return;
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
