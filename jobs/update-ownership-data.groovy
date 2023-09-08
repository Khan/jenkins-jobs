// The pipeline job to update dev/ownership_data.json.
//
// This file contains the mapping of who owns what; see dev/ownership.py for
// details.  This job updates the file automatically, checks it into git, and
// uploads it to GCS.

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.exec
//import vars.kaGit
//import vars.notify
//import vars.withTimeout


new Setup(steps

).addStringParam(
   "GIT_BRANCH",
   """The branch on which to work; we check it out, merge master, and then push
the updated data-file to it.""",
   "automated-commits"

).addStringParam(
   "SLACK_CHANNEL",
   "The slack channel to send our status info.",
   "#infrastructure"

).addCronSchedule(
   '0 3 * * *'

).apply();


def runScript() {
   // We do our work in the automated-commits branch (first pulling in master).
   kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", params.GIT_BRANCH);
   kaGit.safeMergeFromMaster("webapp", params.GIT_BRANCH);

   dir("webapp") {
      sh("timeout 2m git clean -ffd"); // in case the merge modified .gitignore
      sh("timeout 1m git clean -ffd"); // sometimes we need two runs to clean

      sh("make clean_pyc");    // in case some .py files went away
      sh("make fix_deps");  // force a remake of all deps all the time

      // Run the script!
      sh("dev/tools/update_ownership_data.py");

      // Check we didn't break anything.
      sh("tools/runtests.sh dev/consistency_tests/ownership_test.py");
   }
}


def publishResults() {
   dir("webapp") {
      sh("git add dev/ownership_data.json");
      // Also publish to GCS, for usage from scripts.
      // TODO(benkraft): Is this actually the best way for scripts to read this
      // data?
      sh("gsutil cp dev/ownership_data.json "
         + "gs://webapp-artifacts/ownership_data.json");
   }
   // Check it in!
   kaGit.safeCommitAndPush(
      "webapp", ["-m", "Automated update of ownership_data.json"]);
}


onMaster('2h') {
   notify([slack: [channel: params.SLACK_CHANNEL,
                   sender: 'Testing Turtle',
                   emoji: ':turtle:',
                   when: ['SUCCESS', 'FAILURE', 'UNSTABLE', 'ABORTED']]]) {
      stage("Run script") {
         runScript();
      }
      stage("Publish results") {
         publishResults();
      }
   }
}
