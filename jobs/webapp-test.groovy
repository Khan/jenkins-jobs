// The pipeline job for webapp tests.
//
// webapp tests are the tests run in the webapp repo, including python
// tests, javascript tests, and linters (which aren't technically tests).
//
// This job can either run all tests, or a subset thereof, depending on
// how parameters are specified.

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


new Setup(steps

).allowConcurrentBuilds(

// We do a *ton* of webapp-test runs, often >100 each day.  Make sure we don't
// clean them too quickly.
).resetNumBuildsToKeep(
   500,

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

If the empty string, run *all* tests.  Note that regardless of this
value, the list of tests to run is limited by MAX_SIZE.""",
   "origin/master",

).addChoiceParam(
   "MAX_SIZE",
   """The largest size tests to run, as per tools/runtests.py.""",
   ["medium", "large", "huge", "small", "tiny"]

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
   onWorker.defaultNumTestWorkerMachines().toString()

).addStringParam(
   "JOBS_PER_WORKER",
   """How many tests to run on each worker machine.  In my testing,
the best value -- the one that minimizes the total test time -- is
`#cpus + 1`.  This makes sure each CPU is occupied with a test, while
having a "spare" test for when all the existing tests are doing
something I/O bound.  `#cpus` is also reasonable.""",
   "1"

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
JOBS_PER_WORKER = null;
// GIT_SHA1 is the sha1 for GIT_REVISION.
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

// If we're running the large or huge tests, we need a bit more
// memory, because some of those tests seem to use a lot of memory.
// So we have a special worker type.
WORKER_TYPE = (params.MAX_SIZE in ["large", "huge"]
               ? 'big-test-worker' : 'ka-test-ec2');

WORKER_TIMEOUT = params.MAX_SIZE == 'huge' ? '4h' : '2h';


def initializeGlobals() {
   NUM_WORKER_MACHINES = params.NUM_WORKER_MACHINES.toInteger();
   JOBS_PER_WORKER = params.JOBS_PER_WORKER.toInteger();
   // We want to make sure all nodes below work at the same sha1,
   // so we resolve our input commit to a sha1 right away.
   GIT_SHA1 = kaGit.resolveCommitish("git@github.com:Khan/webapp",
                                     params.GIT_REVISION);
}


def _setupWebapp() {
   kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", GIT_SHA1);

   dir("webapp") {
      clean(params.CLEAN);
      // TOOD(csilvers): we could get away with only running
      // python_deps, and just npm_deps if running js tests,
      // and go_deps if running go tests/lints.
      sh("make python_deps npm_deps");
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
   // The `dir("genfiles")` creates a genfiles@tmp directory which
   // confuses lint_test.py (it tries to make a Lint_genfiles__tmp
   // test-class, which of course doesn't work on the workers).
   // Let's remove it.
   sh("rm -rf genfiles@tmp");

   def runtestsArgs = ["--max-size=${params.MAX_SIZE}",
                       "--override-skip-by-default",
                       "--timing-db=genfiles/test-info.db",
                     ];

   if (params.BASE_REVISION) {
      // Only run the tests that are affected by files that were
      // changed between BASE_REVISION and GIT_REVISION.
      def testsToRun = exec.outputOf(
         ["deploy/should_run_tests.py",
          "--from-commit=${params.BASE_REVISION}",
          "--to-commit=${params.GIT_REVISION}"
         ]).split("\n") as List;
      runtestsArgs += testsToRun;
      echo("Running ${testsToRun.size()} tests");
   } else {
      runtestsArgs += ["."];
      echo("Running all tests");
   }

   // This isn't needed until tests are done.
   // The `sed` gets rid of all tests except python tests.
   // The `grep -v :` gets rid of the header line, which is not an actual test.
   // TODO(csilvers): do this differently once we move to per-language clients.
   // TODO(csilvers): integrate it with the normal runtests_server run
   // so we don't have to do it separately.
   sh("testing/runtests_server.py -n ${exec.shellEscapeList(runtestsArgs)} | sed -n '/PYTHON TESTS:/,/^\$/p' | grep -v : > genfiles/test-specs.txt")

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

   // Start the server.  Note this blocks.  It will auto-exit when
   // it's done serving all the tests.  "HOST=..." lets other machines
   // connect to us.
   sh("env HOST=${serverIP} testing/runtests_server.py ${exec.shellEscapeList(runtestsArgs)}")

   // Failing test-workers wait for this to be set so they all finish together.
   TESTS_ARE_DONE = true;
}

def _runOneTest(splitId) {
   def runtestsArgs = [
       "--pickle",
       "--pickle-file=../test-results.${splitId}.pickle",
       "--quiet",
       "--jobs=1",
       TEST_SERVER_URL];

   // An extra begin-end pair so we can upload the pickle-file to the server.
   exec(["curl", "--retry", "240", "--retry-delay", "1",
         "--retry-connrefused", "${TEST_SERVER_URL}/begin"]);
   try {
      sh("cd webapp; " +
         // Say what machine we're on, to help with debugging
         "ifconfig; " +
         "curl -s -HMetadata-Flavor:Google http://metadata.google.internal/computeMetadata/v1/instance/hostname | cut -d. -f1; " +
         "../jenkins-jobs/timeout_output.py -v 55m " +
         "tools/runtests.py ${exec.shellEscapeList(runtestsArgs)} ");
   } finally {
      try {
         // This stores the pickle file on the test-server machine in
         // genfiles/test-reports/.  It's our very own stash()!
         exec(["curl", "--retry", "3",
               "--upload-file", "test-results.${splitId}.pickle",
               "${TEST_SERVER_URL}/end?filename=test-results.${splitId}.pickle"]);
      } catch (e) {
         // Better to not pass a pickle file than to do nothing.
         exec(["curl", "--retry", "3", "${TEST_SERVER_URL}/end"]);
         throw e;
      }
   }
}

def doTestOnWorker(workerNum) {
   // Normally each worker should take 20-30m so we give them an hour
   // or two just in case; when running huge tests, the one that gets
   // make_test_db_test can take 2+ hours so we give it lots of time.
   onWorker(WORKER_TYPE, WORKER_TIMEOUT) {
      // We can sync webapp right away, before we know what tests we'll be
      // running.
      _setupWebapp();

      // Out with the old, in with the new!
      sh("rm -f test-results.*.pickle");

      // We continue to hold the worker while waiting, so we can make sure to
      // get the same one, and start right away, once ready.
      waitUntil({ TEST_SERVER_URL != null });

      def parallelTests = ["failFast": false];
      for (def i = 0; i < JOBS_PER_WORKER; i++) {
         def id = "$workerNum-$i";
         parallelTests["job-$id"] = { _runOneTest(id); };
      }

      parallel(parallelTests);
   }
}

public class TestsAreDone extends Exception {}

def runTests() {
   def jobs = [
      // This is a kwarg that tells parallel() what to do when a job fails.
      // We abuse it to handle this situation, which there's no other good
      // way to handle: we have 2 test-workers, and the first one finishes
      // all the tests while the second one is still being provisioned.
      // We want to cancel the provisioning and end the job right away,
      // not wait until the worker is provisioned and then have it be a noop.
      "failFast": true,
      "serving-tests": {
         withTimeout(WORKER_TIMEOUT) {
            _setupWebapp();
            dir("webapp") {
               _startTestServer();
            }
            throw new TestsAreDone();
         }
      }
   ];
   for (def i = 0; i < NUM_WORKER_MACHINES; i++) {
      // A restriction in `parallel`: need to redefine the index var here.
      def workerNum = i;
      jobs["test-${workerNum}"] = {
         try {
            doTestOnWorker(workerNum);
         } catch(e) {
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

      def foundAPickleFile = false;
      for (def i = 0; i < NUM_WORKER_MACHINES; i++) {
         for (def j = 0; j < JOBS_PER_WORKER; j++) {
            if (fileExists("webapp/genfiles/test-reports/test-results.${i}-${j}.pickle")) {
               foundAPickleFile = true;
            }
         }
      }
      // Send a special message if all workers fail, because that's not good
      // (and the normal script can't handle it).
      if (!foundAPickleFile) {
         def msg = ("All test workers failed!  Check " +
                    "${env.BUILD_URL}consoleFull to see why.)");
         notify.fail(msg);
      }

      withSecrets() {     // we need secrets to talk to slack!
         dir("webapp") {
            sh("tools/test_pickle_util.py merge " +
               "genfiles/test-reports/test-results.*.pickle " +
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

            // We try to keep the command short and clear.
            // max-size is the only option we need locally, and only
            // if it is not "medium".
            maxSizeParam = "";
            if (params.MAX_SIZE != "medium") {
               maxSizeParam = (
                  " --max-size=${exec.shellEscape(params.MAX_SIZE)}");
            }
            rerunCommand = "tools/runtests.py${maxSizeParam} --override-skip-by-default"
            summarize_args = [
               "tools/test_pickle_util.py", "summarize-to-slack",
               "genfiles/test-results.pickle", params.SLACK_CHANNEL,
               "--jenkins-build-url", env.BUILD_URL,
               "--deployer", params.DEPLOYER_USERNAME,
               // The commit here is just used for a human-readable
               // slack message, so we use REVISION_DESCRIPTION.
               "--label", REVISION_DESCRIPTION,
               "--expected-tests-file", "genfiles/test-specs.txt",
               "--rerun-command", rerunCommand,
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


onWorker(WORKER_TYPE, '5h') {     // timeout
   notify([slack: [channel: params.SLACK_CHANNEL,
                   thread: params.SLACK_THREAD,
                   sender: 'Testing Turtle',
                   emoji: ':turtle:',
                   when: ['FAILURE', 'UNSTABLE']],
           aggregator: [initiative: 'infrastructure',
                        when: ['SUCCESS', 'BACK TO NORMAL',
                               'FAILURE', 'ABORTED', 'UNSTABLE']],
           buildmaster: [sha: params.GIT_REVISION,
                         what: 'webapp-test']]) {
      initializeGlobals();

      try {
         stage("Running tests") {
            runTests();
         }
      } finally {
         // We want to analyze results even if -- especially if --
         // there were failures; hence we're in the `finally`.
         stage("Analyzing results") {
            analyzeResults();
         }
      }
   }
}
