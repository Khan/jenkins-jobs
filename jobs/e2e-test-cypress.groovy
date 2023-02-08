// Single job for cypress e2e tests.
//
// cypress e2e tests are the smoketests run in the webapp/feature/cypress repo,
// that hit a live website using lambdatest cli.
@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.exec
//import vars.notify
//import vars.withTimeout
//import vars.withSecrets

new Setup(steps

).allowConcurrentBuilds(

// Allow multiple LambdaTest runs to execute concurrently.
).addStringParam(
   "GIT_REVISION",
   """The name of a cypress branch to use when building.""",
   "master"

).addStringParam(
   "URL",
   "The url-base to run these tests against.",
   "https://www.khanacademy.org/"

).addStringParam(
   "SLACK_CHANNEL",
   "The slack channel to which to send failure alerts.",
   "#cypress-logs-deploys"
).addStringParam(
   "SLACK_THREAD",
   """The slack thread (must be in SLACK_CHANNEL) to which to send failure
alerts.  By default we do not send in a thread.  Generally only set by the
buildmaster, to the 'thread_ts' or 'timestamp' value returned by the Slack
API.""",
    ""
).addStringParam(
   "DEPLOYER_USERNAME",
   """Who asked to run this job, used to ping on slack.
Typically not set manually, but rather by other jobs that call this one.""",
   ""

).addStringParam(
   "REVISION_DESCRIPTION",
   """Set by the buildmaster to give a more human-readable description
of the GIT_REVISION, especially if it is a commit rather than a branch.
Defaults to GIT_REVISION.""",
   "master"
// NOTE: These workers are not the same as the workers used by the Jenkins
// onWorker config. These are the workers we have available in LambdaTest.
// TODO(FEI-4888): Rename this param to make it clear that these are separate
// workers.
).addStringParam(
   "NUM_WORKER_MACHINES",
   """How many worker machines to use in LambdaTest. Max available is 30.""",
   "30"

)
.addBooleanParam(
   "USE_FIRSTINQUEUE_WORKERS",
   """If true, use the jenkins workers that are set aside for the
currently active deploy.  Obviously, this should only be set if you
are, indeed, the currently active deploy.  We reserve these machines
so the currently active deploy never has to wait for smoketest workers
to spin up.""",
   false

)
.addStringParam(
   "TEST_RETRIES",
   """How many retry attempts to use. By default is 4.""",
   "4"

).addChoiceParam(
   "TEST_TYPE",
   """\
<ul>
  <li> <b>all</b>: run all tests</li>
  <li> <b>deploy</b>: run only those tests that are important to run at
        deploy-time (as identified by the `@`
        decorator)</li>
  <li> <b>nightly</b>: run only night list of tests.</li>
</ul>
""",
   ["all", "deploy", "nightly"]

).apply()

// Override the build name by the info that is passed in (from buildmaster).
REVISION_DESCRIPTION = params.REVISION_DESCRIPTION ?: params.GIT_REVISION;
currentBuild.displayName = ("${currentBuild.displayName} " +
                            "(${REVISION_DESCRIPTION})");

// We use the build name as a unique identifier for user notifications.
BUILD_NAME = "build e2e-cypress-test #${env.BUILD_NUMBER} (${params.URL}: ${params.REVISION_DESCRIPTION})"

// At this time removing @ before username.
DEPLOYER_USER = params.DEPLOYER_USERNAME.replace("@", "")

// GIT_SHA1 is the sha1 for GIT_REVISION.
GIT_SHA1 = null;

// We have a dedicated set of workers for the second smoke test.
WORKER_TYPE = (params.USE_FIRSTINQUEUE_WORKERS
               ? 'ka-firstinqueue-ec2' : 'ka-test-ec2');

def _setupWebapp() {
   GIT_SHA1 = kaGit.resolveCommitish("git@github.com:Khan/webapp",
                                        params.GIT_REVISION);

   kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", GIT_SHA1);

   dir("webapp/services/static") {
      sh("yarn install --frozen-lockfile");
   }
}

