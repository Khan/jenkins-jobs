// The pipeline job to update the dev-server images in gcloud.
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
    "SERVICES",
    """Comma-separated list of services to update and re-push.
    If the empty string, run update all services.""",
    ""

).addBooleanParam(
    "MERGE_MASTER",
    """If true, merge master into the branch before working.""",
    true

).addStringParam(
   "SLACK_CHANNEL",
   "The slack channel to send our status info.",
   "#infrastructure"

).addCronSchedule(
   '0 22 * * *'

).apply();


def _setupWebapp() {
   // We do not allow pushing directly to master.
   if (params.GIT_BRANCH == "master") {
      notify.fail("You may not push directly to master.  Try `automated-commits` instead.")
   }

   // We do our work in the automated-commits branch (first pulling in master).
   kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", params.GIT_BRANCH);
   if (params.MERGE_MASTER) {
      kaGit.safeMergeFromMaster("webapp", params.GIT_BRANCH);
   }
   dir("webapp") {
      sh("timeout 2m git clean -ffd"); // in case the merge modified .gitignore
      sh("timeout 1m git clean -ffd"); // sometimes we need two runs to clean
   }
}

def uploadService(service) {
   dir("webapp") {
      // TODO(csilvers): figure out why this periodically (regularly,
      // but unpredictably) fails with:
      // ERROR: failed to receive status: rpc error: code = Unavailable desc = error reading from server: EOF
      retry(5) {
         exec(["ssh-agent", "sh", "-c", "ssh-add; dev/server/upload-images.sh --no-tags " + service]);
      }
   }
}

def publishResults() {
   dir("webapp") {
      sh("git add dev/server/.env.latest_uploaded_build_tag");
      // The upload-all-static-images rule can modify some other generated
      // files, that we don't care about.  `git restore .` resets everything
      // in the client except files that have been `git add`-ed.
      sh("git restore .");
   }
   // Check it in!
   kaGit.safeCommitAndPush(
      "webapp", ["-m", "Automated update of .env.latest_uploaded_build_tag"]);
}


// NOTE: it would be nice to run this on jenkins-server, so we could
// re-use our caches from run to run.  But the job uses so much CPU
// that it can bring down our server!  See
// https://khanacademy.slack.com/archives/C096UP7D0/p1738681917185069
onWorker('build-worker', '10h') {
   notify([slack: [channel: params.SLACK_CHANNEL,
                   failureChannel: "#local-devserver",
                   sender: 'Devserver Duck',
                   emoji: ':duck:',
                   when: ['SUCCESS', 'FAILURE', 'UNSTABLE', 'ABORTED']]]) {
      stage("Setup webapp") {
         _setupWebapp();
      }

      def services = [];
      stage("Listing services to regenerate") {
         if (params.SERVICES) {
             services = params.SERVICES.split(",");
         } else {
             dir("webapp") {
                 def services_str = exec.outputOf(["dev/server/upload-images.sh", "--list"]);
                 echo("Updating these services:\n${services_str}");
                 services = services_str.split("\n");
             }
         }
      }

      for (def i = 0; i < services.size(); i++) {
         stage(services[i]) {
            uploadService(services[i]);
         }
      }

      stage("Publish results") {
         publishResults();
      }
   }
}
