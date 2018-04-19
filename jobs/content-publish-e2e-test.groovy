// The pipeline job for end-to-end tests run after a content publish.
//
// Ideally we'd run this after every publish, but in reality we run it
// on a schedule (once every 10 minutes).
//
// Because it runs so frequently, we do not want to use the test
// worker machines, like we do with e2e-test.groovy.  Instead, we do
// all our work on saucelabs.  This makes the test a bit slower to
// run, but uses someone else's resources instead of ours. :-)


@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.exec
//import vars.kaGit
//import vars.notify
//import vars.withTimeout
//import vars.withSecrets


new Setup(steps

).addCronSchedule("H/10 * * * *"

).addStringParam(
    "URL",
    "The url-base to run these tests against.",
    "https://www.khanacademy.org"

).addStringParam(
    "SLACK_CHANNEL",
    "The slack channel to which to send failure alerts.",
    "#content-beep-boop"

).addStringParam(
    "PUBLISH_USERNAME",
    "Name of the user who triggered the publish action.",
    ""

).addStringParam(
    "PUBLISH_MESSAGE",
    "The message associated with the publish action.",
    ""

).addBooleanParam(
   "FORCE",
   """If set, run the tests even if the database says that the e2e tests
have already passed for this webapp version + publish commit.""",
   false

).addStringParam(
    "DEPLOYER_USERNAME",
    """Who asked to run this job, used to ping on slack. Typically not set
manually, but rather by other jobs that call this one.""",
    ""

).apply();


// This is used to check if we've already run this exact e2e test.
// This is necessary because this job gets run on a schedule; we
// don't want to redo work if nothing has changed since the last run!
def getRedisKey() {
   withTimeout('30m') {
      def versionJson = exec.outputOf(
         ["curl", "-s", "${params.URL}/api/internal/dev/version"]);
      def versionId = sh(
         script: ("echo ${exec.shellEscape(versionJson)} | " +
                  "python -c 'import json, sys;" +
                  " x = json.load(sys.stdin);" +
                  " v = x[\"version_id\"].split(\".\")[0];" +
                  " s = x[\"static_version_id\"];" +
                  " print v if v == s else v + \"-\" + s'"),
         returnStdout: true).trim();

      def publishCommitJson = exec.outputOf(
         ["curl", "-s",
          "${params.URL}/api/internal/misc/last_published_commit"]);
      def publishCommitId = sh(
         script: ("echo ${exec.shellEscape(publishCommitJson)} | " +
                  "python -c 'import json, sys;" +
                  " print json.load(sys.stdin)[\"last_published_commit\"]'"),
         returnStdout: true).trim();

      return "e2etest:${versionId}:${publishCommitId}";
   }
};


def syncWebapp() {
   withTimeout('15m') {
      kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", "master");
      dir("webapp") {
         sh("make deps");
      }
   }
}


def runAndroidTests() {
   def slackArgsWithoutChannel = ["jenkins-jobs/alertlib/alert.py",
                                  "--chat-sender=Testing Turtle",
                                  "--icon-emoji=:turtle:"];
   def slackArgs = (slackArgsWithoutChannel +
                    ["--slack=${params.SLACK_CHANNEL}"]);
   def successMsg = "Mobile integration tests succeeded";
   def failureMsg = ("Mobile integration tests failed " +
                     "(search for 'ANDROID' in ${env.BUILD_URL}consoleFull)");

   withTimeout('1h') {
      withEnv(["URL=${params.URL}"]) {
         withSecrets() {  // we need secrets to talk to slack!
            try {
               sh("jenkins-jobs/run_android_db_generator.sh");
               sh("echo ${exec.shellEscape(successMsg)} | " +
                  "${exec.shellEscapeList(slackArgs)} --severity=info");
            } catch (e) {
               sh("echo ${exec.shellEscape(failureMsg)} | " +
                  "${exec.shellEscapeList(slackArgs)} --severity=error");
               // TODO(charlie): Re-enable sending these failures to
               // #mobile-1s-and-0s.  They're too noisy right now,
               // making the channel unusable on some days, and these
               // failures are rarely urgent (so, e.g., if we only
               // notice them the next morning when the nightly e2es
               // run, that's fine). Note that the tests are mostly
               // failing during the publish e2es.  See:
               // https://app.asana.com/0/31965416896056/268841235736013.
               //sh("echo ${exec.shellEscape(failureMsg)} | " +
               //     "${exec.shellEscapeList(slackArgsWithoutChannel)} " +
               //     "--slack='#mobile-1s-and-0s' --severity=error");
               throw e;
            }
         }
      }
   }
}


