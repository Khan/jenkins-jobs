// The pipeline job that starts up some 2nd-smoke-test workers.
//
// This job just does the git-merging and deps-building that
// the second smoke-test job needs.  It doesn't actually run
// any smoketests.
//
// This is a time-saving measure: its big win is making sure that the
// 2nd-smoke-test workers are actually running.  Since it can take up
// to 5 minutes for a gce machine to spin up, it's to our advantage to
// do this priming at the beginning of a deploy, well before it's time
// for the deploy to actually run the 2nd smoke test.
//
// We can do this because the 2nd smoke test runs on a dedicated set
// of machines, so the machines we prime here will be the ones that
// are available when it's time to actually run the smoketests.
//
// This job is only every run by a job that's first in the deploy
// queue.  Because the 2nd smoke tests is only run by jobs that are
// first in the deploy queue, we can easily guarantee that this job
// never conflicts with an actual 2nd-smoke-test job.

@Library("kautils")
// Standard Math classes we use.
import java.lang.Math;
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

).addStringParam(
   "GIT_REVISION",
   """A commit-ish to check out.  This only affects the version of the
E2E test used; it will probably match the tested version's code,
but it doesn't need to.""",
   "master"

).addStringParam(
   "NUM_WORKER_MACHINES",
   """How many worker machines to use.""",
   onWorker.defaultNumTestWorkerMachines().toString()

).addStringParam(
   "REVISION_DESCRIPTION",
   """Set by the buildmaster to give a more human-readable description
of the GIT_REVISION, especially if it is a commit rather than a branch.
Defaults to GIT_REVISION.""",
   ""

).apply();

REVISION_DESCRIPTION = params.REVISION_DESCRIPTION ?: params.GIT_REVISION;

currentBuild.displayName = ("${currentBuild.displayName} " +
                            "(${REVISION_DESCRIPTION})");


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
   kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", GIT_SHA1);
   dir("webapp") {
      sh("make clean_pyc");
      sh("make python_deps");
   }
}


def prime() {
   onWorker('ka-2ndsmoketest-ec2', '1h') {     // timeout
      _setupWebapp();
      // We also need to sync mobile, so we can run the mobile integration test
      // (if we are assigned to do so).
      // TODO(benkraft): Only run this if we get it from the splits?
      kaGit.safeSyncToOrigin("git@github.com:Khan/mobile", "master");
   }
}

def primeWorkers() {
   def jobs = [
       "determine-splits": prime,
   ];
   for (def i = 0; i < NUM_WORKER_MACHINES; i++) {
      // A restriction in `parallel`: need to redefine the index var here.
      def workerNum = i;
      jobs["e2e-test-${workerNum}"] = {
         onWorker('ka-2ndsmoketest-ec2', '1h') {     // timeout
            prime();
            // Hold the machine to make sure jenkins actually starts
            // a new machine for each worker, rather than reusing a
            // machine if it happens to be really fast at building deps.
            sleep(120);  // seconds
         }
      }
   }

   parallel(jobs);
}


onWorker('ka-2ndsmoketest-ec2', '1h') {  // timeout
   // We don't do any notification; priming is best-effort only.
   notify([timeout: "1h"]) {
      initializeGlobals();
      stage("Priming") {
         primeWorkers();
      }
   }
}
