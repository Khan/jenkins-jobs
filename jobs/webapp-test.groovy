// The pipeline job for webapp tests.
//
// webapp tests are the tests run in the webapp repo, including python
// tests, javascript tests, and linters (which aren't technically tests).
//
// This job can either run all tests, or a subset thereof, depending on
// how parameters are specified.

@Library("kautils")
// Classes we use, under jenkins-tools/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-tools/vars/.  This is just for documentation.
//import vars.clean
//import vars.exec
//import vars.kaGit
//import vars.notify
//import vars.withTimeout
//import vars.onTestWorker
//import vars.singleton
//import vars.withSecrets


new Setup(steps

).allowConcurrentBuilds(
   // We serialize via the using-test-workers lock

).addStringParam(
   "GIT_REVISION",
   """The git commit-hash to run tests at, or a symbolic name referring
to such a commit-hash.""",
   "master"

).addChoiceParam(
   "TEST_TYPE",
   """\
<ul>
  <li> <b>all</b>: run all tests</li>
  <li> <b>relevant</b>: run only those tests that are affected by
         files that have changed between master and GIT_REVISION.</li>
</ul>
""",
   ["all", "relevant"]

).addChoiceParam(
   "MAX_SIZE",
   """The largest size tests to run, as per tools/runtests.py.""",
   ["medium", "large", "huge", "small", "tiny"]

).addBooleanParam(
   "FAILFAST",
   "If set, stop running tests after the first failure.",
   false

).addStringParam(
   "SLACK_CHANNEL",
   "The slack channel to which to send failure alerts.",
   "#1s-and-0s"

).addBooleanParam(
   "FORCE",
   """If set, run the tests even if the database says that the tests
have already passed at this GIT_REVISION.""",
   false

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
   """How many worker machines to use.  This will function best
when it's equal to the <code>Instance Cap</code> value for
the <code>ka-test worker</code> ec2 setup at
<a href=\"/configure\">the Jenkins configure page</a>.  You'll need
to click on 'advanced' to see the instance cap.""",
   "6"

).addStringParam(
   "DEPLOYER_USERNAME",
   """Who asked to run this job, used to ping on slack.
Typically not set manually, but rather by other jobs that call this one.""",
   ""

).apply();

currentBuild.displayName = ("${currentBuild.displayName} " +
                            "(${params.GIT_REVISION})");


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
      clean(params.CLEAN);
      sh("make deps");
   }
   // Webapp's lint tests also look for the linter in ../devtools/khan-linter
   // so make sure we sync that to the latest version.
   // TODO(csilvers): make safeSyncToOrigin clone into `.`, not workspace-root?
   kaGit.safeSyncToOrigin("git@github.com:Khan/khan-linter", "master");
   sh("rm -rf devtools/khan-linter");
   sh("cp -r khan-linter devtools/");
}


// Figures out what tests to run based on TEST_TYPE and writes them to
// a file in workspace-root.  Should be called in the webapp dir.
def _determineTests() {
   def tests;

   // This command expands directory arguments, and also filters out
   // tests that are not the right size.  Finally, it figures out splits.
   def runtestsCmd = ("tools/runtests.py " +
                      "--max-size=${exec.shellEscape(params.MAX_SIZE)} " +
                      "--jobs=${NUM_WORKER_MACHINES} " +
                      "--timing-db=genfiles/test-info.db " +
                      "--dry-run --just-split");

   if (params.TEST_TYPE == "all") {
      // We have to specify these explicitly because they are @manual_only.
      sh("${runtestsCmd} . testutil.js_test testutil.lint_test " +
         " > genfiles/test_splits.txt");
   } else if (params.TEST_TYPE == "relevant") {
      // TODO(csilvers): Instead of `origin/master`, what we really want
      // is "the last time tests passed on a commit that is in master."
      // We could use redis for this.
      sh("tools/tests_for.py -i origin/master " +
         " | ${runtestsCmd} -" +
         " > genfiles/test_splits.txt");
   } else {
      error("Unexpected TEST_TYPE '${params.TEST_TYPE}'");
   }

   dir("genfiles") {
      // Make sure to clean out any stray old splits files.  (This probably
      // only matters if we changed the number of workers, but it never hurts.)
      sh("rm -f test_splits.*.txt");

      def allSplits = readFile("test_splits.txt").split("\n\n");
      if (allSplits.size() != NUM_WORKER_MACHINES) {
         error("Got ${allSplits.size()} splits, " +
               "expected ${NUM_WORKER_MACHINES}.");
      }
      for (def i = 0; i < allSplits.size(); i++) {
         writeFile(file: "test_splits.${i}.txt",
                   text: allSplits[i]);
      }
      stash(includes: "test_splits.*.txt", name: "splits");
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
            _setupWebapp();
            dir("webapp") {
               _determineTests();
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
      "failFast": params.FAILFAST,
   ];
   for (def i = 0; i < NUM_WORKER_MACHINES; i++) {
      // A restriction in `parallel`: need to redefine the index var here.
      def workerNum = i;

      jobs["test-${workerNum}"] = {
         onTestWorker('1h') {     // timeout
            // Out with the old, in with the new!
            sh("rm -f test-results.*.pickle");
            unstash("splits");

            // Hopefully, the sync-webapp-i job above synced us to
            // the right commit, but we can't depend on that, so we
            // double-check.
            _setupWebapp();

            dir("webapp") {
               clean(params.CLEAN);
            }

            try {
               sh("cd webapp; ../jenkins-tools/timeout_output.py 45m " +
                  "tools/runtests.py " +
                  "--pickle " +
                  "--pickle-file=../test-results.${workerNum}.pickle " +
                  "--quiet --jobs=1 " +
                  "--max-size=${exec.shellEscape(params.MAX_SIZE)} " +
                  (params.FAILFAST ? "--failfast " : "") +
                  "- < ../test_splits.${workerNum}.txt");
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
      sh("rm /tmp/make_check.run");

      sh("rm -f test-results.*.pickle");
      for (def i = 0; i < NUM_WORKER_MACHINES; i++) {
         try {
            unstash("results ${i}");
         } catch (e) {
            // We'll mark the actual error next.
         }
      }

      def numPickleFileErrors = 0;
      for (def i = 0; i < NUM_WORKER_MACHINES; i++) {
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
         notify.fail(msg);
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
                when: ['FAILURE', 'UNSTABLE', 'ABORTED']],
        aggregator: [initiative: 'infrastructure',
                     when: ['SUCCESS', 'BACK TO NORMAL',
                            'FAILURE', 'ABORTED', 'UNSTABLE']],
        timeout: "5h"]) {
   initializeGlobals();

   def key = ["rGW${GIT_SHA1}", params.TEST_TYPE, params.MAX_SIZE];
   singleton(params.FORCE ? null : key.join(":")) {
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
            // We want to analyze results even if -- especially if --
            // there were failures; hence we're in the `finally`.
            stage("Analyzing results") {
               analyzeResults();
            }
         }
      }
   }
}