def runEndToEndTests() {
   withTimeout("1h") {
      // Out with the old, in with the new!
      sh("rm -f webapp/genfiles/test-results.pickle");

      // This is apparently needed to avoid hanging with
      // the chrome driver.  See
      // https://github.com/SeleniumHQ/docker-selenium/issues/87
      // We also work around https://bugs.launchpad.net/bugs/1033179
      withEnv(["DBUS_SESSION_BUS_ADDRESS=/dev/null",
               "TMPDIR=/tmp"]) {
         withSecrets() {   // we need secrets to talk to saucelabs
            dir("webapp") {
               lock("using-saucelabs") {
                  exec(["tools/runsmoketests.py",
                        "--pickle",
                        "--pickle-file=genfiles/test-results.pickle",
                        // JOBS=9 leaves one sauce machine available
                        // for deploy e2e tests (which uses sauce on
                        // test failure).
                        "--quiet", "--jobs=9", "--retries=3",
                        "--url", params.URL, "--driver=sauce"])
               }
            }
         }
      }
   }
}


// 'label' is attached to the slack message to help identify the job.
def analyzeResults(label) {
   withTimeout("15m") {
      if (!fileExists("webapp/genfiles/test-results.pickle")) {
         def msg = ("The e2e tests did not even finish (could be due " +
                    "to timeouts or framework errors; search for " +
                    "`Failed` at ${env.BUILD_URL}consoleFull to see " +
                    "exactly why)");
         // e2e test failures are not currently fatal, so mark as UNSTABLE.
         notify.fail(msg, "UNSTABLE");
      }

      if (params.PUBLISH_MESSAGE) {
         label += ": ${params.PUBLISH_MESSAGE}";
      }
      // We prefer to say the publisher "did" the deploy, if available.
      def deployer = params.PUBLISH_USERNAME ?: params.DEPLOYER_USERNAME;
      withSecrets() {      // we need secrets to talk to slack.
         dir("webapp") {
            exec(["tools/test_pickle_util.py", "summarize-to-slack",
                  "genfiles/test-results.pickle", params.SLACK_CHANNEL,
                  "--jenkins-build-url", env.BUILD_URL,
                  "--commit", label,
                  "--deployer", deployer]);
            // Let notify() know not to send any messages to slack,
            // because we just did it above.
            env.SENT_TO_SLACK = '1';

            sh("rm -rf genfiles/test-reports");
            sh("tools/test_pickle_util.py to-junit " +
               "genfiles/test-results.pickle genfiles/test-reports");
         }
      }

      junit("webapp/genfiles/test-reports/*.xml");
   }
}


onMaster('4h') {
   notify([slack: [channel: params.SLACK_CHANNEL,
                   sender: 'Testing Turtle',
                   emoji: ':turtle:',
                   when: ['FAILURE', 'UNSTABLE']],
           aggregator: [initiative: 'infrastructure',
                        when: ['SUCCESS', 'BACK TO NORMAL',
                               'FAILURE', 'ABORTED', 'UNSTABLE']]]) {
      def key = getRedisKey();

      currentBuild.displayName = "${currentBuild.displayName} (${key})";

      singleton.storeEvenOnFailure(params.FORCE ? null : key) {
         stage("Syncing webapp") {
            syncWebapp();
         }
         stage("Running android tests") {
            try {
               runAndroidTests();
            } catch (e) {
               // end-to-end failures are not blocking currently, so if
               // tests fail set the status to UNSTABLE, not FAILURE.
               // We also keep going to do the other tests.
               currentBuild.result = "UNSTABLE";
            }
         }
         stage("Running e2e tests") {
            try {
               runEndToEndTests();
            } catch (e) {
               // end-to-end failures are not blocking currently, so if
               // tests fail set the status to UNSTABLE, not FAILURE.
               // We also keep going to analyze the results.
               currentBuild.result = "UNSTABLE";
            }
         }
         stage("Analyzing results") {
            analyzeResults(key);
         }
      }
   }
}
