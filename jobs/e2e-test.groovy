// The pipeline job for e2e tests.

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.exec
//import vars.kaGit
//import vars.notify
//import vars.withTimeout
//import vars.onTestWorker
//import vars.withSecrets


new Setup(steps

).allowConcurrentBuilds(
   // We serialize via the using-test-workers lock

).addStringParam(
   "URL",
   "The url-base to run these tests against.",
   "https://www.khanacademy.org"

).addStringParam(
   "SLACK_CHANNEL",
   "The slack channel to which to send failure alerts.",
   "#1s-and-0s-deploys"

).addStringParam(
   "NUM_WORKER_MACHINES",
   """How many worker machines to use.  This will function best
when it's equal to the <code>Instance Cap</code> value for
the <code>ka-test worker</code> ec2 setup at
<a href=\"/configure\">the Jenkins configure page</a>.  You'll need
to click on 'advanced' to see the instance cap.""",
   onTestWorker.defaultNumWorkerMachines().toString()

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
for num in `seq 1 16`; do echo -- \$num; time tools/rune2etests.py -j\$num >/dev/null 2>&1; done
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

).apply();

currentBuild.displayName = ("${currentBuild.displayName} " +
                            "(${params.GIT_REVISION})");


// We set these to real values first thing below; but we do it within
// the notify() so if there's an error setting them we notify on slack.
NUM_WORKER_MACHINES = null;
JOBS_PER_WORKER = null;
GIT_SHA1 = null;
IS_ONE_GIT_SHA = null;

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
   kaGit.safeSyncTo("git@github.com:Khan/webapp", GIT_SHA1);
   dir("webapp") {
      sh("make clean_pyc");
      sh("make python_deps");
   }
}


def determineSplits() {
   // The main goal of this stage is to determine the splits, which
   // happens on master.  But while we're waiting for that, we might
   // as well get all the test-workers synced to the right commit!
   // That will save time later.
   def jobs = [
      "determine-splits": {
         withTimeout('10m') {
            // Figure out how to split up the tests.  We run 4 jobs on
            // each of 4 workers.  We put this in the location where the
            // 'copy to slave' plugin expects it (e2e-test-<worker> will
            // copy the file from here to each worker machine).
            def NUM_SPLITS = NUM_WORKER_MACHINES * JOBS_PER_WORKER;

            _setupWebapp();
            dir("webapp") {
               sh("tools/rune2etests.py -n --just-split -j${NUM_SPLITS}" +
                  "> genfiles/test-splits.txt");
               dir("genfiles") {
                  def allSplits = readFile("test-splits.txt").split("\n\n");
                  for (def i = 0; i < allSplits.size(); i++) {
                     writeFile(file: "test-splits.${i}.txt",
                               text: allSplits[i]);
                  }
                  stash(includes: "test-splits.*.txt", name: "splits");
               }
            }

            // Touch this file right before we start using the jenkins
            // make-check workers.  We have a cron job running on jenkins
            // that will keep track of the make-check workers and
            // complain if a job that uses the make-check workers is
            // running, but all the workers aren't up.  (We delete this
            // file in a try/finally.)
            sh("touch /tmp/make_check.run");
         }
      }
   ];

   for (def i = 0; i < NUM_WORKER_MACHINES; i++) {
     // A restriction in `parallel`: need to redefine the index var here.
      def workerNum = i;
      jobs["sync-webapp-${workerNum}"] = {
         onTestWorker('10m') {      // timeout
            _setupWebapp();
         }
      }
   }

   parallel(jobs);
}


def _runOneTest(splitId) {
   def args = ["xvfb-run", "-a", "tools/rune2etests.py",
               "--url=${params.URL}",
               "--pickle", "--pickle-file=../test-results.${splitId}.pickle",
               "--timing-db=genfiles/test-info.db",
               "--xml-dir=genfiles/test-reports",
               "--quiet", "--jobs=1", "--retries=3",
               "--driver=chrome", "--backup-driver=sauce",
               "-"];
   if (params.FAILFAST) {
      args += ["--failfast"];
   }

   try {
      sh(exec.shellEscapeList(args) + " < ../test-splits.${splitId}.txt");
   } catch (e) {
      // end-to-end failures are not blocking currently, so if
      // tests fail set the status to UNSTABLE, not FAILURE.
      currentBuild.result = "UNSTABLE";
   }
}

def runAndroidTests(slackArgs, slackArgsWithoutChannel) {
   def successMsg = "Android integration tests succeeded";
   def failureMsg = ("Android integration tests failed " +
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
               sh("echo ${exec.shellEscape(failureMsg)} | " +
                  "${exec.shellEscapeList(slackArgsWithoutChannel)} " +
                  "--slack='#mobile-1s-and-0s' --severity=error");
               throw e;
            }
         }
      }
   }
}


