// The pipeline job for e2e tests.

@Library("kautils")
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
// TODO(benkraft): Keep more failures and fewer successes.
).resetNumBuildsToKeep(
   250,

).addStringParam(
   "URL",
   "The url-base to run these tests against.",
   "https://www.khanacademy.org"

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

).addStringParam(
   "NUM_WORKER_MACHINES",
   """How many worker machines to use.  This will function best
when it's equal to the <code>Instance Cap</code> value for
the <code>ka-test worker</code> ec2 setup at
<a href=\"/configure\">the Jenkins configure page</a>.  You'll need
to click on 'advanced' to see the instance cap.""",
   onWorker.defaultNumTestWorkerMachines().toString()

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

).addStringParam(
   "GIT_REVISION",
   """A commit-ish to check out.  This only affects the version of the
E2E test used; it will probably match the tested version's code,
but it doesn't need to.""",
   "master"

).addBooleanParam(
   "FAILFAST",
   "If set, stop running tests after the first failure.",
   false

).addStringParam(
   "DEPLOYER_USERNAME",
   """Who asked to run this job, used to ping on slack.
Typically not set manually, but rather by other jobs that call this one.""",
   ""

).addBooleanParam(
   "NOTIFY_BUILDMASTER",
   "If set, notify buildmaster on any notification.",
   false

).addStringParam(
   "REVISION_DESCRIPTION",
   """Set by the buildmaster to give a more human-readable description
of the GIT_REVISION, especially if it is a commit rather than a branch.
Defaults to GIT_REVISION.""",
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
version.  Currently only supported when we are deploying dynamic.""",
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
IS_ONE_GIT_SHA = null;

// Set to true once master has run setup, so graphql/android tests can begin.
HAVE_RUN_SETUP = false;
// Set to true once we have stashed the list of tests for the workers to run.
HAVE_STASHED_TESTS = false;

def initializeGlobals() {
   NUM_WORKER_MACHINES = params.NUM_WORKER_MACHINES.toInteger();
   JOBS_PER_WORKER = params.JOBS_PER_WORKER.toInteger();
   // We want to make sure all nodes below work at the same sha1,
   // so we resolve our input commit to a sha1 right away.
   GIT_SHA1 = kaGit.resolveCommitish("git@github.com:Khan/webapp",
                                     params.GIT_REVISION);
   // Required for buildmaster to accept a notification
   IS_ONE_GIT_SHA = true;
}


def _setupWebapp() {
   kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", GIT_SHA1);
   dir("webapp") {
      sh("make clean_pyc");
      sh("make python_deps");
   }
}


def runAndroidTests(slackArgs, slackArgsWithoutChannel) {
   // Wait until the other thread has finished setup, then go.
   waitUntil({ HAVE_RUN_SETUP });

   def successMsg = ("Android integration tests succeeded for " +
                     REVISION_DESCRIPTION);
   def failureMsg = ("Android integration tests failed for " +
                     REVISION_DESCRIPTION +
                     "(search for 'ANDROID' in ${env.BUILD_URL}consoleFull)");

   withTimeout('1h') {
      withEnv(["URL=${E2E_URL}"]) {
         withSecrets() {  // we need secrets to talk to slack!
            try {
               // TODO(benkraft): This should really set the cookie
               // GOOGAPPUID=999 to make sure it gets the data from the new
               // version if we are still in a traffic split.
               sh("jenkins-jobs/run_android_db_generator.sh");
               sh("echo ${exec.shellEscape(successMsg)} | " +
                  "${exec.shellEscapeList(slackArgs)} --severity=info");
            } catch (e) {
               sh("echo ${exec.shellEscape(failureMsg)} | " +
                  "${exec.shellEscapeList(slackArgs)} --severity=error");
               sh("echo ${exec.shellEscape(failureMsg)} | " +
                  "${exec.shellEscapeList(slackArgsWithoutChannel)} " +
                  "--slack='#mobile-1s-and-0s' --severity=error");
               throw e;
            }
         }
      }
   }
}

def _determineTests() {
   // Figure out how to split up the tests.  We run 4 jobs on
   // each of 4 workers.  We put this in the location where the
   // 'copy to slave' plugin expects it (e2e-test-<worker> will
   // copy the file from here to each worker machine).
   def NUM_SPLITS = NUM_WORKER_MACHINES * JOBS_PER_WORKER;

   sh("tools/runsmoketests.py -n --just-split -j${NUM_SPLITS}" +
      "> genfiles/test-splits.txt");
   dir("genfiles") {
      def allSplits = readFile("test-splits.txt").split("\n\n");
      for (def i = 0; i < allSplits.size(); i++) {
         writeFile(file: "test-splits.${i}.txt",
                   text: allSplits[i]);
      }
      stash(includes: "test-splits.*.txt", name: "splits");
      // Now tell the test workers to get to work!
      HAVE_STASHED_TESTS = true;
   }
}

def _runOneTest(splitId) {
   def args = ["xvfb-run", "-a", "tools/runsmoketests.py",
               "--url=${E2E_URL}",
               "--pickle", "--pickle-file=../test-results.${splitId}.pickle",
               "--timing-db=genfiles/test-info.db",
               "--xml-dir=genfiles/test-reports",
               "--quiet", "--jobs=1", "--retries=3",
               "--driver=chrome", "--backup-driver=sauce",
               "-"];
   if (params.FAILFAST) {
      args += ["--failfast"];
   }
   if (params.SET_SPLIT_COOKIE) {
      args += ["--set-split-cookie"];
   }
   if (params.EXPECTED_VERSION) {
      args += ["--expected-version=${params.EXPECTED_VERSION}"];
   }


   try {
      sh(exec.shellEscapeList(args) + " < ../test-splits.${splitId}.txt");
   } catch (e) {
      // end-to-end failures are not blocking currently, so if
      // tests fail set the status to UNSTABLE, not FAILURE.
      currentBuild.result = "UNSTABLE";
   }
}

def doTestOnWorker(workerNum) {
   onWorker('ka-test-ec2', '1h') {     // timeout
      // We can sync webapp right away, before we know what tests we'll be
      // running.
      _setupWebapp();
      // We also need to sync mobile, so we can run the mobile integration test
      // (if we are assigned to do so).
      // TODO(benkraft): Only run this if we get it from the splits?
      kaGit.safeSyncToOrigin("git@github.com:Khan/mobile", "master");

      // We continue to hold the worker while waiting, so we can make sure to
      // get the same one, and start right away, once ready.
      waitUntil({ HAVE_STASHED_TESTS });

      // Out with the old, in with the new!
      sh("rm -f test-results.*.pickle");
      unstash("splits");
      def firstSplit = workerNum * JOBS_PER_WORKER;
      def lastSplit = firstSplit + JOBS_PER_WORKER - 1;

      def parallelTests = ["failFast": params.FAILFAST];
      for (def j = firstSplit; j <= lastSplit; j++) {
         // That restriction in `parallel` again.
         def split = j;
         parallelTests["job-$split"] = { _runOneTest(split); };
      }

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
               name: "results ${workerNum}");
      }
   }
}

def determineSplitsAndRunTests() {
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
      "failFast": params.FAILFAST,
      "determine-splits": {
         withTimeout('10m') {
            _setupWebapp();
            // Tell the android/graphql tests they can go.
            HAVE_RUN_SETUP = true;
            dir("webapp") {
               _determineTests();
            }
         }
      },
      // This runs on the "parent" worker, and wait for setup before doing
      // anything; we spawn them at the same time as everything else just to
      // keep things simple and avoid more nesting.
      "android-integration-test": { runAndroidTests(
         slackArgs, slackArgsWithoutChannel); },
   ];
   for (def i = 0; i < NUM_WORKER_MACHINES; i++) {
      // A restriction in `parallel`: need to redefine the index var here.
      def workerNum = i;

      jobs["e2e-test-${workerNum}"] = {
         doTestOnWorker(workerNum);
      };
   }

   parallel(jobs);
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

      def numPickleFileErrors = 0;
      for (def i = 0; i < NUM_WORKER_MACHINES * JOBS_PER_WORKER; i++) {
         if (!fileExists("test-results.${i}.pickle")) {
            numPickleFileErrors++;
         }
      }
      // Send a special message if all workers fail, because that's not good
      // (and the normal script can't handle it).
      if (numPickleFileErrors == NUM_WORKER_MACHINES) {
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
            summarize_args = [
               "tools/test_pickle_util.py", "summarize-to-slack",
               "genfiles/test-results.pickle", params.SLACK_CHANNEL,
               "--jenkins-build-url", env.BUILD_URL,
               "--deployer", params.DEPLOYER_USERNAME,
               // The label goes at the top of the message; we include
               // both the URL and the REVISION_DESCRIPTION.
               "--label", "${E2E_URL}: ${REVISION_DESCRIPTION}",
               "--expected-tests-file", "genfiles/test-splits.txt",
               "--cc-always", "#qa-log"];
            if (params.SLACK_THREAD) {
               summarize_args += ["--slack-thread", params.SLACK_THREAD];
            }
            exec(summarize_args);
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


// We run the test-splitter, reporter, and graphql/android tests on a worker --
// with all the tests running nowadays running it on the master can overwhelm
// the master, and we have plenty of workers.
onWorker('ka-test-ec2', '5h') {     // timeout
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
         stage("Running tests") {
            determineSplitsAndRunTests();
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
