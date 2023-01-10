// Single job for cypress e2e tests.
// 
// cypress e2e tests are the smoketests run in the webapp/feature/cypress repo, 
// that hit a live website using lambdatest cli.
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
   "GIT_REVISION",
   """The name of a cypress branch to use when building.""",
   "master"

).addStringParam(
   "URL",
   "The url-base to run these tests against.",
   "https://www.khanacademy.org/"

).addStringParam(
   "SLACK_CHANNEL",
   "The slack channel to which to send failure alerts.",
   "#cypress-logs-deploys"

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
   """How many retry attempts to use. By default is 3.""",
   "3"

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

// Override the build name by the info that is passed in (from buildmaster).
REVISION_DESCRIPTION = params.REVISION_DESCRIPTION ?: params.GIT_REVISION;
currentBuild.displayName = ("${currentBuild.displayName} " +
                            "(${REVISION_DESCRIPTION})");

// We use the build name as a unique identifier for user notifications. 
BUILD_NAME = "build e2e-cypress-test #${env.BUILD_NUMBER} (${params.URL}: ${params.REVISION_DESCRIPTION})"

// At this time removing @ before username.
DEPLOYER_USER = params.DEPLOYER_USERNAME.replace("@", "")

// GIT_SHA1 is the sha1 for GIT_REVISION.
GIT_SHA1 = null;

def _setupWebapp() {
   GIT_SHA1 = kaGit.resolveCommitish("git@github.com:Khan/webapp",
                                        params.GIT_REVISION);

   kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", GIT_SHA1);

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

   // Determine which environment we're running against, so we can provide a tag
   // in the LambdaTest build.
   def e2eEnv = params.URL == "https://www.khanacademy.org" ? "prod" : "preprod";
   
   def runLambdaTestArgs = ["yarn",
                            "lambdatest",
                            "--cy='--config baseUrl=\"${params.URL}\",retries=${params.TEST_RETRIES}'",
                            "--bn='${BUILD_NAME}'",
                            "-p=${params.NUM_WORKER_MACHINES}",
                            "--sync=true", 
                            "--bt='jenkins,${e2eEnv}'",
                            "--eof"
   ];
   
   dir('webapp/services/static') {
      withEnv(["LT_USERNAME=${lt_username}",
               "LT_ACCESS_KEY=${lt_access_key}"]) {
         exec(runLambdaTestArgs);
      }
   }
}

def analyzeResults() {
   withTimeout('5m') {
      if (currentBuild.result == 'ABORTED') {
         // No need to report the results in the case of abort!  They will
         // likely be more confusing than useful.
         echo('We were aborted; no need to report results.');
         return;
      }

      dir('webapp/services/static') {
         withCredentials([
            string(credentialsId: "SLACK_BOT_TOKEN", variable: "SLACK_TOKEN")
         ]) {
            def deployerUsername = params.DEPLOYER_USERNAME;
            
            def notifyResultsArgs = [
               "./dev/cypress/e2e/tools/notify-e2e-results.js",
               "--channel", params.SLACK_CHANNEL,
               // The URL associated to this Jenkins build.
               "--build-url", env.BUILD_URL,
               // The deployer is the person who triggered the build (this is
               // only included in the message if the e2e tests fail).
               "--deployer", deployerUsername,
               // The LambdaTest build name that will be included at the
               // beginning of the message.
               "--label", BUILD_NAME,
               // The URL we test against.
               "--url", params.URL,
               // Notify failures to DevOps
               "--cc-on-failure", "#dev-support-log"
            ];

            if (params.SLACK_THREAD) {
               notifyResultsArgs += ["--slack-thread", params.SLACK_THREAD];
            }

            // Include the deployer here so they can get DMs when the e2e
            // results are ready.
            def ccAlways = "#cypress-logs-deploys,${deployerUsername}";
            
            if (params.SLACK_CHANNEL != "#qa-log") {
               ccAlways += ",#qa-log";
            }

            notifyResultsArgs += ["--cc-always", ccAlways];

            // notify-e2e-results returns a non-zero rc if it detects test
            // failures. We set the job to UNSTABLE in that case (since we don't
            // consider smoketest failures to be blocking). But we still keep on
            // running the rest of this script!
            catchError(buildResult: "UNSTABLE", stageResult: "UNSTABLE",
                        message: "There were test failures!") {
               exec(notifyResultsArgs);
            }

            // Let notify() know not to send any messages to slack, because we
            // just did it here.
            env.SENT_TO_SLACK = '1';
         }
      }
   }
}


onWorker("ka-test-ec2", '6h') {
   notify([slack: [channel: params.SLACK_CHANNEL,
                   sender: 'Testing Turtle',
                   emoji: ':turtle:',
                   extraText : "Hey ${DEPLOYER_USER} ${BUILD_NAME} " +
                   "FAILED (<https://automation.lambdatest.com/logs/|Open>)",
                   when: ['FAILURE', 'UNSTABLE', 'ABORTED']]]) {
      stage("Sync webapp") {
         _setupWebapp();
      }

      try {
         stage("Run e2e tests") {
            runLamdaTest();
            
         }
      } finally {
         // We want to analyze results even if -- especially if -- there were
         // failures; hence we're in the `finally`.
         stage("Analyzing results") {
            analyzeResults();
         }
      }

   }
}
