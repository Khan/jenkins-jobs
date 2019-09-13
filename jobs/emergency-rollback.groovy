// Job to roll back the live version of the site to the previous version.
// It also marks the current version as bad.
//
// This is the script to run if you've done `sun: finish` and only then
// realize that the current deploy has a problem with it.
//
// Note this job does not interact with the deploy pipeline, and can run
// even while a deploy is going on.
//
// You can run this from slack by saying <code>sun: emergency rollback</code>
// in the <code>1s-and-0s-deploys</code> channel.</p>

@Library("kautils")

import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars.  This is just for documentation.
//import vars.exec
//import vars.kaGit
//import vars.notify
//import vars.withTimeout
//import vars.withSecrets

// We try to keep minimal options in this job: you don't want to have to
// figure out which options are right when the site is down!
new Setup(steps

).addStringParam(
   "JOB_PRIORITY",
   """The priority of the job to be run (a lower priority means it is run
sooner). The Priority Sorter plugin reads this parameter in to reorder jobs
in the queue accordingly. Should always be set to 1. See
https://jenkins.khanacademy.org/advanced-build-queue/ for more information.""",
   "1"

).addBooleanParam(
   "DRY_RUN",
   """Don't actually run emergency rollback, just say what we would do.  This
is primarily useful for making sure the job has a recent checkout of webapp.
(We run it on a cron job for that purpose.)""",
   false


).addStringParam(
   "ROLLBACK_TO",
   """The version to rollback to. If not provided, we will default to the
   most recent good version on app engine. This is a full version tag name,
   e.g. gae-181217-1330-b18f83d38a3d-kotlin-routes-181217-0832-789e0227e0ba""",
   ""

).addStringParam(
   "BAD_VERSION",
   """The version to rollback from and mark `-bad` in git. If not provided, we
   look for the current live version. This is also a full version tag name,
   e.g. gae-181217-2111-5f05dc51cf56-kotlin-routes-181217-0832-789e0227e0ba""",
   ""
// NOTE(benkraft): This runs in a cron job started from the buildmaster,
// instead of a jenkins cron job, because jenkins cron jobs can't pass
// parameters and we need to pass DRY_RUN.

).apply();

if (params.DRY_RUN) {
   currentBuild.displayName = "${currentBuild.displayName} **DRY RUN**";
}


// We purposefully hard-code this so people can't do secret deploys. :-)
SLACK_CHANNEL = "#1s-and-0s-deploys";


def _alert(def msg) {
   args = ["jenkins-jobs/alertlib/alert.py",
           "--slack=${SLACK_CHANNEL}",
           "--chat-sender=Mr Monkey",
           "--icon-emoji=:monkey_face:",
           "--slack-simple-message",
          ];
   withSecrets() {     // to talk to slack
      sh("echo ${exec.shellEscape(msg)} | ${exec.shellEscapeList(args)}");
   }
}


// TODO(csilvers): maybe use another workspace to save time syncing?
// But decrypting secrets is a problem then.
def doSetup() {
    withTimeout('30m') {
        kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", "master");
        dir("webapp") {
           sh("make clean_pyc");
           sh("make python_deps");
        }
    }
    _alert("Setup has just been completed :white_check_mark:");
}


// This attempts to verify that a provided tag is valid.
// When a tag is explicitly provided but cannot be verified we
// throw a failure rather than attempting to fallback to a default.
// We'd prefer not to run the rollback on a version that was
// not intended. Someone can run this without the params if they
// want to rely on the defaults.
def verifyValidTag(tag) {
   // A full version tag should always include the dynamic version, which will
   // be listed first. We strip everything else (e.g. 181217-1330-b18f83d38a3d)
   if (!tag.contains('gae')) {
      notify.fail("Version tag should always include the " +
                  "dynamic version. To see all potential tags, " +
                  "use `git tag -l 'gae-*'.");
   }
   def dynamic = exec.outputOf(["deploy/git_tags.py", "--service=dynamic",
                                tag]);
   // Check that the dynamic version in fact exists on GAE
   def args = ["gcloud", "app", "versions", "list",
               "--project=khan-academy", "--service=default",
               "--filter=version.name:${dynamic}"];
   def gae_version = exec.outputOf(args);
   // when a version is not found, gcloud returns "Listed 0 items."
   if (!gae_version.contains(dynamic)) {
      notify.fail("Version gae-${dynamic} not found. " +
                  "Check versions that exist on GAE using: " +
                  "`${args}`");
   }
   _alert("Confirmed that ${gae_version} is a valid version!");
   return true;
}


def doRollback() {
   withTimeout('30m') {
      withSecrets() {
         cmd = ["deploy/rollback.py"];
         if (params.DRY_RUN) {
            cmd += ["-n"];
         }

         if (params.ROLLBACK_TO && verifyValidTag(params.ROLLBACK_TO)) {
            cmd += ["--good=${params.ROLLBACK_TO}"];
         }
         if (params.BAD_VERSION && verifyValidTag(params.BAD_VERSION)) {
            cmd += ["--bad=${params.BAD_VERSION}"];
         }
         try {
            dir("webapp") {
               exec(cmd);
            }
            _alert(":penguin_dance: Rollback is now complete! I'll run the e2e tests now to finish the job!");
         } catch(e) {
            notify.fail("Rollback failed: ${e}.\nTo try running again " +
                        "manually, see the <https://docs.google.com/document/" +
                        "d/1sdN7_fNIDkTkLp16ztubklf57bgXqeGAhsL4DrGjP7s|" +
                        "emergency rollback checklist>.");
         }
      }
   }
}


onMaster('1h') {
   notify([slack: [channel: '#1s-and-0s-deploys',
                   sender: 'Mr Monkey',
                   emoji: ':monkey_face:',
                   when: ['BUILD START', 'SUCCESS',
                          'FAILURE', 'UNSTABLE', 'ABORTED']],
           aggregator: [initiative: 'infrastructure',
                        when: ['SUCCESS', 'BACK TO NORMAL',
                               'FAILURE', 'ABORTED', 'UNSTABLE']]]) {
       stage("setup") {
           doSetup();
       }
       stage("rollback") {
           doRollback();
       }
   }
   // Let's kick off the e2e tests again to make sure everything is
   // working ok.
   if (!params.DRY_RUN) {
      build(job: '../deploy/e2e-test',
            parameters: [
               string(name: 'SLACK_CHANNEL', value: "#1s-and-0s-deploys"),
               string(name: 'TEST_TYPE', value: "deploy"),
            ]);
   } else {
      echo("Would run deploy/e2e-test job on master.");
   }
}
