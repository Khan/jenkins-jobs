// Automate monthly change for PL sandbox account passwords
// https://khanacademy.atlassian.net/browse/DIST-4092

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
   "REVISION_DESCRIPTION",
   """Set by the buildmaster to give a more human-readable description
of the GIT_REVISION, especially if it is a commit rather than a branch.
Defaults to GIT_REVISION.""",
   "master"

).addStringParam(
   "TEST_RETRIES",
   """How many retry attempts to use. By default is 1.""",
   "1"

).apply()

// We use the build name as a unique identifier for user notifications. 
BUILD_NAME = "build demo-district-password-update #${env.BUILD_NUMBER} (${params.REVISION_DESCRIPTION})"

def _setupWebapp() {
   kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", params.CYPRESS_GIT_REVISION);
   def runPythonTestArgs = 'sudo apt-get install -y python3.10'.split()
   
   dir("webapp/services/static") {
      // We need to install Py3 to use google-api-spreadsheet-client
      sh script: runPythonTestArgs.join(' '), returnStatus: true 
      sh '/usr/bin/python3 -m pip install --upgrade google-api-python-client google-auth-httplib2 google-auth-oauthlib'
   }
}

def runScript() {
   def runCypressArgs = ["yarn",
                         "cypress",
                         "run", 
                         "--spec",
                         "javascript/districts-package/__e2e-tests__/change-pswd.spec.ts",
                         "--browser=electron",
                         "-c {\"retries\":${params.TEST_RETRIES}, \
                         \"screenshotOnRunFailure\":false, \"video\":false}"
   ];

   dir('webapp/services/static') {        
      exec(runCypressArgs); 
      sh '/usr/bin/python3 /home/ubuntu/webapp-workspace/webapp/services/static/javascript/districts-package/__e2e-tests__/update-psw.py'
   }
}

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
