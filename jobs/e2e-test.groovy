// Single job for cypress e2e tests.
//
// cypress e2e tests are the smoketests run in the webapp/feature/cypress repo,
// that hit a live website using lambdatest cli.

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import groovy.json.JsonBuilder;
import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.withVirtualenv
//import vars.exec
//import vars.notify
//import vars.singleton
//import vars.withTimeout
//import vars.kaGit
//import vars.onWorker
//import vars.withSecrets
//import vars.buildmaster
//import vars.onMaster
//import vars.clean

new Setup(steps

).allowConcurrentBuilds(

// We do a lot of e2e-test runs, and QA would like to be able to see details
// for a bit longer.
).resetNumBuildsToKeep(
   350,

).addStringParam(
   "URL",
   "The url-base to run these tests against.",
   "https://www.khanacademy.org"

).addChoiceParam(
   "TEST_TYPE",
   """IGNORE: This is a dummy parameter that is only here to avoid breaking the
   communication with buildmaster""",
   ["all", "deploy", "custom"]

).addStringParam(
   "TESTS_TO_RUN",
   """IGNORE: This is a dummy parameter that is only here to avoid breaking the
   communication with buildmaster""",
   ""

).addStringParam(
   "SLACK_CHANNEL",
   "The slack channel to which to send failure alerts.",
   "#1s-and-0s-deploys"

).addStringParam(
   "SLACK_THREAD",
   """The slack thread (must be in SLACK_CHANNEL) to which to send failure
alerts.  By default we do not send in a thread.  Generally only set by the
buildmaster, to the 'thread_ts' or 'timestamp' value returned by the Slack
API.""",
   ""

).addStringParam(
   "NUM_WORKER_MACHINES",
   """How many worker machines to use in LambdaTest. Max available is 30.""",
   "30"

).addBooleanParam(
   "USE_FIRSTINQUEUE_WORKERS",
   """IGNORE: Using ka-firstinqueue-ec2 currently breaks Cypress Cloud's
ability to run in parallel. It is hardcoded to use ka-test-ec2 for now.
If true, use the jenkins workers that are set aside for the
currently active deploy.  Obviously, this should only be set if you
are, indeed, the currently active deploy.  We reserve these machines
so the currently active deploy never has to wait for smoketest workers
to spin up.""",
   false

).addStringParam(
   "GIT_REVISION",
   """A commit-ish to check out.  This only affects the version of the
E2E test used; it will probably match the tested version's code,
but it doesn't need to.""",
   "master"

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

).addBooleanParam(
   "SET_SPLIT_COOKIE",
   """IGNORE: This is a dummy parameter that is only here to avoid breaking the
   communication with buildmaster""",
   false

).addStringParam(
   "EXPECTED_VERSION",
   """IGNORE: This is a dummy parameter that is only here to avoid breaking the
   communication with buildmaster""",
   ""

).addStringParam(
   "EXPECTED_VERSION_SERVICES",
   """IGNORE: This is a dummy parameter that is only here to avoid breaking the
   communication with buildmaster""",
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

).addStringParam(
   "SKIP_TESTS",
   """IGNORE: This is a dummy parameter that is only here to avoid breaking the
   communication with buildmaster""",
   ""

).apply();

// Override the build name by the info that is passed in (from buildmaster).
REVISION_DESCRIPTION = params.REVISION_DESCRIPTION ?: params.GIT_REVISION;
// Drop this part so the branches can be grouped in Cypress Cloud
SHORT_REVISION_DESCRIPTION = REVISION_DESCRIPTION.replaceAll(/\s*\((now live|currently deploying)\)/, '');
BASE_URL = params.URL;
E2E_URL = BASE_URL[-1] == '/' ? BASE_URL.substring(0, BASE_URL.length() - 1): BASE_URL;

currentBuild.displayName = ("${currentBuild.displayName} " +
   "(${REVISION_DESCRIPTION})");

// We use the build name as a unique identifier for user notifications.
BUILD_NAME = "${E2E_URL} #${env.BUILD_NUMBER}"

// At this time removing @ before username.
DEPLOYER_USER = params.DEPLOYER_USERNAME.replace("@", "")

// GIT_SHA1 is the sha1 for GIT_REVISION.
GIT_SHA1 = null;
GITHUB_TOKEN = null;

SLACK_TOKEN = null;

// Use first worker available
WORKER_TYPE = "ka-firstinqueue-ec2";

def initializeGlobals() {
   GIT_SHA1 = kaGit.resolveCommitish("git@github.com:Khan/webapp",
      params.GIT_REVISION);
   // for security, use only with withGithubToken!
   GITHUB_TOKEN = exec.outputOf([
         "gcloud", "--project", "khan-academy",
         "secrets", "versions", "access", "latest",
         "--secret", "jenkins_github_webapp_e2e_workflow_runner_token",
   ]);

   SLACK_TOKEN = exec.outputOf([
          "gcloud", "--project", "khan-academy",
          "secrets", "versions", "access", "latest",
          "--secret", "Slack_api_token_for_slack_owl",
   ]);
}

def withGitHubToken(body) {
   wrap([$class: 'MaskPasswordsBuildWrapper', varPasswordPairs: [[password: GITHUB_TOKEN]]]) {
      withEnv(["GITHUB_TOKEN=${GITHUB_TOKEN}"]) {
         body()
      }
   }
}

def _pullWebapp() {
   // need script to start the workflow
   kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", GIT_SHA1, [], force=true);
}

// Determines if we are running the first or second smoke test.
E2E_RUN_TYPE = (E2E_URL == "https://www.khanacademy.org" ? "second-smoke-test" : "first-smoke-test");

onWorker(WORKER_TYPE, '5h') {     // timeout
   notify([slack: [channel: params.SLACK_CHANNEL,
                  thread: params.SLACK_THREAD,
                  sender: 'Testing Turtle',
                  emoji: ':turtle:',
                  when: ['FAILURE', 'UNSTABLE']],
          buildmaster: [sha: params.GIT_REVISION,
                        what: E2E_RUN_TYPE]]) {
      initializeGlobals();
      _pullWebapp();
      stage("Run e2e tests") {
         withGitHubToken {
            withEnv(["SLACK_TOKEN=${SLACK_TOKEN}"]) {
               def githubWorkflowArgs = [
                  "npx",
                  "--yes",
                  "tsx",
                  "./tools/notify-workflow-status.ts",
                  // E2EWorkflowConfig params
                  "--checkout-ref=${params.GIT_REVISION}",
                  "--build-name=${BUILD_NAME}",
                  "--base-url=${params.URL}",
                  // SummarizeOptions params
                  "--channel=${params.SLACK_CHANNEL}",
                  "--build-url=${BUILD_URL}",  // This would be the Jenkins build URL
                  "--label=${params.REVISION_DESCRIPTION ?: params.GIT_REVISION}",
                  "--url=${params.URL}",
                  "--deployer=${params.DEPLOYER_USERNAME ? "@${params.DEPLOYER_USERNAME}" : ""}",
                  "--thread=${params.SLACK_THREAD}"
               ];
               dir("webapp/testing/e2e") {
                  exec(["pnpm", "install"]);
                  exec(githubWorkflowArgs);
               }
            }
         }
      }
   }
}
