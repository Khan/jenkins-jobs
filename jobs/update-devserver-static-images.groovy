// The pipeline job to update the dev-server static images in gcloud.
//
// When called (which we expect to be every day), this job rebuilds
// all the images that we use for local dev-server -- one for each
// first-party service in webapp -- and uploads them to the google
// container registry.  It then updates a file in webapp saying what
// git commit corresponds to the latest-pushed images, and checks
// that into the `automated-commits` branch.

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
   '0 2 * * *'

).apply();


def runScript() {
   // We do our work in the automated-commits branch (first pulling in master).
   kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", params.GIT_BRANCH);
   kaGit.safeMergeFromMaster("webapp", params.GIT_BRANCH);

   dir("webapp") {
      sh("timeout 2m git clean -ffd"); // in case the merge modified .gitignore
      sh("timeout 1m git clean -ffd"); // sometimes we need two runs to clean

      sh("make fix_deps");  // force a remake of all deps all the time

      // Run the script!
      sh("Make -C dev/server upload-all-static-images");
   }
}


def publishResults() {
   dir("webapp") {
      sh("git add dev/server/.env.latest_uploaded_build_tag");
   }
   // Check it in!
   kaGit.safeCommitAndPush(
      "webapp", ["-m", "Automated update of .env.latest_uploaded_build_tag"]);
}


onMaster('2h') {
   notify([slack: [channel: params.SLACK_CHANNEL,
                   sender: 'Devserver Duck',
                   emoji: ':duck:',
                   when: ['SUCCESS', 'FAILURE', 'UNSTABLE', 'ABORTED']]]) {
      stage("Run script") {
         runScript();
      }
      stage("Publish results") {
         publishResults();
      }
   }
}
