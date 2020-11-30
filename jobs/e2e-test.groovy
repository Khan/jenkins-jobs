// The pipeline job for e2e tests.

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
   "JOBS_PER_WORKER",
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
JOBS_PER_WORKER = null;
GIT_SHA1 = null;

// Set the server protocol+host+port as soon as we're ready to start
// the server.
TEST_SERVER_URL = null;

// Used to make sure we exit our test-running at the right time:
// either when all tests are done (so server is done, we need clients
// to be done too), or all test-clients fail (so clients are done, and
// we need server to be done too).
TESTS_ARE_DONE = false;
NUM_RUNNING_WORKERS = 0;
NUM_WORKER_FAILURES = 0;

// We have a dedicated set of workers for the second smoke test.
WORKER_TYPE = (params.USE_FIRSTINQUEUE_WORKERS
               ? 'ka-firstinqueue-ec2' : 'ka-test-ec2');

def initializeGlobals() {
   NUM_WORKER_MACHINES = params.NUM_WORKER_MACHINES.toInteger();
   JOBS_PER_WORKER = params.JOBS_PER_WORKER.toInteger();
   if (params.TEST_TYPE == "custom") {
      // If we've specified a list of tests to run, there may be very few;
      // don't spin up more workers than we need.  This is slightly a lie --
      // you might have specified a module which contains many test-cases --
      // but luckily the deploy system, which is the primary user of this
      // option, doesn't do that.  (And if somehow we do, we'll still run the
      // tests, just on fewer workers.)
      def numTests = params.TESTS_TO_RUN.split().size()
      if (numTests < NUM_WORKER_MACHINES * JOBS_PER_WORKER) {
         NUM_WORKER_MACHINES = Math.ceil(
            (numTests/JOBS_PER_WORKER).doubleValue()).toInteger();
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
      sh("make python_deps");
   }
}


def _startTestServer() {
   // Try to load the server's test-info db.
   try {
      onMaster('1m') {
         stash(includes: "test-info.db", name: "test-info.db before");
      }
      dir("genfiles") {
         sh("rm -f test-info.db");
         unstash(name: "test-info.db before");
      }
   } catch (e) {
      // Proceed anyway -- perhaps the file doesn't exist yet.
      // Ah well; we'll just serve tests in a slightly sub-optimal order.
      echo("Unable to restore test-db from server, won't sort tests by time: ${e}");
   }

   def runSmokeTestsArgs = ["--prod", "--timing-db=genfiles/test-info.db"];
   if (params.TEST_TYPE == "deploy") {
      runSmokeTestsArgs += ["--deploy-tests-only"];
   }
   if (params.TEST_TYPE == "custom") {
      runSmokeTestsArgs += ["--test-match=${params.TESTS_TO_RUN}"];
   }
   if (params.SKIP_TESTS) {
      runSmokeTestsArgs += ["--skip-tests=${params.SKIP_TESTS}"];
   }

   // This isn't needed until tests are done.
   // The `sed` gets rid of all tests except smoke tests.
   // The `grep -v :` gets rid of the header line, which is not an actual test.
   // TODO(csilvers): integrate it with the normal runtests_server run
   // so we don't have to do it separately.
   sh("testing/runtests_server.py -n ${exec.shellEscapeList(runSmokeTestsArgs)} . | sed -n '/SMOKE TESTS:/,/^\$/p' | grep -v : > genfiles/test-specs.txt")

   // This gets our 10.x.x.x IP address.
   def serverIP = exec.outputOf(["ip", "route", "get", "10.1.1.1"]).split()[6];
   // This unblocks the test-workers to let them know they can connect
   // to the server.  Note we do this before the server starts up,
   // since the server is blocking (so we can't do it after), but the
   // clients know this and will retry when connecting.
   TEST_SERVER_URL = "http://${serverIP}:5001";

   // Start the server.  Note this blocks.  It will auto-exit when
   // it's done serving all the tests.  "HOST=..." lets other machines
   // connect to us.
   sh("env HOST=${serverIP} testing/runtests_server.py ${exec.shellEscapeList(runSmokeTestsArgs)} .")

   // Failing test-workers wait for this to be set so they all finish together.
   TESTS_ARE_DONE = true;
}

def _runOneTest(splitId) {
   def args = ["xvfb-run", "-a", "tools/runsmoketests.py",
               "--url=${E2E_URL}",
               "--pickle", "--pickle-file=../test-results.${splitId}.pickle",
               "--timing-db=genfiles/test-info.db",
               "--xml-dir=genfiles/test-reports",
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

   try {
      exec(args);
   } catch (e) {
      // end-to-end failures are not blocking currently, so if
      // tests fail set the status to UNSTABLE, not FAILURE.
      echo("Smoketest-executable failed, marking build unstable: ${e}");
      currentBuild.result = "UNSTABLE";
   }
}

def doTestOnWorker(workerNum) {
   onWorker(WORKER_TYPE, '1h') {     // timeout
      // We can sync webapp right away, before we know what tests we'll be
      // running.
      _setupWebapp();
      // We also need to sync mobile, so we can run the mobile integration test
      // (content/end_to_end/android_integration_smoketest.py).
      // TODO(benkraft): Do this in runmsoketests.py instead (at need),
      //                 or in the smoketest itself?
      kaGit.safeSyncToOrigin("git@github.com:Khan/mobile", "master");

      // Out with the old, in with the new!
      sh("rm -f test-results.*.pickle");

      // Wait for the test-server to start up, or to say it's not going to.
      waitUntil({ TEST_SERVER_URL != null });

      def parallelTests = ["failFast": false];
      for (def i = 0; i < JOBS_PER_WORKER; i++) {
         def id = "$workerNum-$i";
         parallelTests["job-$id"] = { _runOneTest(id); };
      }

      NUM_RUNNING_WORKERS++;
      try {
         // This is apparently needed to avoid hanging with
         // the chrome driver.  See
         // https://github.com/SeleniumHQ/docker-selenium/issues/87
         // We also work around https://bugs.launchpad.net/bugs/1033179
         withEnv(["DBUS_SESSION_BUS_ADDRESS=/dev/null",
                  "TMPDIR=/tmp"]) {
            withSecrets() {   // we need secrets to talk to saucelabs
               dir("webapp") {
                  parallel(parallelTests);
               }
            }
         }
      } finally {
         // Now let the next stage see all the results.
         // runsmoketests.py should normally produce these files
         // even when it returns a failure rc (due to some test
         // or other failing).
         stash(includes: "test-results.*.pickle",
               name: "results ${workerNum}",
               allowEmpty: true);
         NUM_RUNNING_WORKERS--;
      }
   }
}

public class TestsAreDone extends Exception {}

def runTests() {
   def slackArgsWithoutChannel = ["jenkins-jobs/alertlib/alert.py",
                                  "--chat-sender=Testing Turtle",
                                  "--icon-emoji=:turtle:"];
   def slackArgs = (slackArgsWithoutChannel +
      ["--slack=${params.SLACK_CHANNEL}"]);
   if (params.SLACK_THREAD) {
      slackArgs += ["--slack-thread=${params.SLACK_THREAD}"];
   }
   def jobs = [
      // This is a kwarg that tells parallel() what to do when a job fails.
      // We abuse it to handle this situation, which there's no other good
      // way to handle: we have 2 test-workers, and the first one finishes
      // all the tests while the second one is still being provisioned.
      // We want to cancel the provisioning and end the job right away,
      // not wait until the worker is provisioned and then have it be a noop.
      "failFast": true,
      "serving-tests": {
         withTimeout('1h') {
            _setupWebapp();
            dir("webapp") {
               _startTestServer();
            }
            // If we get here, all tests have been run.  Wait to let
            // them finish stashing their results, then throw a
            // TestsAreDone "exception" to cause all our workers to
            // exit if they haven't already.
            // TODO(csilvers): pass the data in /end instead.
            waitUntil({ NUM_RUNNING_WORKERS == 0 });
            throw new TestsAreDone();
         }
      },
   ];
   for (def i = 0; i < NUM_WORKER_MACHINES; i++) {
      // A restriction in `parallel`: need to redefine the index var here.
      def workerNum = i;

      jobs["e2e-test-${workerNum}"] = {
         try {
            doTestOnWorker(workerNum);
         } catch (e) {
            NUM_WORKER_FAILURES++;
            // We don't *actually* want to failfast when a worker fails,
            // so just wait until the server says tests are over,
            // or until all the clients have failed (since the server
            // will never get to "all tests run" in that case).
            waitUntil({ TESTS_ARE_DONE || NUM_WORKER_FAILURES == NUM_WORKER_MACHINES });
            throw e;
         }
      };
   }

   try {
      parallel(jobs);
   } catch (TestsAreDone e) {
      // Ignore this "error": it's thrown on successful test completion.
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

      sh("rm -f test-results.*.pickle");
      for (def i = 0; i < NUM_WORKER_MACHINES; i++) {
         try {
            unstash("results ${i}");
         } catch (e) {
            // We'll mark the actual error next.
         }
      }

      def foundAPickleFile = false;
      for (def i = 0; i < NUM_WORKER_MACHINES; i++) {
         for (def j = 0; j < JOBS_PER_WORKER; j++) {
            if (fileExists("test-results.${i}-${j}.pickle")) {
               foundAPickleFile = true;
            }
         }
      }
      // Send a special message if all workers fail, because that's not good
      // (and the normal script can't handle it).
      if (!foundAPickleFile) {
         def msg = ("All test workers failed!  Check " +
                    "${env.BUILD_URL}consoleFull to see why.)");
         notify.fail(msg, "UNSTABLE");
      }

      withSecrets() {     // we need secrets to talk to slack!
         dir("webapp") {
            sh("tools/test_pickle_util.py merge " +
               "../test-results.*.pickle " +
               "genfiles/test-results.pickle");
            sh("tools/test_pickle_util.py update-timing-db " +
               "genfiles/test-results.pickle genfiles/test-info.db");

            // Try to send the timings back to the server.
            try {
               dir("genfiles") {
                  stash(includes: "test-info.db", name: "test-info.db after");
               }
               onMaster('1m') {
                  unstash(name: "test-info.db after");
               }
            } catch (e) {
               // Oh well; hopefully another job will do better.
               echo("Unable to push test-db back to server: ${e}");
            }

            summarize_args = [
               "tools/test_pickle_util.py", "summarize-to-slack",
               "genfiles/test-results.pickle", params.SLACK_CHANNEL,
               "--jenkins-build-url", env.BUILD_URL,
               "--deployer", params.DEPLOYER_USERNAME,
               // The label goes at the top of the message; we include
               // both the URL and the REVISION_DESCRIPTION.
               "--label", "${E2E_URL}: ${REVISION_DESCRIPTION}",
               "--expected-tests-file", "genfiles/test-specs.txt",
               "--cc-always", "#qa-log",
               // We try to keep the command short and clear.
               // We need only --url and -driver chrome.
               // If using www.khanacademy.org, we abbreviate --url to --prod.
               "--rerun-command",
               "tools/runsmoketests.py --driver chrome " + (
                     E2E_URL == "https://www.khanacademy.org"
                     ? "--prod"
                     : "--url ${exec.shellEscape(E2E_URL)}"),
            ];
            if (params.SLACK_THREAD) {
               summarize_args += ["--slack-thread", params.SLACK_THREAD];
            }
            // summarize-to-slack returns a non-zero rc if it detects
            // test failures.  We want to fail the entire job in that
            // case, but still keep on running the rest of this script!
            catchError(buildResult: "FAILURE", stageResult: "FAILURE",
                       message: "There were test failures!") {
               exec(summarize_args);
            }
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


onWorker(WORKER_TYPE, '5h') {  // timeout
   notify([slack: [channel: params.SLACK_CHANNEL,
                   thread: params.SLACK_THREAD,
                   sender: 'Testing Turtle',
                   emoji: ':turtle:',
                   when: ['FAILURE', 'UNSTABLE']],
           aggregator: [initiative: 'infrastructure',
                        when: ['SUCCESS', 'BACK TO NORMAL',
                               'FAILURE', 'ABORTED', 'UNSTABLE']],
           buildmaster: [sha: params.GIT_REVISION,
                         what: (E2E_URL == "https://www.khanacademy.org" ?
                                'second-smoke-test': 'first-smoke-test')],
           timeout: "2h"]) {
      initializeGlobals();

      try {
         stage("Running smoketests") {
            runTests();
         }
      } finally {
         // We want to analyze results even if -- especially if -- there
         // were failures; hence we're in the `finally`.
         stage("Analyzing results") {
            analyzeResults();
         }
      }
   }
}
