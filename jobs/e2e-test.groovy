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
<pre>
cd webapp-workspace/webapp
. ../env/bin/activate
for num in `seq 1 16`; do echo -- \$num; time tools/rune2etests.py -j\$num >/dev/null 2>&1; done
</pre>
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
         def NUM_SPLITS = (params.NUM_WORKER_MACHINES.toInteger() *
                           params.JOBS_PER_WORKER.toInteger());

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
