// Backup single job for cypress e2e tests.
// 
// This is a backup slow cypress e2e tests run in the webapp/feature/cypress repo, 
// that hit a live website using Jekins worker without any parallelization in case if main
// provider is down.

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.exec
//import vars.notify
//import vars.withTimeout
//import vars.withSecrets

new Setup(steps

).allowConcurrentBuilds(

// Allow multiple LambdaTest runs to execute concurrently.
).addStringParam(
   "CYPRESS_GIT_REVISION",
   """The name of a cypress branch to use when building.""",
   "master"

).addStringParam(
   "URL",
   "The url-base to run these tests against.",
   "https://www.khanacademy.org"

).addStringParam(
   "SLACK_CHANNEL",
   "The slack channel to which to send failure alerts.",
   "#cypress-testing"

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
   "master"

).addStringParam(
   // We're not using any parallelization with Cypress itself.
   "NUM_WORKER_MACHINES",
   """How many worker machines to use. Max available is 20.""",
   "20"

).addStringParam(
   "TEST_RETRIES",
   """How many retry attempts to use. By default is 1.""",
   "1"

).apply()

// We use the build name as a unique identifier for user notifications. 
BUILD_NAME = "build e2e-cypress-test #${env.BUILD_NUMBER} (${params.URL}: ${params.REVISION_DESCRIPTION})"

// At this time removing @ before username.
DEPLOYER_USER = params.DEPLOYER_USERNAME.replace("@", "")

def _setupWebapp() {
   kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", params.CYPRESS_GIT_REVISION);

   dir("webapp/services/static") {
      sh("yarn install");
   }
}

def runTests() {
   // Using one machine is very slow without parallelization, 
   // so turn off screenshots and video compressing. 
   def runCypressTestArgs = ["yarn",
                             "cypress",
                             "run", 
                             "--browser=chrome",
                             "-c {\"baseUrl\":\"${params.URL}\", \"retries\":${params.TEST_RETRIES}, \
                             \"screenshotOnRunFailure\":false, \"video\":false}"       
   ];
   
   dir('webapp/services/static') {
      exec(runCypressTestArgs); 
   }
}


onWorker("ka-test-ec2", '6h') {
   notify([slack: [channel: params.SLACK_CHANNEL,
                   sender: 'Testing Turtle',
                   emoji: ':turtle:',
                   extraText : "Hey ${DEPLOYER_USER} ${BUILD_NAME} FAILED",
                   when: ['FAILURE', 'UNSTABLE', 'ABORTED']]]) {
      stage("Sync webapp") {
         _setupWebapp();
      }
      stage("Run e2e tests") {
         runTests();
      }
   }
}
