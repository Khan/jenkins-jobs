// The pipeline job for e2e tests.

@Library("kautils")
// Classes we use, under jenkins-tools/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-tools/vars/.  This is just for documentation.
//import vars.kaGit
//import vars.notify
//import vars.onMaster
//import vars.onTestWorker
//import vars.withSecrets


new Setup(steps

// We run on the test-workers a few different times during this job,
// and we want to make sure no other job sneaks in between those times
// and steals our test-workers from us.  So we acquire this lock.  It
// depends on everyone else who uses the test-workers using this lock too.
).blockBuilds(['builds-using-test-workers']

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
   "4"

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

).apply();

currentBuild.displayName = ("${currentBuild.displayName} " +
                            "(${params.GIT_REVISION})");


// This is the e2e command we run on the workers.  $1 is the
// job-number for this current worker (and is used to decide what
// tests this worker is responsible for running.)
E2E_CMD = """\
   cd webapp; timeout -k 5m 5h xvfb-run -a tools/rune2etests.py
   --pickle --pickle-file=../test-results.\$1.pickle
   --timing-db=genfiles/test-info.db --xml-dir=genfiles/test-reports
   --quiet --jobs=1 --retries 3 ${params.FAILFAST ? '--failfast ' : ''}
   --url=${exec.shellEscape(params.URL)} --driver=chrome --backup-driver=sauce
   - < ../test-splits.\$1.txt
""".replaceAll("\n", "")

// We set these to real values first thing below; but we do it within
// the notify() so if there's an error setting them we notify on slack.
NUM_WORKER_MACHINES = null;
JOBS_PER_WORKER = null;
GIT_SHA1 = null;

def initializeGlobals() {
   NUM_WORKER_MACHINES = params.NUM_WORKER_MACHINES.toInteger();
   JOBS_PER_WORKER = params.JOBS_PER_WORKER.toInteger();
   // We want to make sure all nodes below work at the same sha1,
   // so we resolve our input commit to a sha1 right away.
   GIT_SHA1 = kaGit.resolveCommitish("git@github.com:Khan/webapp",
                                     params.GIT_REVISION);
}


def _setupWebapp() {
   kaGit.safeSyncTo("git@github.com:Khan/webapp", GIT_SHA1);
   dir("webapp") {
      sh("make python_deps");
   }
}


// This should be called from workspace-root.
def _alert(def msg, def isError=true, def channel=params.SLACK_CHANNEL) {
   withSecrets() {     // you need secrets to talk to slack
      sh("echo ${exec.shellEscape(msg)} | " +
         "jenkins-tools/alertlib/alert.py " +
         "--slack=${exec.shellEscape(channel)} " +
         "--severity=${isError ? 'error' : 'info'} " +
         "--chat-sender='Testing Turtle' --icon-emoji=:turtle:");
   }
}


def determineSplits() {
   // The main goal of this stage is to determine the splits, which
   // happens on master.  But while we're waiting for that, we might
   // as well get all the test-workers synced to the right commit!
   // That will save time later.
   def jobs = [
      "determine-splits": {
         onMaster('10m') {        // timeout
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


def runTests() {
   def jobs = [
      // This is a kwarg that tells parallel() what to do when a job fails.
      "failFast": params.FAILFAST == "true",

      "mobile-integration-test": {
         onMaster('1h') {       // timeout
            withEnv(["URL=${params.URL}"]) {
               withSecrets() {  // we need secrets to talk to slack!
                  try {
                     sh("jenkins-tools/run_android_db_generator.sh");
                     _alert("Mobile integration tests succeeded",
                            isError=false);
                  } catch (e) {
                     def msg = ("Mobile integration tests failed " +
                                "(search for 'ANDROID' in " +
                                "${env.BUILD_URL}consoleFull)");
                     _alert(msg, isError=true);
                     _alert(msg, isError=true, channel="#mobile-1s-and-0s");
                     throw e;
                  }
               }
            }
         }
      },
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

            try {
               // This is apparently needed to avoid hanging with
               // the chrome driver.  See
               // https://github.com/SeleniumHQ/docker-selenium/issues/87
               withEnv(["DBUS_SESSION_BUS_ADDRESS=/dev/null"]) {
                  withSecrets() {   // we need secrets to talk to saucelabs
                     // The trailing `tools/rune2etests.py` is just to
                     // set the executable name ($0) reported by `sh`.
                     exec(["jenkins-tools/in_parallel.py"] +
                          (firstSplit..lastSplit) +
                          ["--", "sh", "-c", E2E_CMD, "tools/rune2etests.py"]);
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
   onMaster('5m') {         // timeout
      // Once we get here, we're done using the worker machines,
      // so let our cron overseer know.
      sh("rm /tmp/make_check.run");

      def numPickleFileErrors = 0;

      sh("rm -f test-results.*.pickle");
      for (def i = 0; i < NUM_WORKER_MACHINES; i++) {
         try {
            unstash("results ${i}");
         } catch (e) {
            // I guess that worker had trouble even producing results.
            numPickleFileErrors++;
         }
      }

      if (numPickleFileErrors) {
         def msg = ("${numPickleFileErrors} test workers did not even " +
                    "finish (could be due to timeouts or framework " +
                    "errors; check ${env.BUILD_URL}consoleFull " +
                    "to see exactly why)");
         _alert(msg, isError=true);
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


notify([slack: [channel: params.SLACK_CHANNEL,
                sender: 'Testing Turtle',
                emoji: ':turtle:',
                when: ['FAILURE', 'UNSTABLE']]]) {
   initializeGlobals();

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
