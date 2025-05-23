// Single job for cypress e2e tests.
//
// cypress e2e tests are the smoketests run in the webapp/feature/cypress repo,
// that hit a live website using lambdatest cli.
@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.withVirtualenv
//import vars.exec
//import vars.notify
//import vars.singleton
//import vars.withTimeout
//import vars.kaGit
//import vars.onWorker
//import vars.withSecrets
//import vars.buildmaster
//import vars.onMaster
//import vars.clean

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
   """IGNORE: Using ka-firstinqueue-ec2 currently breaks Cypress Cloud's
ability to run in parallel. It is hardcoded to use ka-test-ec2 for now.
If true, use the jenkins workers that are set aside for the
currently active deploy.  Obviously, this should only be set if you
are, indeed, the currently active deploy.  We reserve these machines
so the currently active deploy never has to wait for smoketest workers
to spin up.""",
   false

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

// We set these to real values first thing below; but we do it within
// the notify() so if there's an error setting them we notify on slack.
NUM_WORKER_MACHINES = null;

// Override the build name by the info that is passed in (from buildmaster).
REVISION_DESCRIPTION = params.REVISION_DESCRIPTION ?: params.GIT_REVISION;
// Drop this part so the branches can be grouped in Cypress Cloud
SHORT_REVISION_DESCRIPTION = REVISION_DESCRIPTION.replaceAll(/\s*\((now live|currently deploying)\)/, '');
BASE_URL = params.URL;
E2E_URL = BASE_URL[-1] == '/' ? BASE_URL.substring(0, BASE_URL.length() - 1): BASE_URL;

currentBuild.displayName = ("${currentBuild.displayName} " +
   "(${REVISION_DESCRIPTION})");

// We use the build name as a unique identifier for user notifications.
BUILD_NAME = "${E2E_URL} #${env.BUILD_NUMBER}"

// At this time removing @ before username.
DEPLOYER_USER = params.DEPLOYER_USERNAME.replace("@", "")

// GIT_SHA1 is the sha1 for GIT_REVISION.
GIT_SHA1 = null;
REPORT_DIR = "webapp/genfiles";

SKIPPED_E2E_FILENAME = "skipped_e2e_tests.json";
SKIPPED_STASH_ID = "e2e-skipped-list";

// We have a dedicated set of workers for the second smoke test. (ka-firstinqueue-ec2)
// But using them breaks our ability to run e2e in parallel
// because Cypress complains about the environments being too different
WORKER_TYPE = 'ka-test-ec2';

// Used to tell whether all the test-workers raised an exception.
WORKERS_RAISING_EXCEPTIONS = 0;
public class TestFailed extends Exception {}

def swallowExceptions(Closure body, Closure onException = {}) {
   try {
      body();
   } catch (e) {
      echo("Swallowing exception: ${e}");
      onException();
   }
}

def initializeGlobals() {
   NUM_WORKER_MACHINES = params.NUM_WORKER_MACHINES.toInteger();

   GIT_SHA1 = kaGit.resolveCommitish("git@github.com:Khan/webapp",
      params.GIT_REVISION);
}

def _setupWebapp() {
   kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", GIT_SHA1);

   dir("webapp/services/static") {
      sh("make npm_deps");
   }
}

// Run all the test-clients on all the worker machine, in parallel.
def runAllTestClients() {
   // We want to swallow any framework exceptions unless *all* the
   // clients have raised a framework exception.  Our theory is that
   // if one client dies unexpectedly the others can compensate, but
   // if they all do, then there's nothing more we can do.
   def onException = {
      echo("Worker raised an exception");
      WORKERS_RAISING_EXCEPTIONS++;
      if (WORKERS_RAISING_EXCEPTIONS == NUM_WORKER_MACHINES) {
         echo("All worker machines failed!");
         throw new TestFailed("All worker machines failed!");
      }
   }

   def jobs = [:];
   for (i = 0; i < NUM_WORKER_MACHINES; i++) {
      def workerId = i;  // avoid scoping problems
      jobs["e2e-test-${workerId}"] = {
         stage("e2e-worker-${workerId}") {
            swallowExceptions({
               onWorker(WORKER_TYPE, '2h') {
                  _setupWebapp()
                  runE2ETests(workerId)
                  dir("${REPORT_DIR}") {
                     stash includes: "e2e-test-results.json", name: "worker-${workerId}-reports"
                  }
               }
            }, onException);
         }
      }
   };
   parallel(jobs);
}

