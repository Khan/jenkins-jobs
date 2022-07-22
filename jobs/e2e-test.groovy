// The pipeline job for e2e tests.
// TODO(csilvers): rename this job, and all references in it, from e2e->smoke.
//
// e2e tests are the smoketests run in the webapp repo, that hit a live
// website using selenium.
//
// This job can either run all tests, or a subset thereof, depending on
// how parameters are specified.

// The Jenkins "interrupt" exception: for failFast and user interrupt
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

@Library("kautils")
// Standard Math classes we use.
import java.lang.Math;
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.exec
//import vars.kaGit
//import vars.notify
//import vars.withTimeout
//import vars.onWorker
//import vars.withSecrets


new Setup(steps

).allowConcurrentBuilds(

// We do a lot of e2e-test runs, and QA would like to be able to see details
// for a bit longer.
).resetNumBuildsToKeep(
   250,

).addStringParam(
   "URL",
   "The url-base to run these tests against.",
   "https://www.khanacademy.org"

).addChoiceParam(
   "TEST_TYPE",
   """\
<ul>
  <li> <b>all</b>: run all tests</li>
  <li> <b>deploy</b>: run only those tests that are important to run at
        deploy-time (as identified by the `@run_on_every_deploy`
        decorator)</li>
  <li> <b>custom</b>: run a specified list of tests, defined in
        TESTS_TO_RUN </li>
</ul>
""",
   ["all", "deploy", "custom"]

).addStringParam(
   "TESTS_TO_RUN",
   """A space-separated list of tests to run. Only relevant if we've selected
   TEST_TYPE=custom above.""",
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
   """How many worker machines to use.""",
   onWorker.defaultNumTestWorkerMachines().toString()

).addBooleanParam(
   "USE_FIRSTINQUEUE_WORKERS",
   """If true, use the jenkins workers that are set aside for the
currently active deploy.  Obviously, this should only be set if you
are, indeed, the currently active deploy.  We reserve these machines
so the currently active deploy never has to wait for smoketest workers
to spin up.""",
   false

).addStringParam(
   "CLIENTS_PER_WORKER",
   """How many end-to-end tests to run on each worker machine.  It
will depend on the size of the worker machine, which you can see in
the <code>Instance Type</code> value for the
<code>ka-test worker</code> ec2 setup at
<a href=\"/configure\">the Jenkins configure page</a>.<br><br>
Here's one way to figure out the right value: log into a worker
machine and run:
<blockqoute>
<pre>
cd webapp-workspace/webapp
. ../env/bin/activate
for num in `seq 1 16`; do echo -- \$num; time tools/runsmoketests.py -j\$num >/dev/null 2>&1; done
</pre>
</blockquote>
and pick the number with the shortest time.  For m3.large,
the best value is 4.""",
   "4"

).addBooleanParam(
   "USE_SAUCE",
   """Use SauceLabs to record a video of any tests that fail.
This slows down the tests significantly but is often helpful for debugging.""",
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
   """Set by deploy-webapp when we are in the middle of migrating traffic;
this causes us to set the magic cookie to send tests to the new version.
Only works when the URL is www.khanacademy.org.""",
   false

).addStringParam(
   "EXPECTED_VERSION",
   """Set along with SET_SPLIT_COOKIE if we wish to verify we got the right
version.  Currently only supported when we are deploying dynamic.
TODO(csilvers): move this to wait_for_default.py and with
EXPECTED_VERSION_SERVICES.""",
   ""

).addStringParam(
   "EXPECTED_VERSION_SERVICES",
   """Used with EXPECTED_VERSION.  If set (as a space-separated list),
we busy-wait until all these services's /_api/version calls return
EXPECTED_VERSION.
TODO(csilvers): actually use this!""",
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
   """Space-separated list of tests to be skipped by the test runner.
   Tests should be the full path - e.g.
   web.response.end_to_end.loggedout_smoketest.LoggedOutPageLoadTest""",
   ""

).apply();

REVISION_DESCRIPTION = params.REVISION_DESCRIPTION ?: params.GIT_REVISION;
E2E_URL = params.URL[-1] == '/' ? params.URL.substring(0, params.URL.length() - 1): params.URL;

