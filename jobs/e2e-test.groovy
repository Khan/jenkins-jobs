// The pipeline job for e2e tests.

@Library("kautils")
import org.khanacademy.Setup;
import org.khanacademy.GitUtils;

new Setup(steps).addStringParam(
   "URL",
   "The url-base to run these tests against.",
   "https://www.khanacademy.org"

).addStringParam(
   "SLACK_CHANNEL",
   "The slack channel to which to send failure alerts.",
   "#1s-and-0s-deploys"

).addStringParam(
   "GIT_REVISION",
   "A commit-ish to check out.  This only affects the version of the
E2E test used; it will probably match the tested version's code,
but it doesn't need to.",
   "master"

).addBooleanParam(
   "FAILFAST",
   "If set, stop running tests after the first failure.",
   false

).addStringParam(
   "DEPLOYER_USERNAME",
   "Who asked to run this job, used to ping on slack.
Typically not set manually, but rather by other jobs that call this one.",
   "@AutomatedRun"

).apply();


// We run on 4 workers.
env.NUM_WORKERS = 4

// We run 4 jobs on each worker (because they're m3.large and that's
// how many chrome's fit comfortably on that machine size).
env.JOBS_PER_WORKER = 4


// TODO(csilvers): add a good timeout
// TODO(csilvers): set the build name to
//     #${BUILD_NUMBER} (${ENV, var="GIT_REVISION"})

stage("Determining splits") {
   node("master") {
      timestamps {
         // Figure out how to split up the tests.  We run 4 jobs on
         // each of 4 workers.  We put this in the location where the
         // 'copy to slave' plugin expects it (e2e-test-worker will
         // copy the file from here to each worker machine).
         def NUM_SPLITS = env.NUM_SPLITS * env.JOBS_PER_WORKER;

         gitUtils = new org.khanacademy.GitUtils();
         gitUtils.safeSyncToOrigin "git@github.com:Khan/webapp", "master";
         dir("webapp") {
            sh "make python_deps";
            sh ("tools/rune2etests.py --dry-run --just-split -j${NUM_SPLITS}" +
                "> genfiles/e2e-test-splits.txt");
            dir("genfiles") {
               stash includes: "e2e-test-splits.txt", name: "splits";
            }
         }
      }
   }
}

stage("Running tests") {
   // Touch a file to indicate that a job is running that uses
   // the jenkins make-check workers.  We have a cron job
   // running on jenkins that will keep track of the make-check
   // workers and complain if a job that uses the make-check
   // workers is running, but all the workers aren't up.  (We
   // delete this file in an always-run post-build step.)
   touch /tmp/make_check.run

   // TODO(csilvers): finish
   // https://jenkins.khanacademy.org/job/e2e-test/configure

}
