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
to such a commit-hash.  Can also be a list of branches to deploy separated
by `+` ('br1+br2+br3').  In that case we will merge the branches together --
dying if there's a merge conflict -- and run tests on the resulting code.""",
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

).addBooleanParam(
   "FAILFAST",
   "If set, stop running tests after the first failure.",
   false

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

).addBooleanParam(
   "USE_TEST_SERVER",
   """If set, use the experimental test client/server architecture.
   TODO(csilvers): remove and decide one way or the other by 11/15/2020.""",
   true

).apply()


REVISION_DESCRIPTION = params.REVISION_DESCRIPTION ?: params.GIT_REVISION;

currentBuild.displayName = ("${currentBuild.displayName} " +
                            "(${REVISION_DESCRIPTION})");


// We set these to real values first thing below; but we do it within
// the notify() so if there's an error setting them we notify on slack.
NUM_WORKER_MACHINES = null;
// GIT_SHA1S are the sha1's for every revision specified in GIT_REVISION.
GIT_SHA1S = null;

// Set to true once we have stashed the list of tests for the workers to run.
HAVE_STASHED_TESTS = false;

// If we're running the large or huge tests, we need a bit more
// memory, because some of those tests seem to use a lot of memory.
// So we have a special worker type.
WORKER_TYPE = (params.MAX_SIZE in ["large", "huge"]
               ? 'big-test-worker' : 'ka-test-ec2');

WORKER_TIMEOUT = params.MAX_SIZE == 'huge' ? '4h' : '2h';


def getGitSha1s() {
   // resolveCommitish returns the sha of a commit.  If
   // resolveCommitish(webapp, X) == X, then X must be a sha, and we can
   // skip the rest of the function.
   // TODO(benkraft): Stop accepting anything other than a single sha, since
   // the buildmaster can do that part just fine.
   def revisionSha1 = null;
   try {
      revisionSha1 = kaGit.resolveCommitish("git@github.com:Khan/webapp",
                                            params.GIT_REVISION);
   } catch (e) {
      // Error resolving GIT_REVISION.  It's probably in `br1+br2` format
   }
   if (revisionSha1 && revisionSha1 == params.GIT_REVISION) {
      GIT_SHA1S = [params.GIT_REVISION];
      return GIT_SHA1S;
   }
   GIT_SHA1S = [];
   def allBranches = params.GIT_REVISION.split(/\+/);
   for (def i = 0; i < allBranches.size(); i++) {
      def sha1 = kaGit.resolveCommitish("git@github.com:Khan/webapp",
                                        allBranches[i].trim());
      GIT_SHA1S += [sha1];
   }
   return GIT_SHA1S;
}


def initializeGlobals() {
   NUM_WORKER_MACHINES = params.NUM_WORKER_MACHINES.toInteger();
   // We want to make sure all nodes below work at the same sha1,
   // so we resolve our input commit to a sha1 right away.
   GIT_SHA1S = getGitSha1s();
}