currentBuild.displayName = ("${currentBuild.displayName} " +
                            "(${REVISION_DESCRIPTION})");


// We set these to real values first thing below; but we do it within
// the notify() so if there's an error setting them we notify on slack.
NUM_WORKER_MACHINES = null;
CLIENTS_PER_WORKER = null;
// GIT_SHA1 is the sha1 for GIT_REVISION.
GIT_SHA1 = null;

// Set the server protocol+host+port as soon as we're ready to start
// the server.
TEST_SERVER_URL = null;

// Used to tell whether all the test-workers raised an exception.
WORKERS_RAISING_EXCEPTIONS = 0;
public class TestFailed extends Exception {}  // for use with the above
// Used to make sure we finish our job as soon as all tests are run.
public class TestsAreDone extends Exception {}

// We have a dedicated set of workers for the second smoke test.
WORKER_TYPE = (params.USE_FIRSTINQUEUE_WORKERS
               ? 'ka-firstinqueue-ec2' : 'ka-test-ec2');


// Gloabally scoped to allow the test runner to set this and allow us to skip analysis as well
skipTestAnalysis = false

// Run body, set the red circle on the flow-pipeline stage if it fails,
// but do not fail the overall build.  Re-raises "interrupt" exceptions,
// either due to a failFast or due to a user interrupt.
def swallowExceptions(Closure body, Closure onException = {}) {
    try {
        body();
    } catch (FlowInterruptedException e) {   // user interrupt
        echo("Interrupted!");
        throw e;
    } catch (e) {
        echo("Swallowing exception: ${e}");
        onException();
    }
}

def initializeGlobals() {
   NUM_WORKER_MACHINES = params.NUM_WORKER_MACHINES.toInteger();
   CLIENTS_PER_WORKER = params.CLIENTS_PER_WORKER.toInteger();
   if (params.TEST_TYPE == "custom") {
      // If we've specified a list of tests to run, there may be very few;
      // don't spin up more workers than we need.  This is slightly a lie --
      // you might have specified a module which contains many test-cases --
      // but luckily the deploy system, which is the primary user of this
      // option, doesn't do that.  (And if somehow we do, we'll still run the
      // tests, just on fewer workers.)
      def numTests = params.TESTS_TO_RUN.split().size()
      if (numTests < NUM_WORKER_MACHINES * CLIENTS_PER_WORKER) {
         NUM_WORKER_MACHINES = Math.ceil(
            (numTests/CLIENTS_PER_WORKER).doubleValue()).toInteger();
      }
   }
   // We want to make sure all nodes below work at the same sha1,
   // so we resolve our input commit to a sha1 right away.
   GIT_SHA1 = kaGit.resolveCommitish("git@github.com:Khan/webapp",
                                     params.GIT_REVISION);
}


def _setupWebapp() {
   kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", GIT_SHA1);

   dir("webapp") {
      sh("make clean_pyc");
      // One of our smoketests runs js code:
      // GraphQLSchemaIntegrationSmokeTest.test_graphql_schema_validates
      sh("make python_deps npm_deps");
   }
}

