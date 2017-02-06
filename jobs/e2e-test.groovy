// The pipeline job for e2e tests.

@Library("kautils")
// Classes we use, under jenkins-tools/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-tools/vars/.  This is just for documentation.
//import vars.kaGit
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
   "@AutomatedRun"

).apply();


def NUM_WORKER_MACHINES = params.NUM_WORKER_MACHINES.toInteger();
def JOBS_PER_WORKER = params.JOBS_PER_WORKER.toInteger();


def _setupWebapp() {
   if (!kaGit.isAtCommit("webapp", params.GIT_REVISION)) {
      kaGit.safeSyncToOrigin("git@github.com:Khan/webapp",
                             params.GIT_REVISION);
      dir("webapp") {
         sh("make python_deps");
      }
   }
}


// TODO(csilvers): add a good timeout
// TODO(csilvers): set the build name to
//     #${BUILD_NUMBER} (${ENV, var="GIT_REVISION"})
// TODO(csilvers): do something reasonable with slack messaging
//     (add an `alert` build step to call out to alert.py)

stage("Determining splits") {
   // The main goal of this stage is to determine the splits, which
   // happens on master.  But while we're waiting for that, we might
   // as well get all the test-workers synced to the right commit!
   // That will save time later.
   def jobs = [
      "determine-splits": {
         onMaster('10m') {        // timeout
            // Figure out how to split up the tests.  We run 4 jobs on
            // each of 4 workers.  We put this in the location where the
            // 'copy to slave' plugin expects it (e2e-test-worker will
            // copy the file from here to each worker machine).
            def NUM_SPLITS = NUM_WORKER_MACHINES * JOBS_PER_WORKER;

            _setupWebapp();
            dir("webapp") {
               sh("tools/rune2etests.py -n --just-split -j${NUM_SPLITS}" +
                  "> genfiles/e2e_splits.txt");
               dir("genfiles") {
                  def allSplits = readFile("e2e_splits.txt").split("\n\n");
                  for (def i = 0; i < allSplits.size(); i++) {
                     writeFile(file: "e2e_splits.${i}.txt",
                               text: allSplits[i]);
                  }
                  stash(includes: "e2e_splits.*.txt", name: "splits");
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

try {
   stage("Running tests") {
      def jobs = [
         // This is a kwarg that tells parallel() what to do when a job fails.
         "failFast": params.FAILFAST == "true",

         "mobile-integration-test": {
            onMaster('1h') {       // timeout
               withEnv(["URL=${params.URL}",
                        "SLACK_CHANNEL=${params.SLACK_CHANNEL}"]) {
                  sh("jenkins-tools/android-e2e-tests.sh");
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
               sh("rm -f e2e-test-results.*.pickle");
               unstash("splits");
               def firstSplit = workerNum * JOBS_PER_WORKER;
               def lastSplit = firstSplit + JOBS_PER_WORKER - 1;

               // Hopefully, the sync-webapp-i job above synced us to
               // the right commit, but we can't depend on that, so we
               // double-check.
               _setupWebapp();

               try {
                  withEnv(["URL=${params.URL}",
                           "FAILFAST=${params.FAILFAST}"]) {
                     // We need secrets so we can talk to saucelabs.
                     withSecrets() {
                        sh("jenkins-tools/parallel-selenium-e2e-tests.sh " +
                           "`seq ${firstSplit} ${lastSplit}`");
                     }
                  }
               } finally {
                  // Now let the next stage see all the results.
                  // parallel-selenium-e2e-tests.sh should normally
                  // produce these files even when it returns a
                  // failure rc (due to some test or other failing).
                  stash(includes: "e2e-test-results.*.pickle",
                        name: "results ${workerNum}");
               }
            }
         };
      }

      parallel(jobs);
   }
} finally {
   // We want to analyze results even if -- especially if -- there
   // were failures; hence we're in the `finally`.
   stage("Analyzing results") {
      onMaster('5m') {         // timeout
        // Once we get here, we're done using the worker machines,
        // so let our cron overseer know.
        sh("rm /tmp/make_check.run");

        sh("rm -f e2e-test-results.*.pickle");
        for (def i = 0; i < NUM_WORKER_MACHINES; i++) {
           try {
              unstash("results ${i}");
           } catch (e) {
              // I guess that worker had trouble even producing results
              // TODO(csilvers): warn to slack about this
           }
        }

        dir("webapp") {
           sh("tools/test_pickle_util.py merge " +
              "../e2e-test-results.*.pickle " +
              "genfiles/e2e-test-results.pickle");
           sh("tools/test_pickle_util.py update-timing-db " +
              "genfiles/e2e-test-results.pickle " +
              "genfiles/e2e_test_info.db");
           sh("rm -rf genfiles/selenium_test_reports");
           sh("tools/test_pickle_util.py to-junit " +
              "genfiles/e2e-test-results.pickle " +
              "genfiles/selenium_test_reports");
        }

        junit("webapp/genfiles/selenium_test_reports/*.xml");
        // TODO(csilvers): rewrite analyze_make_output to read from pickle
        sh("jenkins-tools/analyze_make_output.py " +
           "--test_reports_dir=webapp/genfiles/selenium_test_reports " +
           "--jenkins_build_url='${env.BUILD_URL}' " +
           "--slack-channel='${params.SLACK_CHANNEL}'");
      }
   }
}
