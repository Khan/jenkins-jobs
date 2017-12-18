// NOTE: DO NOT USE -- this allows Joshua Netterfield to actually test the
// Jenkins job while working on it, since there isn't a practical way of testing
// it locally. It's not ready for use yet.

// The pipeline job for calculating page weight.

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
   "GIT_REVISION_BASE",
   """A commit-ish to consider as the baseline. If you are testing a Phabricator
diff, this should be of the form phabricator/base/xxxxxx.""",
   ""

).addStringParam(
   "GIT_REVISION_DIFF",
   """A commit-ish to compare against the baseline. If you are testing a Phabricator
diff, this should be of the form phabricator/diff/xxxxxx.""",
   ""

).apply();

currentBuild.displayName = ("${currentBuild.displayName} " +
      "(${params.GIT_REVISION_BASE} vs ${params.GIT_REVISION_DIFF})");


// We set these to real values first thing below; but we do it within
// the notify() so if there's an error setting them we notify on slack.
GIT_SHA_BASE = null;
GIT_SHA_DIFF = null

def initializeGlobals() {
   // We want to make sure all nodes below work at the same sha1,
   // so we resolve our input commit to a sha1 right away.
   // Note that this is not needed for Phabricator diffs since those are
   // immutable, but it's still good practice.
   GIT_SHA_BASE = kaGit.resolveCommitish("git@github.com:Khan/webapp",
         params.GIT_REVISION_BASE);

   GIT_SHA_DIFF = kaGit.resolveCommitish("git@github.com:Khan/webapp",
         params.GIT_REVISION_DIFF);
}


def _setupWebapp() {
   kaGit.safeSyncTo("git@github.com:Khan/webapp", "148a5a26e74117f6f0056b855c66715237935fd9");
   dir("webapp") {
      sh("make clean_pyc");
      sh("make deps");
      sh("make current.sqlite");  // TODO(joshuan): Cache this and update every n days?
   }
}


def _computePageWeightDelta() {
   // This will be killed when the job ends -- see https://wiki.jenkins.io/display/JENKINS/ProcessTreeKiller
   sh("make serve &");
   sleep(10);  // STOPSHIP(joshuan): instead detect when 8081 is up. I'm just seeing if this works.
   exec(["xvfb-run", "-a", "tools/compute_page_weight_delta.sh", GIT_SHA_BASE, GIT_SHA_DIFF]);
}


def calculatePageWeightDeltas() {
   onTestWorker('1h') {     // timeout
      _setupWebapp();

      // This is apparently needed to avoid hanging with
      // the chrome driver.  See
      // https://github.com/SeleniumHQ/docker-selenium/issues/87
      // We also work around https://bugs.launchpad.net/bugs/1033179
      withEnv(["DBUS_SESSION_BUS_ADDRESS=/dev/null",
               "TMPDIR=/tmp"]) {
         withSecrets() {   // we need secrets to talk to bq/GCS
            dir("webapp") {
               _computePageWeightDelta();
            }
         }
      }
   }
}

// Notify does more than notify on Slack. It also acts as a node and sets a timeout.
// We don't need notifications for this job, currently, but using this instead of a
// node and `onMaster` keeps this consistent with other jobs.
// TODO(joshuan): Consider renaming `notify`.
notify([timeout: "2h"]) {
   initializeGlobals();
   
   // We want to make sure no other job sneaks in and steals our test-workers from us.
   // So we acquire this lock for the entire job. We also want to make sure we don't
   // steal test workers from anyone else. It depends on everyone else who uses the
   // test-workers using this lock too.
   lock(label: 'using-test-workers', quantity: 1) {
      stage("Calculating page weight deltas") {
         calculatePageWeightDeltas();
      }
   }
}