// Run the test-server.  Must be on a server node.
def runTestServer() {
   _setupWebapp();

   // Try to load the server's test-info db.
   try {
      onMaster('1m') {
         stash(includes: "test-info.db", name: "test-info.db before");
      }
      sh("rm -f test-info.db");
      unstash(name: "test-info.db before");
      sh("touch test-info.db.lock");   // split_tests.py needs this too
   } catch (e) {
      // Proceed anyway -- perhaps the file doesn't exist yet.
      // Ah well; we'll just serve tests in a slightly sub-optimal order.
      echo("Unable to restore test-db from server, won't sort tests by time: ${e}");
   }

   dir("webapp") {
      def runSmokeTestsArgs = ["--prod",
                               "--timing-db=../test-info.db",
                               "--file-for-not-run-tests=../not-run-tests.txt",
                              ];
      if (params.TEST_TYPE == "deploy") {
         runSmokeTestsArgs += ["--deploy-tests-only"];
      }
      if (params.TEST_TYPE == "custom") {
         runSmokeTestsArgs += ["--test-match=${params.TESTS_TO_RUN}"];
      }
      if (params.SKIP_TESTS) {
         runSmokeTestsArgs += ["--skip-tests=${params.SKIP_TESTS}"];
      }

      // Determine if we actually need to run any tests at all
      // TODO(dbraley): This process takes a few seconds to run, and is done 
      //  again by the actual server run a bit later in this method. We should
      //  make this faster, or at least able to reuse the test list we've 
      //  already calculated.
      tests = exec.outputOf(["testing/runtests_server.py", "-n"] + runSmokeTestsArgs + ["."]);

      // The runtests_server.py script with -n outputs all tests to run of 
      // various types. We only need to worry about smoke tests, which are 
      // also the last section, so grab everything after the header. If it's 
      // empty, there are no tests to run.
      e2eTests = tests.substring(tests.lastIndexOf("SMOKE TESTS:") + "SMOKE TESTS:".length())
      // An empty line indicates the end of the section (if found)
      emptyLineIndex = e2eTests.lastIndexOf('\n\n')
      if (emptyLineIndex >= 0) {
         e2eTests = e2eTests.substring(0, emptyLineIndex).trim()
      }

      echo("e2eTests: ${e2eTests}")
      if (e2eTests.isAllWhitespace()) {
         echo("No E2E Tests to run!")
         skipTestAnalysis = true
         throw new TestsAreDone();
      }

      // This gets our 10.x.x.x IP address.
      def serverIP = exec.outputOf(["ip", "route", "get", "10.1.1.1"]).split()[6];
      // This unblocks the test-workers to let them know they can connect
      // to the server.  Note we do this before the server starts up,
      // since the server is blocking (so we can't do it after), but the
      // clients know this and will retry when connecting.
      TEST_SERVER_URL = "http://${serverIP}:5001";

      // runtests_server.py writes to this directory.  Make sure it's clean
      // before it does so, so it doesn't read "old" data.
      sh("rm -rf genfiles/test-reports");

      // START THE SERVER!  Note this blocks.  It will auto-exit when
      // it's done serving all the tests.
      // "HOST=..." lets other machines connect to us.
      sh("env HOST=${serverIP} testing/runtests_server.py ${exec.shellEscapeList(runSmokeTestsArgs)} .");
   }

   // The server updated test-info.db as it ran.  Let's store the updates!
   try {
      stash(includes: "test-info.db", name: "test-info.db after");
      onMaster('1m') {
         unstash(name: "test-info.db after");
      }
   } catch (e) {
      // Oh well; hopefully another job will do better.
      echo("Unable to push test-db back to server: ${e}");
   }

   // This lets us cancel any clients that haven't started up yet.
   // See the comments in runTests() for more details.
   throw new TestsAreDone();
}

// Run one test-client on a worker machine.  A "test client" is a
// single thread of execution that runs on a worker.  A worker may run
// multiple test clients in parallel.  Each test-client runs
// runsmoketests.py separately.
def runTestClient(workerId, clientId) {
   echo("Starting tests for client ${workerId}-${clientId}");

   // The `env` is apparently needed to avoid hanging with
   // the chrome driver.  See
   // https://github.com/SeleniumHQ/docker-selenium/issues/87
   // We also work around https://bugs.launchpad.net/bugs/1033179
   def args = ["env", "DBUS_SESSION_BUS_ADDRESS=/dev/null", "TMPDIR=/tmp",
               "xvfb-run", "-a",
               "tools/runsmoketests.py",
               "--url=${E2E_URL}",
               "--xml", "--xml-dir=genfiles/test-reports",
               "--quiet", "--jobs=1", "--retries=3",
               "--driver=chrome",
               TEST_SERVER_URL];
   if (params.USE_SAUCE) {
      args += ["--backup-driver=sauce"];
   }
   if (params.SET_SPLIT_COOKIE) {
      args += ["--set-split-cookie"];
   }
   if (params.EXPECTED_VERSION) {
      args += ["--expected-version=${params.EXPECTED_VERSION}"];
   }

   dir("webapp") {
      // If a test (or the test runner) fails, it's not a fatal error;
      // another client might compensate.  We'll figure it out all out
      // in the analyze step, when we see what the junit files say.
      swallowExceptions {
         exec(args);
      }
   }
}