// Verify that candidate schema supports all active queries.
def runGraphlSchemaTest(slackArgs, slackArgsWithoutChannel) {
   def successMsg = "GraphQL schema integration test succeeded";
   def failureMsg = "GraphQL schema integration test failed. This means " +
      "the GraphQL schema is not valid or does not support all active " +
      "queries (most likely the schema breaks a mobile native query).";
   def cmd = "curl -s ${exec.shellEscape(params.URL)}'/api/internal/" +
      "graphql_whitelist/validate?format=pretty' | tee /dev/stderr | " +
      "grep -q '.passed.: *true'";
   withSecrets() {  // we need secrets to talk to slack!
      try {
         sh(cmd)
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


def runTests() {
   def slackArgsWithoutChannel = ["jenkins-jobs/alertlib/alert.py",
                                  "--chat-sender=Testing Turtle",
                                  "--icon-emoji=:turtle:"];
   def slackArgs = (slackArgsWithoutChannel +
      ["--slack=${params.SLACK_CHANNEL}"]);
   def jobs = [
      // This is a kwarg that tells parallel() what to do when a job fails.
      "failFast": params.FAILFAST,
      "android-integration-test": { runAndroidTests(
         slackArgs, slackArgsWithoutChannel); },
      "graphql-integration-test": { runGraphlSchemaTest(
         slackArgs, slackArgsWithoutChannel); },
   ];
   for (def i = 0; i < NUM_WORKER_MACHINES; i++) {
      // A restriction in `parallel`: need to redefine the index var here.
      def workerNum = i;

      jobs["e2e-test-${workerNum}"] = {
         onTestWorker('1h') {     // timeout
            // Out with the old, in with the new!
            sh("rm -f test-results.*.pickle");
            unstash("splits");
            def firstSplit = workerNum * JOBS_PER_WORKER;
            def lastSplit = firstSplit + JOBS_PER_WORKER - 1;

            // Hopefully, the sync-webapp-i job above synced us to
            // the right commit, but we can't depend on that, so we
            // double-check.
            _setupWebapp();

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
               // rune2etests.py should normally produce these files
               // even when it returns a failure rc (due to some test
               // or other failing).
               stash(includes: "test-results.*.pickle",
                     name: "results ${workerNum}");
            }
         }
      };
   }

   parallel(jobs);
}


def analyzeResults() {
   withTimeout('5m') {
      // Once we get here, we're done using the worker machines,
      // so let our cron overseer know.
      sh("rm -f /tmp/make_check.run");

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
      if (numPickleFileErrors) {
         def msg = ("${numPickleFileErrors} test workers did not " +
                    "even finish (could be due to timeouts or framework " +
                    "errors; search for `Failed in branch` at " +
                    "${env.BUILD_URL}consoleFull to see exactly why)");
         // One could imagine it's useful to go on in this case, and
         // analyze the pickle-file we *did* get back.  But in my
         // experience it's too confusing: people think that the
         // results we emit are the full results, even though this
         // error indicates some results could not be processed.
         notify.fail(msg, "UNSTABLE");
      }

      withSecrets() {     // we need secrets to talk to slack!
         dir("webapp") {
            sh("tools/test_pickle_util.py merge " +
               "../test-results.*.pickle " +
               "genfiles/test-results.pickle");
            sh("tools/test_pickle_util.py update-timing-db " +
               "genfiles/test-results.pickle genfiles/test-info.db");
            exec(["tools/test_pickle_util.py", "summarize-to-slack",
                  "genfiles/test-results.pickle", params.SLACK_CHANNEL,
                  "--jenkins-build-url", env.BUILD_URL,
                  "--deployer", params.DEPLOYER_USERNAME,
                  // The commit here is just used for a human-readable
                  // slack message, so we use the input commit, not the sha1.
                  "--commit", params.GIT_REVISION]);
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


def notify_args = [
   slack: [channel: params.SLACK_CHANNEL,
           sender: 'Testing Turtle',
           emoji: ':turtle:',
           when: ['FAILURE', 'UNSTABLE', 'ABORTED']],
   aggregator: [initiative: 'infrastructure',
                when: ['SUCCESS', 'BACK TO NORMAL',
                       'FAILURE', 'ABORTED', 'UNSTABLE']],
   timeout: "2h"];


if (params.NOTIFY_BUILDMASTER) {
   notify_args.buildmaster = [sha1sCallback: { GIT_SHA1 },
                              isOneGitShaCallback: { IS_ONE_GIT_SHA },
                              what: 'e2e-test'];
}


notify(notify_args) {
   initializeGlobals();

   // We run on the test-workers a few different times during this
   // job, and we want to make sure no other job sneaks in between
   // those times and steals our test-workers from us.  So we acquire
   // this lock for the entire job.  It depends on everyone else who
   // uses the test-workers using this lock too.
   lock(label: 'using-test-workers', quantity: 1) {
      stage("Determining splits") {
         determineSplits();
      }

      try {
         stage("Running tests") {
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
