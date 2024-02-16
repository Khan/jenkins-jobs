// The pipeline job for webapp tests.
//
// webapp tests are the tests run in the webapp repo, including python
// tests, javascript tests, and linters (which aren't technically tests).
//
// This job can either run all tests, or a subset thereof, depending on
// how parameters are specified.

// The Jenkins "interrupt" exception: for failFast and user interrupt
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.buildmaster
//import vars.clean
//import vars.exec
//import vars.kaGit
//import vars.notify
//import vars.withTimeout
//import vars.onWorker
//import vars.withSecrets
//import vars.withVirtualenv


new Setup(steps

).allowConcurrentBuilds(

// We do a *ton* of webapp-test runs, often >100 each day.  Make sure we don't
// clean them too quickly.
).resetNumBuildsToKeep(
   1500,

).addStringParam(
   "GIT_REVISION",
   """The git commit-hash to run tests at, or a symbolic name referring
to such a commit-hash.""",
   "master"

).addStringParam(
   "BASE_REVISION",
   """Only run tests that could be affected by files that were changed in
the commits between BASE_REVISION..GIT_REVISION.  It's always safe to
have BASE_REVISION == origin/master, since we keep the invariant that
all tests pass on master.  But in some cases we can do better: at
deploy-time it can be the revision of the current live deploy, and for
phabricator diffs it can be the commit `arc land` would land the diff
being tested.

If the empty string, run *all* tests.""",
   "origin/master",

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

).addChoiceParam(
   "CLEAN",
   """\
<ul>
  <li> <b>some</b>: Clean the workspaces (including .pyc files)
         but not genfiles
  <li> <b>most</b>: Clean the workspaces and genfiles, excluding
         js/ruby/python modules
  <li> <b>all</b>: Full clean that results in a pristine working copy
  <li> <b>none</b>: Don't clean at all
</ul>""",
   ["some", "most", "all", "none"]

).addStringParam(
   "NUM_WORKER_MACHINES",
    """How many worker machines to use.""",
    // We divide by 2 because we use 2 clients per worker by default.
    (onWorker.defaultNumTestWorkerMachines() / 2).toString()

).addStringParam(
   "CLIENTS_PER_WORKER",
   """How many test-clients to run on each worker machine.  In my testing,
the best value -- the one that minimizes the total test time -- is
`#cpus`.  `#cpus + 1` may also work well, letting a CPU-bound test and
an IO-bound test share a processor.""",
   "2"

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

).addStringParam(
   "JOB_PRIORITY",
   """The priority of the job to be run (a lower priority means it is run
sooner). The Priority Sorter plugin reads this parameter in to reorder jobs
in the queue accordingly. Should be set to 3 if the job is depended on by
the currently deploying branch, otherwise 6. Legal values are 1
through 11. See https://jenkins.khanacademy.org/advanced-build-queue/
for more information.""",
   "6"

).apply()


REVISION_DESCRIPTION = params.REVISION_DESCRIPTION ?: params.GIT_REVISION;

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

// The tests each workers should run.  Each element is a map:
// cmd: the command to run.  "<server>" is replaced by TEST_SERVER_URL.
// oneAtATime: true if it's not ok to run multiple instances of cmd
//    on the same machine at the same time.  Usually it's false, but
//    golangci-lint, e.g., complains if two of them run at once.
//    Can be omitted entirely when it's false.
// done: starts at false, set to true when a client finishes with
//    the last file for a given test.  This is the only element
//    that is modified.  It's used only as a small perf optimization;
//    things still work even if `done` is never set to true.
TESTS = [
    [cmd: "testing/typecheck_test_client.sh -j1 <server>", done: false],
    [cmd: "testing/mypy_test_client.sh -j1 <server>", done: false],
    [cmd: "tools/runtests.sh -j 1 --server=<server>/tests/kotlin", oneAtATime: true, done: false],
    [cmd: "tools/runtests.sh -j 1 --server=<server>/tests/python", done: false],
    [cmd: "tools/runlint.sh -j 1 --server=<server>/lint/javascript", done: false],
    [cmd: "tools/runlint.sh -j 1 --server=<server>/lint/python", done: false],
    [cmd: "tools/runlint.sh -j 1 --server=<server>/lint/go", oneAtATime: true, done: false],
    [cmd: "tools/runlint.sh -j 1 --server=<server>/lint/kotlin", done: false],
    [cmd: "tools/runlint.sh -j 1 --server=<server>/lint/other", done: false],
    [cmd: "tools/runtests.sh -j 1 --server=<server>/tests/javascript", done: false],
    [cmd: "tools/runtests.sh -j 1 --server=<server>/tests/go", done: false],
];

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
   // We want to make sure all nodes below work at the same sha1,
   // so we resolve our input commit to a sha1 right away.
   GIT_SHA1 = kaGit.resolveCommitish("git@github.com:Khan/webapp",
                                     params.GIT_REVISION);
}


