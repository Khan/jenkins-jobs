// Single job for cypress e2e tests.
// 
// cypress e2e tests are the smoketests run in the webapp/feature/cypress repo, 
// that hit a live website using lambdatest cli.
//
// TODO(ruslan): integrate later this job to the e2e tests pipeline.
// This job runs lambdatest-cypress-cli in the webapp environment 
// depending on how parameters are specified.

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
   "feature/cypress"

).addStringParam(
   "URL",
   "The url-base to run these tests against.",
   "https://www.khanacademy.org/"

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
   "NUM_WORKER_MACHINES",
   """How many worker machines to use. Max available is 20.""",
   "20"

).addStringParam(
   "TEST_RETRIES",
   """How many retry attempts to use. By default is 1.""",
   "1"

).addChoiceParam(
   "TEST_TYPE",
   """\
<ul>
  <li> <b>all</b>: run all tests</li>
  <li> <b>deploy</b>: run only those tests that are important to run at
        deploy-time (as identified by the `@`
        decorator)</li>
  <li> <b>nightly</b>: run only night list of tests.</li>
</ul>
""",
   ["all", "deploy", "nightly"]

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

def runLamdaTest() {
   // We need login creds for LambdaTest Cli
   def lt_username = sh(script: """\
      keeper --config ${exec.shellEscape("${HOME}/.keeper-config.json")} \
      get qvYpo_KnpCiLBN69fYpEYA --format json | jq -r .login\
      """, returnStdout:true).trim();
   def lt_access_key = sh(script: """\
      keeper --config ${exec.shellEscape("${HOME}/.keeper-config.json")} \
      get qvYpo_KnpCiLBN69fYpEYA --format json | jq -r .password\
      """, returnStdout:true).trim();
   
   // TODO(ruslan): Implement TEST_TYPE param to use decorator or flag 
   // in cypress script files. 
   // TODO(ruslan): Use build tags --bt with prod/znd states.
   def runLambdaTestArgs = ["yarn",
                            "lambdatest",
                            "--cy='--config baseUrl=\"${params.URL}\", retries=${params.TEST_RETRIES}'",
                            "--bn='${BUILD_NAME}'",
                            "-p=${params.NUM_WORKER_MACHINES}",
                            "--sync=true", 
                            "--bt='e2e-test #${env.BUILD_NUMBER}'",
                            "--eof"
   ];
   
   dir('webapp/services/static') {
      withEnv(["LT_USERNAME=${lt_username}",
               "LT_ACCESS_KEY=${lt_access_key}"]) {
         exec(runLambdaTestArgs); 
      }
   }
}


onWorker("ka-test-ec2", '6h') {
   // TODO(ruslan): Add specific build_id number to lambdatest logs link.
   notify([slack: [channel: params.SLACK_CHANNEL,
                   sender: 'Testing Turtle',
                   emoji: ':turtle:',
                   extraText : "Hey ${DEPLOYER_USER} ${BUILD_NAME} " +
                   "FAILED (<https://automation.lambdatest.com/logs/|Open>)",
                   when: ['FAILURE', 'UNSTABLE', 'ABORTED']]]) {
      stage("Sync webapp") {
         _setupWebapp();
      }
      stage("Run e2e tests") {
         runLamdaTest();
      }
   }
}