def _setupWebapp() {
   kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", GIT_SHA1S[0]);
   for (def i = 1; i < GIT_SHA1S.size(); i++) {
      kaGit.safeMergeFromBranch("webapp", "HEAD", GIT_SHA1S[i]);
   }

   dir("webapp") {
      clean(params.CLEAN);
      // TOOD(csilvers): we could get away with only running
      // python_deps, and just npm_deps if running js tests,
      // and go_deps if running go tests/lints.
      sh("make python_deps npm_deps");
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
   try {
      def tests;

      // Try to load the server's test-info db.
      try {
         onMaster('1m') {
            stash(includes: "test-info.db", name: "test-info.db before");
         }
         dir("genfiles") {
            unstash(name: "test-info.db before");
         }
      } catch (e) {
         // Proceed anyway -- perhaps the file doesn't exist yet.
         // Ah well; we'll just have worse splits.
         echo("Unable to restore test-db from server, expect poor splitting: ${e}");
      }
      // The `dir("genfiles")` creates a genfiles@tmp directory which
      // confuses lint_test.py (it tries to make a Lint_genfiles__tmp
      // test-class, which of course doesn't work on the workers).
      // Let's remove it.
      sh("rm -rf genfiles@tmp");

      // This command expands directory arguments, and also filters out
      // tests that are not the right size.  Finally, it figures out splits.
      // TODO(dhruv): share these flags with `doTestOnWorker` to ensure we're using
      // the same config in both places.
      def runtestsArgs = ["--max-size=${params.MAX_SIZE}",
                          "--timing-db=genfiles/test-info.db",
                          "--override-skip-by-default",
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

      def serverIP = "";
      if (params.USE_TEST_SERVER) {
         // This gets our 10.x.x.x IP address.
         serverIP = exec.outputOf(["ip", "route", "get", "10.1.1.1"]).split()[6];
      }

      sh("testing/runtests.py ${exec.shellEscapeList(runtestsArgs)} -n --just-split -j${NUM_WORKER_MACHINES} > genfiles/test_splits.txt");

      dir("genfiles") {
         // Make sure to clean out any stray old splits files.  (This probably
         // only matters if we changed the number of workers, but it never hurts.)
         sh("rm -f test_splits.*.txt");

         def allSplits = readFile("test_splits.txt").split("\n\n");
         if (allSplits.size() != NUM_WORKER_MACHINES) {
            echo("Got ${allSplits.size()} splits instead of " +
                 "${NUM_WORKER_MACHINES}: must not have a lot of tests to run!");
            // Make it so we only try to run tests on this many workers,
            // since we don't have work for the other workers to do!
            NUM_WORKER_MACHINES = allSplits.size();
         }
         for (def i = 0; i < allSplits.size(); i++) {
            // In test-server mode, we tell each worker about the server
            // url to connect to.  In "split" mode, we tell each worker
            // what test to run.
            writeFile(file: "test_splits.${i}.txt",
                      text: serverIP ? "http://${serverIP}:5001" : allSplits[i]);
         }
         stash(includes: "test_splits.*.txt", name: "splits");
         // Now set the number of test splits.  This unblocks the test-workers.
         // Note that in server mode, this unblocks the test-workers before
         // we start the server!  But that's ok; they know to wait for it.
         HAVE_STASHED_TESTS = true;
      }
      // Again, clean up after the `dir("genfiles")`.
      sh("rm -rf genfiles@tmp");

      if (params.USE_TEST_SERVER) {
         // Start the server.  It will auto-exit when it's done serving
         // all the tests.  "HOST=..." lets other machines connect to us.
         // TODO(csilvers): use genfiles/test_splits.txt so we don't have to
         //                 re-do that work again here.
         sh("env HOST=${serverIP} testing/runtests_server.py ${exec.shellEscapeList(runtestsArgs)}")
      }
   } catch (e) {
      // The workers are waiting on us to finish building deps; if we crash
      // they'll wait forever.  So say we're done, and set the number of
      // workers we need to zero, so they all exit quietly (our error will
      // suffice to fail the job).
      NUM_WORKER_MACHINES = 0;
      HAVE_STASHED_TESTS = true;
      throw e;
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

      // We continue to hold the worker while waiting, so we can make sure to
      // get the same one, and start right away, once ready.
      waitUntil({ HAVE_STASHED_TESTS });

      // It may be we turned out not to need this worker, because there were so
      // few tests to run.  (NUM_WORKER_MACHINES will in that case get reset in
      // _determineTests above.)
      if (workerNum >= NUM_WORKER_MACHINES) {
         return;
      }

      // Out with the old, in with the new!
      sh("rm -f test-results.*.pickle");
      unstash("splits");

     def runtestsArgs = [
         "--pickle",
         "--pickle-file=../test-results.${workerNum}.pickle",
         "--quiet",
         "--jobs=1",
     ];
     if (params.FAILFAST) {
         runtestsArgs += ["--failfast"];
     }
     if (!params.USE_TEST_SERVER) {
         runtestsArgs += [
             "--test-file-glob=${params.TEST_FILE_GLOB}",
             "--override-skip-by-default",
             "--max-size=${params.MAX_SIZE}",
         ];
     }
     // TODO(dhruv): share these flags with `_determineTests` to ensure we're
     // using the same config in both places.
      try {
         sh("cd webapp; " +
            // Say what machine we're on, to help with debugging
            "ifconfig; " +
            "curl -s -HMetadata-Flavor:Google http://metadata.google.internal/computeMetadata/v1/instance/hostname | cut -d. -f1; " +
            "../jenkins-jobs/timeout_output.py -v 55m " +
            "tools/runtests.py ${exec.shellEscapeList(runtestsArgs)} " +
            "- < ../test_splits.${workerNum}.txt");
      } finally {
         // Now let the next stage see all the results.
         // runtests.py should normally produce these files
         // even when it returns a failure rc (due to some test
         // or other failing).
         stash(includes: "test-results.*.pickle",
               name: "results ${workerNum}");
      }
   }
}


def determineSplitsAndRunTests() {
   def jobs = [
      // This is a kwarg that tells parallel() what to do when a job fails.
      "failFast": params.FAILFAST,
      "determine-splits": {
         withTimeout(WORKER_TIMEOUT) {
            _setupWebapp();
            dir("webapp") {
               _determineTests();
            }
         }
      }
   ];

   for (def i = 0; i < NUM_WORKER_MACHINES; i++) {
      // A restriction in `parallel`: need to redefine the index var here.
      def workerNum = i;
      jobs["test-${workerNum}"] = {
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
      for (def i = 0; i < NUM_WORKER_MACHINES; i++) {
         if (!fileExists("test-results.${i}.pickle")) {
            numPickleFileErrors++;
         }
      }
      // Send a special message if all workers fail, because that's not good
      // (and the normal script can't handle it).
      if (numPickleFileErrors == NUM_WORKER_MACHINES) {
         def msg = ("All test workers failed!  Check " +
                    "${env.BUILD_URL}consoleFull to see why.)");
         notify.fail(msg);
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
               "--expected-tests-file", "genfiles/test_splits.txt",
               "--rerun-command", rerunCommand,
            ];
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
         stage("Determining splits & running tests") {
            determineSplitsAndRunTests();
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
