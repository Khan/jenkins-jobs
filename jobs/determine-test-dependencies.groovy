// The pipeline job for figuring out test dependencies.
//
// We have a tool that figures out, for every file in our source tree,
// what tests (python and/or javascript) depend on that file (where
// "depend on" means "opens for reading").  We can use this mapping
// to decide what tests to run for a given commit.
//
// Creating this mapping is slow, though, so we have a jenkins job
// to do it.  This jenkins job, in fact.

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
   "GIT_REVISION",
   "A commit-ish to check out to run the tests at.",
   "master"

).addStringParam(
   "SLACK_CHANNEL",
   "The slack channel to send our status info.",
   "#infrastructure"

).addStringParam(
   "NUM_WORKER_MACHINES",
   """How many worker machines to use.  This will function best
when it's equal to the <code>Instance Cap</code> value for
the <code>ka-test worker</code> ec2 setup at
<a href=\"/configure\">the Jenkins configure page</a>.  You'll need
to click on 'advanced' to see the instance cap.""",
   "4"

// TODO(csilvers): re-enable once I figure out why setCronSchedule causes
// our process to hang.
//).setCronSchedule(
//   'H 3 * * *'

).apply();


NUM_WORKER_MACHINES = params.NUM_WORKER_MACHINES.toInteger();


def _setupWebapp() {
   kaGit.safeSyncToOrigin("git@github.com:Khan/webapp",
                          params.GIT_REVISION);
   dir("webapp") {
      sh("make deps");
   }
}


// This should be called from workspace-root.
def _alert(def msg, def isError=true, def channel=params.SLACK_CHANNEL) {
   withSecrets() {     // you need secrets to talk to slack
      sh("echo '${msg}' | " +
         "jenkins-tools/alertlib/alert.py --slack='${channel}' " +
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
            def NUM_SPLITS = NUM_WORKER_MACHINES;

            _setupWebapp();
            dir("webapp") {
               sh("tools/runtests.py -n --just-split -j${NUM_SPLITS} " +
                  // We have to specify these because they are @manual_only.
                  ". testutil.js_test testutil.lint_test " +
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
   def jobs = [:];
   for (def i = 0; i < NUM_WORKER_MACHINES; i++) {
      // A restriction in `parallel`: need to redefine the index var here.
      def workerNum = i;

      jobs["test-deps-${workerNum}"] = {
         onTestWorker('3h') {     // timeout
            unstash("splits");
            // Hopefully, the sync-webapp-i job above synced us to
            // the right commit, but we can't depend on that, so we
            // double-check.
            _setupWebapp();

            sh("cd webapp; ../jenkins-tools/timeout_output.py 45m " +
               "tools/determine_tests_for.py " +
               "-o ../tests_for.${workerNum}.json " +
               "- < ../test-splits.${workerNum}.txt");
            stash(includes: "tests_for.*.json",
                  name: "results ${workerNum}");
         }
      };
   }

   parallel(jobs);
}

def publishResults() {
   onMaster('5m') {         // timeout
      // Once we get here, we're done using the worker machines,
      // so let our cron overseer know.
      sh("rm /tmp/make_check.run");

      def numWorkerErrors = 0;

      sh("rm -f tests_for.*.json");
      for (def i = 0; i < NUM_WORKER_MACHINES; i++) {
         try {
            unstash("results ${i}");
         } catch (e) {
            // I guess that worker had trouble even producing results.
            numWorkerErrors++;
         }
      }

      if (numWorkerErrors) {
         def msg = ("${numWorkerErrors} test workers did not even " +
                    "finish (could be due to timeouts or framework " +
                    "errors; check the logs to see exactly why), so not " +
                    "updating test-dependency information");
         _alert(msg, isError=true);
         // Let notify() know not to send any messages to slack,
         // because we just did it above.
         env.SENT_TO_SLACK = '1';
         return;
      }

      dir("webapp") {
         // Get ready to overwrite a file in our repo.
         kaGit.safePull(".");

         // Combine all the dicts into one dict.
         sh("tools/determine_tests_for.py --merge " +
            "-o tools/tests_for.json " +
            "../tests_for.*.json");

         // Check it in!
         withEnv(["FORCE_COMMIT=1"]) {   // commit without a test plan
            kaGit.safeCommitAndPush(
               ".", ["-m", "Automatic update of tests_for.json"]);
         }
      }
   }
}


notify([slack: [channel: params.SLACK_CHANNEL,
                sender: 'Testing Turtle',
                emoji: ':turtle:',
                when: ['SUCCESS', 'FAILURE', 'UNSTABLE']]]) {
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
      stage("Publishing results") {
         publishResults();
      }
   }
}