// Run all the test-clients on a single worker machine, in parallel.
def runTestWorker(workerId) {
   // Say what machine we're on, to help with debugging.
   def localIP = exec.outputOf(["ip", "route", "get", "10.1.1.1"]).split()[6];
   echo("Running on ${localIP}");

   def jobs = [:];
   for (def i = 0; i < CLIENTS_PER_WORKER; i++) {
      def clientId = i;  // avoid scoping problems
      jobs["client-$workerId-$clientId"] = { runTestClient(workerId, clientId); };
   }

   sh("rm -rf webapp/genfiles/test-reports");
   sh("mkdir -p webapp/genfiles/test-reports");
   // The worker=... here is not used by code, it's just for documentation.
   // In particular, the test-server will log a line like:
   //    [10.0.4.23] GET /begin?worker=worker-5
   // which is very useful because later the server will log:
   //    [10.0.4.23] Sending these tests: ....
   // and the earlier logline tells us that it's worker-5 that ran those tests.
   exec(["curl", "--retry", "240", "--retry-delay", "1", "--retry-connrefused",
         "${TEST_SERVER_URL}/begin?worker=worker-${workerId}"]);
   try {
      // We need secrets to talk to saucelabs.
      // TODO(csilvers): only do this if USE_SAUCE is true?
      withSecrets() {
         parallel(jobs);
      }
   } finally {
      try {
         // Collect all the junit xml files into one file for uploading.
         // The "/dev/null" arg is to make sure something happens even
         // if the `find` returns no files.
         sh("find webapp/genfiles/test-reports -name '*.xml' -print0 | xargs -0 cat /dev/null | webapp/testing/junit_cat.sh > junit.xml");
         // This stores the xml file on the test-server machine at
         // genfiles/test-reports/junit-<id>.xml.
         exec(["curl", "--retry", "3",
               "--upload-file", "junit.xml",
               "${TEST_SERVER_URL}/end?filename=junit-${workerId}.xml"]);
      } catch (e) {
         echo("Error sending junit results to /end: ${e}");
         // Better to not pass a junit file than to not /end at all.
         try {
            exec(["curl", "--retry", "3", "${TEST_SERVER_URL}/end"]);
         } catch (e2) {
            echo("Error sending /end at all: ${e2}");
         }
      }
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
         swallowExceptions({
            onWorker(WORKER_TYPE, '2h') {
                _setupWebapp();
                // We also need to sync mobile, so we can run
                // content/end_to_end/android_integration_smoketest.py.
                // Note that Mobile latest code is at develop branch
                // TODO(benkraft): Do this in runmsoketests.py instead
                // (at need), or in the smoketest itself?
                kaGit.safeSyncToOrigin("git@github.com:Khan/mobile", "develop");
                // We can go no further until we know the server to connect to.
                waitUntil({ TEST_SERVER_URL != null });
                runTestWorker(workerId);
            }
         }, onException);
      };
   }
   parallel(jobs);
}

def runLambda(){
   // In case the main provider is down, shift a build-job to 'e2e-test-cypress-backup'
   build(job: 'e2e-test-cypress',
          parameters: [
             string(name: 'SLACK_CHANNEL', value: "#cypress-testing"),
             string(name: 'REVISION_DESCRIPTION', value: REVISION_DESCRIPTION),
             string(name: 'DEPLOYER_USERNAME', value: params.DEPLOYER_USERNAME),
             string(name: 'URL', value: E2E_URL),
             string(name: 'NUM_WORKER_MACHINES', value: params.NUM_WORKER_MACHINES),
             string(name: 'TEST_RETRIES', value: "1"),
            // It takes about 5 minutes to run all the Cypress e2e tests when
            // using the default of 20 workers. This build is running in parallel
            // with runTests(). During this test run we don't want to disturb our 
            // mainstream e2e pipeline, so set propagate to false.
          ],
          propagate: false,
          // The pipeline will NOT wait for this job to complete to avoid
          // blocking the main pipeline (e2e tests).
          wait: false,
          );
}

