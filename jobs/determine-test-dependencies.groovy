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
//import vars.exec
//import vars.kaGit
//import vars.notify
//import vars.onMaster
//import vars.onTestWorker
//import vars.withSecrets


new Setup(steps

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

).addCronSchedule(
   'H 3 * * *'

).apply();


// We set these to real values first thing below; but we do it within
// the notify() so if there's an error setting them we notify on slack.
NUM_WORKER_MACHINES = null;
GIT_SHA1 = null;

def initializeGlobals() {
   NUM_WORKER_MACHINES = params.NUM_WORKER_MACHINES.toInteger();
   // We want to make sure all nodes below work at the same sha1,
   // so we resolve our input commit to a sha1 right away.
   GIT_SHA1 = kaGit.resolveCommitish("git@github.com:Khan/webapp",
                                     params.GIT_REVISION);
}


def _setupWebapp() {
   kaGit.safeSyncTo("git@github.com:Khan/webapp", GIT_SHA1);
   dir("webapp") {
      sh("make deps");
   }
}


// This should be called from workspace-root.
def _alert(def msg) {
   withSecrets() {     // you need secrets to talk to slack
      sh("echo ${exec.shellEscape(msg)} | " +
         "jenkins-tools/alertlib/alert.py " +
         "--slack=${exec.shellEscape(channel)} " +
         "--severity='error' " +
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
                  // We have to specify js_test because it is @manual_only.
                  // Note we don't run testutil.lint_test, since we calculate
                  // what files to run linters on in a totally different way.
                  ". testutil.js_test " +
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
            // Out with the old, in with the new!
            sh("rm -f tests_for.*.json");
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
   onMaster('10m') {         // timeout
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
         def msg = ("*determine-test-dependencies:* " +
                    "${numWorkerErrors} test workers did not even " +
                    "finish (could be due to timeouts or framework " +
                    "errors; check ${env.BUILD_URL}consoleFull " +
                    "to see exactly why), so not updating test-dependency " +
                    "information");
         _alert(msg);
         // Let notify() know not to send any messages to slack,
         // because we just did it above.
         env.SENT_TO_SLACK = '1';
         return;
      }

      // Get ready to overwrite a file in our repo.
      kaGit.safePull("webapp");
      dir("webapp") {
         // Combine all the dicts into one dict.
         sh("tools/determine_tests_for.py --merge " +
            "-o tools/tests_for.json " +
            "../tests_for.*.json");
         sh("git add tools/tests_for.json");
      }
      // Check it in!
      withEnv(["FORCE_COMMIT=1"]) {   // commit without a test plan
         kaGit.safeCommitAndPush(
            "webapp", ["-m", "Automated update of tests_for.json"]);
      }
   }
}


notify([slack: [channel: params.SLACK_CHANNEL,
                sender: 'Testing Turtle',
                emoji: ':turtle:',
                when: ['SUCCESS', 'FAILURE', 'UNSTABLE', 'ABORTED']]]) {
   initializeGlobals();

   // We run on the test-workers a few different times during this
   // job, and we want to make sure no other job sneaks in between
   // those times and steals our test-workers from us.  So we acquire
   // this lock for the entire job.  It depends on everyone else who
   // uses the test-workers using this lock too.
   lock('using-test-workers') {
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
}
