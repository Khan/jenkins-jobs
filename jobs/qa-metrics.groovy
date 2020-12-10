// This script runs Quality Automation Metrics report from QA repo
// by schedule and URL trigger.

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
   "WEBAPP_GIT_REVISION",
   """The name of a webapp branch to use when building.""",
   "master"

).addStringParam(
   "QA_TOOLS_GIT_REVISION",
   """The name of a qa-tools branch to use when building.""",
   "master"

).apply()


def setup() {
    kaGit.safeSyncToOrigin("git@github.com:Khan/webapp",
            params.WEBAPP_GIT_REVISION);
    kaGit.safeSyncToOrigin("git@github.com/Khan/qa-tools",
            params.QA_TOOLS_GIT_REVISION);

    dir("qa-tools") {
        sh("pip install -r requirements.txt");
    }
}

def runScript() {
    withSecrets() { // to use slack
        dir("qa-tools/quality_metrics") {
            sh("metrics_script.py");
        }
    }
}

onWorker("ka-test-ec2", '6h') {
    // TODO(ruslan): this should be changed to qa channel after
    notify([slack: [channel: '#bot-testing',
                    when: ['FAILURE', 'UNSTABLE']]]) {
            stage("Setting Up") {
                setup();
            }
            stage("Running the script") {
                runScript();
            }
    }
}

