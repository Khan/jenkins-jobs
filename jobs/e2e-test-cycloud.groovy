// Single job for cypress e2e tests.
//
// cypress e2e tests are the smoketests run in the webapp/feature/cypress repo,
// that hit a live website using lambdatest cli.

// The Jenkins "interrupt" exception: for failFast and user interrupt
//import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException;

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
   "#cypress-logs-next-test"

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
   "CYPRESS_GIT_REVISION",
   """A commit-ish to check out.  This only affects the version of the
E2E test used; it will probably match the tested version's code,
but it doesn't need to.""",
   "master"

).addStringParam(
   "REVISION_DESCRIPTION",
   """Set by the buildmaster to give a more human-readable description
of the CYPRESS_GIT_REVISION, especially if it is a commit rather than a branch.
Defaults to CYPRESS_GIT_REVISION.""",
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
REVISION_DESCRIPTION = params.REVISION_DESCRIPTION ?: params.CYPRESS_GIT_REVISION;
E2E_URL = params.URL[-1] == '/' ? params.URL.substring(0, params.URL.length() - 1): params.URL;

currentBuild.displayName = ("${currentBuild.displayName} " +
                            "(${REVISION_DESCRIPTION})");

// We use the build name as a unique identifier for user notifications.
BUILD_NAME = "build e2e-cypress-test #${env.BUILD_NUMBER} (${E2E_URL}: ${params.REVISION_DESCRIPTION})"

// GIT_SHA1 is the sha1 for CYPRESS_GIT_REVISION.
GIT_SHA1 = null;
REPORT_DIR = "webapp/genfiles"
REPORT_NAME = "results-combined.json"

// We have a dedicated set of workers for the second smoke test.
WORKER_TYPE = (params.USE_FIRSTINQUEUE_WORKERS
               ? 'ka-firstinqueue-ec2' : 'ka-test-ec2');

// Used to tell whether all the test-workers raised an exception.
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
                                        params.CYPRESS_GIT_REVISION);
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
           "targets",
           "mm-test-flaky"
   ];

   dir('webapp/services/static') {
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
            sh("pwd");
            unstash "worker-${i}-reports"
            sh("ls");
         }
      }
   }
   return jsonFolders
}

def analyzeResults(foldersList) {
   if (currentBuild.result == 'ABORTED') {
      // No need to report the results in the case of abort!  They will
      // likely be more confusing than useful.
      echo('We were aborted; no need to report results.');
      return;
   }

   // report-merged-results.ts is a new file
   kaGit.safePullInBranch("webapp/services/static/dev/cypress/e2e/tools", params.CYPRESS_GIT_REVISION);

   dir ('webapp/services/static') {
      sh("ls ./dev/cypress/e2e/tools");
      catchError(buildResult: "UNSTABLE", stageResult: "UNSTABLE",
              message: "There were test failures!") {
//         exec(["./dev/cypress/e2e/tools/report-merged-results.ts", *foldersList]);
//         sh "npx --yes tsx ./dev/cypress/e2e/tools/report-merged-results.ts ${foldersList.join(' ')}"
         exec(["npx", "--yes", "tsx", "./dev/cypress/e2e/tools/report-merged-results.ts", *foldersList]);
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
           buildmaster: [sha: params.CYPRESS_GIT_REVISION,
                         what: E2E_RUN_TYPE]]) {

      initializeGlobals();
      stage("Run e2e tests") {
         runAllTestClients();
      }

      stage("Analyzing Results") {
         def folders = unstashReports();
         analyzeResults(folders);
      }
   }
}
