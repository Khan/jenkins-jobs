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

new Setup(steps

).allowConcurrentBuilds(

// We do a lot of e2e-test runs, and QA would like to be able to see details
// for a bit longer.
).resetNumBuildsToKeep(
   350,

).addStringParam(
   "URL",
   "The url-base to run these tests against.",
   "https://www.khanacademy.org"

).addChoiceParam(
    "FASTLY_SERVICE",
    """""",
    ["PROD [VCL]", "PROD [COMPUTE]", "STAGING [COMPUTE]", "TEST [COMPUTE]"]
)
.addChoiceParam(
   "TEST_TYPE",
   """IGNORE: This is a dummy parameter that is only here to avoid breaking the
   communication with buildmaster""",
   ["all", "deploy", "custom"]

).addStringParam(
   "TESTS_TO_RUN",
   """IGNORE: This is a dummy parameter that is only here to avoid breaking the
   communication with buildmaster""",
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
API.""",
    ""

).addStringParam(
   "NUM_WORKER_MACHINES",
   """How many worker machines to use in LambdaTest. Max available is 30.""",
   "30"

).addBooleanParam(
   "USE_FIRSTINQUEUE_WORKERS",
   """If true, use the jenkins workers that are set aside for the
currently active deploy.  Obviously, this should only be set if you
are, indeed, the currently active deploy.  We reserve these machines
so the currently active deploy never has to wait for smoketest workers
to spin up.""",
   false

).addStringParam(
   "TEST_RETRIES",
   """How many retry attempts to use. By default is 4.""",
   "4"

).addStringParam(
   "GIT_REVISION",
   """A commit-ish to check out.  This only affects the version of the
E2E test used; it will probably match the tested version's code,
but it doesn't need to.""",
   "master"

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
   ""

).addStringParam(
   "BUILDMASTER_DEPLOY_ID",
   """Set by the buildmaster, can be used by scripts to associate jobs
that are part of the same deploy.  Write-only; not used by this script.""",
   ""

).addBooleanParam(
   "SET_SPLIT_COOKIE",
   """IGNORE: This is a dummy parameter that is only here to avoid breaking the
   communication with buildmaster""",
   false

).addStringParam(
   "EXPECTED_VERSION",
   """IGNORE: This is a dummy parameter that is only here to avoid breaking the
   communication with buildmaster""",
   ""

).addStringParam(
   "EXPECTED_VERSION_SERVICES",
   """IGNORE: This is a dummy parameter that is only here to avoid breaking the
   communication with buildmaster""",
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

).addStringParam(
   "SKIP_TESTS",
   """IGNORE: This is a dummy parameter that is only here to avoid breaking the
   communication with buildmaster""",
   ""

).apply();

// Override the build name by the info that is passed in (from buildmaster).
REVISION_DESCRIPTION = params.REVISION_DESCRIPTION ?: params.GIT_REVISION;
E2E_URL = params.URL[-1] == '/' ? params.URL.substring(0, params.URL.length() - 1): params.URL;

currentBuild.displayName = ("${currentBuild.displayName} " +
                            "(${REVISION_DESCRIPTION})");

// We use the build name as a unique identifier for user notifications.
BUILD_NAME = "build e2e-cypress-test #${env.BUILD_NUMBER} (${E2E_URL}: ${params.REVISION_DESCRIPTION})"

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
      sh("make npm_deps");
   }
}