def runE2ETests(workerId) {
   echo("Starting e2e tests for worker ${workerId}");

   // Define which environment we're running against, and setting up junit report
   def e2eEnv = E2E_URL == "https://www.khanacademy.org" ? "prod" : "preprod";

   def runE2ETestsArgs = [
      "./dev/cypress/e2e/tools/start-cy-cloud-run.ts",
      "--url=${E2E_URL}",
      "--name=${BUILD_NAME}",
   ];

   dir('webapp/services/static') {
      unstash SKIPPED_STASH_ID;
      sh("cat ${SKIPPED_E2E_FILENAME}");
      exec(runE2ETestsArgs);
   }
}

def unstashReports() {
   def jsonFolders = [];
   dir("${REPORT_DIR}") {
      for (i = 0; i < NUM_WORKER_MACHINES; i++) {
         exec(["rm", "-rf", "${i}"]);
         exec(["mkdir", "-p", "${i}"]);
         jsonFolders.add("${i}")
         dir("./${i}") {
            unstash "worker-${i}-reports"
            sh("ls");
         }
      }
   }
   return jsonFolders;
}

def analyzeResults(foldersList) {
   if (currentBuild.result == 'ABORTED') {
      // No need to report the results in the case of abort!  They will
      // likely be more confusing than useful.
      echo('We were aborted; no need to report results.');
      return;
   }

   withTimeout('5m') {
      // several new files in util and tools
      kaGit.safePull("webapp/services/static/dev/cypress/e2e/tools");
      kaGit.safePull("webapp/services/static/dev/cypress/e2e/util");

      def notifyResultsArgs = [
         "./dev/cypress/e2e/tools/notify-e2e-results.ts",
         "--channel", params.SLACK_CHANNEL,
         // The URL associated to this Jenkins build.
         "--build-url", env.BUILD_URL,
         // The Cypress Cloud build name that will be included at the
         // beginning of the message.
         "--label", BUILD_NAME,
         // The URL we test against.
         "--url", params.URL,
         // folders with e2e results
         "--folders",
         *foldersList
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

      // TODO(csilvers): services/static/dev/tools/slack/slack-client.ts
      // should get the secret directly from gsm, not via an envvar.
      def slackToken = exec.outputOf([
          "gcloud", "--project", "khan-academy",
          "secrets", "versions", "access", "latest",
          "--secret", "Slack_api_token_for_slack_owl",
      ]);

      dir('webapp/services/static') {
         withEnv(["SLACK_TOKEN=${slackToken}"]) {
            // notify-e2e-results returns a non-zero rc if it detects
            // test failures. We set the job to UNSTABLE in that case
            // (since we don't consider smoketest failures to be
            // blocking).  But we still keep on running the rest of
            // this script!
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
      initializeGlobals();
      stage("Generate skipped list") {
         _setupWebapp();
         dir("webapp/services/static") {
            sh("pnpm cypress:clean");
            exec(["./dev/cypress/e2e/tools/gen-skipped-e2e-tests.js", "${E2E_URL}"]);
            stash includes: SKIPPED_E2E_FILENAME, name: SKIPPED_STASH_ID;
         };
      }
      stage("Run e2e tests") {
         withEnv([
            "COMMIT_INFO_BRANCH=${SHORT_REVISION_DESCRIPTION}",
            "KA_SKIP_GEN=1",
         ]) {
            runAllTestClients();
         }
      }

      stage("Analyzing Results") {
         def folders = unstashReports();
         analyzeResults(folders);
      }
   }
}