def _setupWebapp() {
   kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", GIT_SHA1);

   dir("webapp") {
      clean(params.CLEAN);
      sh("make python_deps");
      // We always delete the ka-lint cache so we run all lints every time.
      // In theory we could use the cache, but just to be safe...
      sh("rm -rf genfiles/ka-lint");
   }
}

// Run the test-server.  Must be on a server node.
def runTestServer() {
   _setupWebapp();

   // Try to load the server's test-info db.
   try {
      onMaster('1m') {
         stash(includes: "test-info.db*", name: "test-info.db before");
      }
      sh("rm -f test-info.db");
      unstash(name: "test-info.db before");
      sh("touch test-info.db.lock");   // split_tests.py needs this too
   } catch (e) {
      notify.rethrowIfAborted(e);
      // Proceed anyway -- perhaps the file doesn't exist yet.
      // Ah well; we'll just serve tests in a slightly sub-optimal order.
      echo("Unable to restore test-db from server, won't sort tests by time: ${e}");
   }

   withVirtualenv.python3() {
      dir("webapp") {
         if (params.BASE_REVISION) {
            // Only run the tests that are affected by files that were
            // changed between BASE_REVISION and GIT_REVISION.  We ignore
            // files where only sync tags have changed; those can't affect
            // tests.
            sh("deploy/trivial_diffs.py ${exec.shellEscape(params.BASE_REVISION)} ${exec.shellEscape(GIT_SHA1)} > ../trivial_diffs.txt");
            sh("git diff --name-only --diff-filter=ACMRTUB ${exec.shellEscape(params.BASE_REVISION)}...${exec.shellEscape(GIT_SHA1)} | fgrep -vx -f ../trivial_diffs.txt | testing/all_tests_for.py - > ../files_to_test.txt");
            // Sometimes we need to run some extra tests for deletd files.
            sh("git diff --name-only --diff-filter=D ${exec.shellEscape(params.BASE_REVISION)}...${exec.shellEscape(GIT_SHA1)} | fgrep -vx -f ../trivial_diffs.txt | testing/all_tests_for.py --deleted-mode - >> ../files_to_test.txt");
            // Note that unlike for tests, we consider deleted files for linting.
            sh("git diff --name-only --diff-filter=ACMRTUBD ${exec.shellEscape(params.BASE_REVISION)}...${exec.shellEscape(GIT_SHA1)} | testing/all_lint_for.py - > ../files_to_lint.txt");
         } else {
            sh("echo > ../trivial_diffs.txt");
            sh("echo . > ../files_to_test.txt");
            sh("echo . > ../files_to_lint.txt");
            echo("Running all tests.");
         }
         sh("cat ../files_to_test.txt");  // to help with debugging
         sh("cat ../files_to_lint.txt");
         sh("cat ../trivial_diffs.txt");

         def runtestsArgs = ["--port=5001",
                             "--timing-db=../test-info.db.json",
                             "--file-for-not-run-tests=../not-run-tests.txt",
                             "--lintfile=../files_to_lint.txt",
                           ];

         // Determine if we actually need to run any tests at all
         tests = exec.outputOf(["cat", "../files_to_test.txt"]);
         if (tests.isAllWhitespace()) {
            echo("No Unit Tests to run!")
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

         // Now that we have TEST_SERVER_URL, we can populate TESTS properly.
         def escapedServer = exec.shellEscape(TEST_SERVER_URL);
         for (i = 0; i < TESTS.size(); i++ ) {
            TESTS[i].cmd = TESTS[i].cmd.replace('<server>', escapedServer);
         }

         // runtests_server.py writes to this directory.  Make sure it's clean
         // before it does so, so it doesn't read "old" data.
         sh("rm -rf genfiles/test-reports");

         // START THE SERVER!  Note this blocks.  It will auto-exit when
         // it's done serving all the tests.
         // "HOST=..." lets other machines connect to us.
         try {
            sh("env HOST=${serverIP} go run ./dev/testing/cmd/runtests-server ${exec.shellEscapeList(runtestsArgs)} - < ../files_to_test.txt")
         } catch (e) {
            // Mark all tests as done so the clients stop iterating.  In
            // theory this isn't necessary: we're about to raise an
            // exception and we're in a failFast context, which should
            // cancel the clients.  But it seems Jenkins isn't always so
            // good at canceling with failFast, so this is a backup.
            for (def i = 0; i < TESTS.size(); i++) {
               TESTS[i].done = true;
            }
            throw e;
         }
      }
   }

   // The server updated test-info.db as it ran.  Let's store the updates!
   try {
      stash(includes: "test-info.db*", name: "test-info.db after");
      onMaster('1m') {
         unstash(name: "test-info.db after");
      }
   } catch (e) {
      notify.rethrowIfAborted(e);
      // Oh well; hopefully another job will do better.
      echo("Unable to push test-db back to server: ${e}");
   }

   // This lets us cancel any clients that haven't started up yet.
   // See the comments in runTests() for more details.
   throw new TestsAreDone();
}

// Run one test-client on a worker machine.  A "test client" is a
// single thread of execution that runs on a worker.  A worker may run
// multiple test clients in parallel.  Each test-client runs the
// individual test-runner processes in serial.  (A test-client never
// runs two things in parallel.)  Specifically, each test client will
// run all the test-runners in TESTS, one after the other.
def runTestClient(workerId, clientId) {
   echo("Starting tests for client ${workerId}-${clientId}");
   // Each client gets the tests in a different order, to maximize
   // the chance one client gets all the js tests, say, and the
   // others don't have to pay the overhead of building js deps.
   def startIndex = workerId * CLIENTS_PER_WORKER + clientId;
   dir("webapp") {
      for (def i = 0; i < TESTS.size(); i++) {
         def test = TESTS[(i + startIndex) % TESTS.size()];
         if (test.done) {
            continue;  // small optimization: another client finished it
         }
         if (test.oneAtATime && clientId != 0) {
            // To avoid the risk of running multiple copies of this
            // test at a time, we limit to running it on process 0.
            continue;
         }
         // If a test (or the test runner) fails, it's not a fatal error;
         // another client might compensate.  We'll figure it out all out
         // in the analyze step, when we see what the junit files say.
         swallowExceptions {
            sh(test.cmd);
            // If we get here, we finished without raising an exception!
            // That means this test has been run to completion.
            test.done = true;
         }
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
   withVirtualenv.python3() {
      try {
         parallel(jobs);
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
            notify.rethrowIfAborted(e);
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
      jobs["test-${workerId}"] = {
         swallowExceptions({
            onWorker('ka-test-ec2', '2h') {
                _setupWebapp();
                // We can go no further until we know the server to connect to.
                waitUntil({ TEST_SERVER_URL != null });
                runTestWorker(workerId);
            }
         }, onException);
      };
   }
   parallel(jobs);
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
         "test-server": { withTimeout('3h') { runTestServer(); } },
         "test-clients": { withTimeout('3h') { runAllTestClients(); } },
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
         notify.log("Aborted", ["job":"webapp-test"])
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

      withSecrets.slackAlertlibOnly() {
         dir("webapp") {
            pythonRerunCommand = "tools/runtests.sh";
            summarize_args = [
               "testing/testresults_util.py", "summarize-to-slack",
               "genfiles/test-reports/", params.SLACK_CHANNEL,
               "--jenkins-build-url", env.BUILD_URL,
               "--deployer", params.DEPLOYER_USERNAME,
               // The commit here is just used for a human-readable
               // slack message, so we use REVISION_DESCRIPTION.
               "--label", REVISION_DESCRIPTION,
               "--not-run-tests-file", "../not-run-tests.txt",
               "--rerun-command", pythonRerunCommand,
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
            // because we just did it here.
            env.SENT_TO_SLACK = '1';
         }
      }
      // TODO(ebrown): Remove: onWorker logs, so not needed here too
      notify.log("Finished ${env.JOB_NAME} ${env.BUILD_NUMBER}")
   }
}


onWorker('ka-test-ec2', '5h') {     // timeout
   // TODO(ebrown): Remove: onWorker logs, so not needed here too
   notify.log("Starting ${env.JOB_NAME} " +
              "${params.REVISION_DESCRIPTION} ${env.BUILD_NUMBER}", [
   ]);

   notify([slack: [channel: params.SLACK_CHANNEL,
                   thread: params.SLACK_THREAD,
                   sender: 'Testing Turtle',
                   emoji: ':turtle:',
                   when: ['FAILURE', 'UNSTABLE']],
           github: [sha: params.GIT_REVISION,
                    context: 'webapp-test',
                    when: ['SUCCESS', 'FAILURE', 'UNSTABLE', 'ABORTED']],
           buildmaster: [sha: params.GIT_REVISION,
                         what: 'webapp-test']]) {
      initializeGlobals();

      try {
         stage("Running tests") {
            runTests();
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
            withVirtualenv.python3() {
               analyzeResults();
            }
         }
      }
   }
}