def runLambdaTest() {
   // We need login creds for LambdaTest Cli
   def lt_username = sh(script: """\
      keeper --config ${exec.shellEscape("${HOME}/.keeper-config.json")} \
      get qvYpo_KnpCiLBN69fYpEYA --format json | jq -r .login\
      """, returnStdout:true).trim();
   def lt_access_key = sh(script: """\
      keeper --config ${exec.shellEscape("${HOME}/.keeper-config.json")} \
      get qvYpo_KnpCiLBN69fYpEYA --format json | jq -r .password\
      """, returnStdout:true).trim();

   // Determine which environment we're running against, so we can provide a tag
   // in the LambdaTest build.
   def e2eEnv = params.URL == "https://www.khanacademy.org" ? "prod" : "preprod";

   def runLambdaTestArgs = ["yarn",
                            "lambdatest",
                            "--cy='--config baseUrl=\"${params.URL}\",retries=${params.TEST_RETRIES}'",
                            "--bn='${BUILD_NAME}'",
                            "-p=${params.NUM_WORKER_MACHINES}",
                            "--sync=true",
                            "--bt='jenkins,${e2eEnv}'",
                            "--eof"
   ];

   dir('webapp/services/static') {
      withEnv(["LT_USERNAME=${lt_username}",
               "LT_ACCESS_KEY=${lt_access_key}"]) {
         exec(runLambdaTestArgs);
      }
   }
}

// This method filters a common 'DEPLOYER_USERNAME' into a series of comma
// seperated slack user id's. For Example:
// DEPLOYER_USERNAME: <@UMZGEUH09> (cc <@UN5UC0EM6>)
// becomes: UMZGEUH09,UN5UC0EM6,
def getUserIds(def deployUsernameBlob) {
   // Regex to specifically grab the ids, which should start with U and be
   // some number of capital letters and numbers. Ids can also start with
   // W (special users), T (teams), or C (channels).
   def pattern = /<@([UTWC][0-9A-Z]+)>/;
   def match = (deployUsernameBlob =~ pattern);

   def mainUser = match[0][1];
   def otherUsers = "";

   for (n in match) {
      // We look for possible duplicates as we don't want to notify the main
      // user twice.
      // NOTE: This can happen when the deployer includes their name in the
      // deploy command (e.g. `@foo` types: `sun queue foo`).
      if (n[1] != mainUser) {
         otherUsers += ",${n[1]}";
      }
   }

   // Return the list of unique ids
   return mainUser + otherUsers;
}

def analyzeResults() {
   withTimeout('5m') {
      if (currentBuild.result == 'ABORTED') {
         // No need to report the results in the case of abort!  They will
         // likely be more confusing than useful.
         echo('We were aborted; no need to report results.');
         return;
      }

      dir('webapp/services/static') {
         // Get the SLACK_TOKEN from global credentials (env vars).
         withCredentials([
            string(credentialsId: "SLACK_BOT_TOKEN", variable: "SLACK_TOKEN")
         ]) {
            def notifyResultsArgs = [
               "./dev/cypress/e2e/tools/notify-e2e-results.js",
               "--channel", params.SLACK_CHANNEL,
               // The URL associated to this Jenkins build.
               "--build-url", env.BUILD_URL,
               // The deployer is the person who triggered the build (this is
               // only included in the message if the e2e tests fail).
               "--deployer", params.DEPLOYER_USERNAME,
               // The LambdaTest build name that will be included at the
               // beginning of the message.
               "--label", BUILD_NAME,
               // The URL we test against.
               "--url", params.URL,
               // Notify failures to DevOps
               "--cc-on-failure", "#dev-support-log"
            ];

            if (params.SLACK_THREAD) {
               notifyResultsArgs += ["--thread", params.SLACK_THREAD];
            }

            def userIds = getUserIds(params.DEPLOYER_USERNAME);

            // Include the deployer(s) here so they can get DMs when the e2e
            // results are ready.
            def ccAlways = "#cypress-logs-deploys,${userIds}";

            if (params.SLACK_CHANNEL != "#qa-log") {
               ccAlways += ",#qa-log";
            }

            notifyResultsArgs += ["--cc-always", ccAlways];

            // notify-e2e-results returns a non-zero rc if it detects test
            // failures. We set the job to UNSTABLE in that case (since we don't
            // consider smoketest failures to be blocking). But we still keep on
            // running the rest of this script!
            catchError(buildResult: "UNSTABLE", stageResult: "UNSTABLE",
                        message: "There were test failures!") {
               exec(notifyResultsArgs);
            }

            // Let notify() know not to send any messages to slack, because we
            // just did it here.
            env.SENT_TO_SLACK = '1';
         }
      }
   }
}


onWorker(WORKER_TYPE, '5h') {     // timeout
   notify([slack: [channel: params.SLACK_CHANNEL,
                   thread: params.SLACK_THREAD,
                   sender: 'Testing Turtle',
                   emoji: ':turtle:',
                   when: ['FAILURE', 'UNSTABLE']]]) {
      stage("Sync webapp") {
         _setupWebapp();
      }

      try {
         stage("Run e2e tests") {
            runLambdaTest();
         }
      } finally {
         // We want to analyze results even if -- especially if -- there were
         // failures; hence we're in the `finally`.
         stage("Analyzing results") {
            analyzeResults();
         }
      }

   }
}