def runLambdaTest() {
   // We need login creds for LambdaTest Cli
   def lt_username = sh(script: """\
      gcloud --project khan-academy secrets describe \
      lambdatest_admin_account --format json | jq -r .annotations.login \
      """, returnStdout:true).trim();
   def lt_access_key = sh(script: """\
      gcloud --project khan-academy secrets versions access latest \
      --secret lambdatest_admin_account \
      """, returnStdout:true).trim();

   // Determine which environment we're running against, so we can provide a tag
   // in the LambdaTest build.
   def e2eEnv = E2E_URL == "https://www.khanacademy.org" ? "prod" : "preprod";

   // Determine the value of the `ka-fastly-compute-environment` cookie to set
   // when running the E2E tests.  These cookie values come from:
   // https://khanacademy.atlassian.net/wiki/spaces/INFRA/pages/3382050914/VCL+to+Fastly+Compute+routing
   def fastlyComputeEnvironmentCookie = "" // Default to PROD [VCL].
   if (params.FASTLY_SERVICE == "PROD [COMPUTE]") {
      fastlyComputeEnvironmentCookie = "NmU3ZDYxMmJjNDRkOGMwNDIzODg4ZDkyYTcxZTA4"
   } else if (params.FASTLY_SERVICE == "STAGING [COMPUTE]") {
      fastlyComputeEnvironmentCookie = "ODI5NTIyOGIxZTdmODUxOTEwZjY0ZDM0NjdlYjJi"
   } else if (params.FASTLY_SERVICE == "TEST [COMPUTE]") {
      fastlyComputeEnvironmentCookie = "ZTM3YmE2YTFjNDZkZjEwMDYxMTM3NzQyM2VlYjgw"
   }

   def runLambdaTestArgs = ["./dev/tools/run_pkg_script.sh",
                            "lambdatest",
                            "--envs='FASTLY_COMPUTE_ENVIRONMENT_COOKIE=${fastlyComputeEnvironmentCookie}'",
                            "--cy='--config baseUrl=\"${E2E_URL}\",retries=${params.TEST_RETRIES}'",
                            "--bn='${BUILD_NAME}'",
                            "-p=${params.NUM_WORKER_MACHINES}",
                            "--bt='jenkins,${e2eEnv}'"
   ];

   dir('webapp/services/static') {
      withEnv(["LT_USERNAME=${lt_username}",
               "LT_ACCESS_KEY=${lt_access_key}"]) {
         // NOTE: We include this Slack token in case we need to notify the
         // FEI team when we are going to hit a `queue_timeout` error in the
         // LambdaTest platform.
         withCredentials([
            string(credentialsId: "SLACK_BOT_TOKEN", variable: "SLACK_TOKEN")
         ]) {
            exec(runLambdaTestArgs);
         }
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
   if (match.results().count() == 0) {
        return ""
   }

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
               "./dev/cypress/e2e/tools/notify-e2e-results.ts",
               "--channel", params.SLACK_CHANNEL,
               // The URL associated to this Jenkins build.
               "--build-url", env.BUILD_URL,
               // The LambdaTest build name that will be included at the
               // beginning of the message.
               "--label", BUILD_NAME,
               // The URL we test against.
               "--url", params.URL,
               // Notify failures to DevOps and FEI
               "--cc-on-failure", "#deploy-support-log,#fei-testing-logs"
            ];

            if (params.DEPLOYER_USERNAME) {
               // The deployer is the person who triggered the
               // build (this is only included in the message if
               // the e2e tests fail).
               notifyResultsArgs += ["--deployer", params.DEPLOYER_USERNAME];
            }
            if (params.SLACK_THREAD) {
               notifyResultsArgs += ["--thread", params.SLACK_THREAD];
            }

            def userIds = getUserIds(params.DEPLOYER_USERNAME);

            // Include the deployer(s) here so they can get DMs when the e2e
            // results are ready.
            def ccAlways = userIds ? "#cypress-logs-deploys,${userIds}" : "#cypress-logs-deploys";

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


// Determines if we are running the first or second smoke test.
E2E_RUN_TYPE = (E2E_URL == "https://www.khanacademy.org" ? "second-smoke-test" : "first-smoke-test");

onWorker(WORKER_TYPE, '5h') {     // timeout
   notify([slack: [channel: params.SLACK_CHANNEL,
                   thread: params.SLACK_THREAD,
                   sender: 'Testing Turtle',
                   emoji: ':turtle:',
                   when: ['FAILURE', 'UNSTABLE']],
           buildmaster: [sha: params.GIT_REVISION,
                         what: E2E_RUN_TYPE]]) {

      stage("Sync webapp") {
         _setupWebapp();
      }

      stage("Run e2e tests") {
         // Note: runLambdaTest() succeeds (has an rc of 0) as long as
         // it succeeded in running all the tests, even if some of those
         // tests failed.  That is, this will succeed as long as there
         // are no lambdatest framework errors; it's up to use to look
         // for actual smoketest-code errors in analyzeResults(), below.
         runLambdaTest();
      }

      stage("Analyzing results") {
         analyzeResults();
      }
   }
}