def runTests() {
   // We want to immediately exit if:
   // (a) the server dies unexpectedly.
   // (b) all the clients die unexpectedly.
   // (c) all tests are done.
   // Of these, the only straightforward one is (a).  For (b), we only
   // want to fail if *all* clients die; if just one dies the others
   // can pick up the slack.  We carefully set up runAllTestClients to
   // only raise an exception when all clients die.  (c) seems
   // straightforward but is tricky when some clients handle all the
   // tests before the rest of the clients have even started up their
   // gce instance.  The only way to cancel the gce-startup
   // immediately is via an failFast plus an exception.  So we use
   // failfast, but swallow the exception in the case of (c) since it's
   // not really an error.
   try {
      parallel([
         failFast: true,
         "test-server": { withTimeout('1h') { runTestServer(); } },
         "test-clients": { withTimeout('1h') { runAllTestClients(); } },
      ]);
    } catch (TestsAreDone e) {
      // Ignore this "error": it's thrown on successful test completion.
      echo("Tests are done!");
    }
}


def analyzeResults() {
   withTimeout('5m') {
      if (currentBuild.result == 'ABORTED') {
         // No need to report the results in the case of abort!  They will
         // likely be more confusing than useful.
         echo('We were aborted; no need to report results.');
         return;
      }

      // Send a special message if all workers fail.
      if (WORKERS_RAISING_EXCEPTIONS == NUM_WORKER_MACHINES) {
         def msg = ("All test workers failed!  Check " +
                    "${env.BUILD_URL}flowPipelineSteps to see why.)");
         notify.fail(msg);
      }

      // The test-server wrote junit files to this dir as it ran.
      junit("webapp/genfiles/test-reports/*.xml");

      withSecrets() {     // we need secrets to talk to slack!
         dir("webapp") {
            rerunCommand = "tools/runsmoketests.py --driver chrome " + (
                  E2E_URL == "https://www.khanacademy.org"
                  ? "--prod"
                  : "--url ${exec.shellEscape(E2E_URL)}");
            summarize_args = [
               "testing/testresults_util.py", "summarize-to-slack",
               "genfiles/test-reports/", params.SLACK_CHANNEL,
               "--jenkins-build-url", env.BUILD_URL,
               "--deployer", params.DEPLOYER_USERNAME,
               // The label goes at the top of the message; we include
               // both the URL and the REVISION_DESCRIPTION.
               "--label", "${E2E_URL}: ${REVISION_DESCRIPTION}",
               "--not-run-tests-file", "../not-run-tests.txt",
               "--rerun-command", rerunCommand,
            ];
            if (params.SLACK_THREAD) {
               summarize_args += ["--slack-thread", params.SLACK_THREAD];
            }
            if (params.SLACK_CHANNEL != "#qa-log") {
               summarize_args += ["--cc-always", "#qa-log"];
            }
            // summarize-to-slack returns a non-zero rc if it detects
            // test failures.  We set the job to UNSTABLE in that case
            // (since we don't consider smoketest failures to be blocking).
            // But we still keep on running the rest of this script!
            catchError(buildResult: "UNSTABLE", stageResult: "UNSTABLE",
                       message: "There were test failures!") {
               exec(summarize_args);
            }
            // Let notify() know not to send any messages to slack,
            // because we just did it here.
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
                   when: ['FAILURE', 'UNSTABLE']],
           buildmaster: [sha: params.GIT_REVISION,
                         what: (E2E_URL == "https://www.khanacademy.org" ?
                                'second-smoke-test': 'first-smoke-test')]]) {
      initializeGlobals();

      try {
         stage("Running smoketests") {
            parallel([
               "run-tests": { runTests(); },
               "test-lambdacli": { runLambda(); },
            ]);
         }
      } finally {
         // If we determined there were no tests to run, we should skip 
         // analysis since it fails if there are no test results.
         if (skipTestAnalysis) {
            echo("Skipping Analysis - No tests run")
            return
         }

         // We want to analyze results even if -- especially if --
         // there were failures; hence we're in the `finally`.
         stage("Analyzing results") {
            analyzeResults();
         }
      }
   }
}
