// Automate monthly change for PL sandbox account passwords
// https://khanacademy.atlassian.net/browse/DIST-4092
//
// TODO(nathanjd): This job is broken due to the removal of services/static. It
// will need to be updated to interface with the frontend repo instead of webapp
// so might be better as a GitHub Action.

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

).addStringParam(
   "CYPRESS_GIT_REVISION",
   """The name of a cypress branch to use when building.""",
   "update-psw"

).addStringParam(
   "SLACK_CHANNEL",
   "The slack channel to which to send failure alerts.",
   "#demo-district-logs"

).addStringParam(
   "TEST_RETRIES",
   """How many retry attempts to use. By default is 1.""",
   "1"

).addCronSchedule(
   'H H 1 * *'        // Run on the first day of every month

).apply()

// We use the build name as a unique identifier for user notifications. 
BUILD_NAME = "build demo-district-password-update";

def _setupWebapp() {
   kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", params.CYPRESS_GIT_REVISION);
   dir("webapp/services/static") {
      sh("make npm_deps");
   }
}

def runScript() {
   def workDir = "webapp/services/static/javascript/districts-package/__e2e-tests__";
   def runCypressArgs = ["./dev/tools/run_pkg_script.sh",
                         "cypress",
                         "run", 
                         "--spec",
                         "javascript/districts-package/__e2e-tests__/change-pswd.spec.ts",
                         "--browser=electron",
                         "-c {\"retries\":${params.TEST_RETRIES}, \
                         \"screenshotOnRunFailure\":false, \"video\":false}"
   ];
   def dockerBuild = ["docker", "build", "-t", "update-pass", "."];
   def dockerRun = ["docker", "run", "update-pass"];

   dir('webapp/services/static') {        
      exec(runCypressArgs); 
   }

   dir(workDir) {
      exec(dockerBuild); 
      exec(dockerRun); 
   }
}

// We can't run this on the master because the Jenkins server doesn't have Cypress dependencies installed.
onWorker("ka-test-ec2", '4d') {
   notify([slack: [channel: params.SLACK_CHANNEL,
                   sender: 'Testing Turtle',
                   emoji: ':turtle:',
                   extraText : "Hey, ${BUILD_NAME} FAILED",
                   when: ['FAILURE', 'UNSTABLE', 'ABORTED']]]) {
      stage("Sync webapp") {
         _setupWebapp();
      }
      stage("Run script") {
         runScript();
      }
   }
}
