// Single job for cypress e2e tests.
// 
// cypress e2e tests are the smoketests run in the webapp/feature/cypress repo, 
// that hit a live website using lambdatest cli.
//
// TODO(ruslan): integrate later this job to the e2e tests pipeline.
// This job runs lambdatest-cypress-cli in the webapp environment 
// depending on how parameters are specified.

// The Jenkins "interrupt" exception: for failFast and user interrupt
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.exec
//import vars.notify
//import vars.withTimeout
//import vars.withSecrets

new Setup(steps

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
   "username"

).addStringParam(
   "REVISION_DESCRIPTION",
   """Set by the buildmaster to give a more human-readable description
of the GIT_REVISION, especially if it is a commit rather than a branch.
Defaults to GIT_REVISION.""",
   ""

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

def _setupWebapp() {
   kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", params.CYPRESS_GIT_REVISION);

   // We need login creds for LambdaTest Cli
   LT_USERNAME = sh(script: """\
      keeper --config ${exec.shellEscape("${HOME}/.keeper-config.json")} \
      get qvYpo_KnpCiLBN69fYpEYA --format json | jq -r .login\
      """, returnStdout:true).trim();
   LT_ACCESS_KEY = sh(script: """\
      keeper --config ${exec.shellEscape("${HOME}/.keeper-config.json")} \
      get qvYpo_KnpCiLBN69fYpEYA --format json | jq -r .password\
      """, returnStdout:true).trim();

   dir("webapp/services/static") {
      sh("yarn install");
   }
}

def runLamdaTest() {
   // We use the build name as a unique identifier for user notifications. 
   BUILD_NAME = "e2e-test #${env.BUILD_NUMBER} (${params.URL}: ${params.CYPRESS_GIT_REVISION}) @${params.DEPLOYER_USERNAME}"

   // TODO(ruslan): Implement TEST_TYPE param to use decorator or flag 
   // in cypress script files. 
   // TODO(ruslan): Use build tags --bt with prod/znd states.
   def runLambdaTestArgs = ["yarn",
                            "lambdatest",
                            "--cy=\"--config baseUrl=${params.URL}\", retries=${params.TEST_RETRIES}",
                            "--bn",
                            BUILD_NAME,
                            "-p",
                            "${params.NUM_WORKER_MACHINES}",
                            "--sync=true", 
                            "--bt",
                            "e2e-test #${env.BUILD_NUMBER}"
   ];
   
   dir('webapp/services/static') {
         withEnv(["LT_USERNAME=${LT_USERNAME}",
                  "LT_ACCESS_KEY=${LT_ACCESS_KEY}"]) {
            exec(runLambdaTestArgs); 
      }
   }
}

// Notify users if build failed.
def notifyFailures() {
   def lambdaURL="https://api.lambdatest.com/automation/api/v1/builds"
      
   // Calling to LambdaTest for build name and id values,
   // it will return 10 last build records.
   // Retry 2 times if connection refused?
   def response = sh(
      script: "curl -s --retry-connrefused --retry 2 --retry-delay 5 " +
              "${lambdaURL} -u ${LT_USERNAME}:${LT_ACCESS_KEY}", 
      returnStdout: true).trim();
   def status = sh(
      script: "echo '$response' | jq -r '.data | map(select(.name ==" +
              " \"${BUILD_NAME}\"))[0].status_ind'", 
      returnStdout: true).trim();
   def build_id = sh(
      script: "echo '$response' | jq -r '.data | map(select(.name ==" +
              " \"${BUILD_NAME}\"))[0].build_id'", 
      returnStdout: true).trim();

   // Send failure to the Slack.
   // TODO(ruslan): Remove @ in extraText before merging with current e2e-test pipeline, 
   // we don't want to disturb real users.  
   def notifyMsg = [channel: params.SLACK_CHANNEL,
                    sender: 'Testing Turtle',
                    emoji: ':turtle:',
                    extraText : 'Hey '+'@' + 
                    params.DEPLOYER_USERNAME + 
                    ' e2e-test #' + env.BUILD_NUMBER + 
                    ' (' + params.URL + ': ' + 
                    params.CYPRESS_GIT_REVISION + 
                    ') FAILED (<https://automation.lambdatest.com/logs/?build='+build_id+'|Open>) \n ' +
                    'The following build failed ' + 
                    'https://automation.lambdatest.com/logs/?build='+build_id
   ];

   // TODO(ruslan): Do we need to assert if (code == 200) before and if not 
   // raise an error?
   if (status == null) {
      // something went wrong 
      notify.sendToSlack(notifyMsg + '@cypress-support something went wrong!', 'FAILURE');
   } else if (status != "passed") {
      // with custom status we won't send logs to #dev-support-log 
      notify.sendToSlack(notifyMsg, 'LAMBDA_ERROR');
   }
}


onWorker("ka-test-ec2", '6h') {
   notify([slack: [channel: '#cypress-testing',
                   when: ['FAILURE', 'UNSTABLE', 'ABORTED']]]) {
      stage("Sync webapp") {
         _setupWebapp();
      }
      stage("Run e2e tests") {
         runLamdaTest();
      }
      stage("Notify failures") {
         notifyFailures();
      }
   }
}
