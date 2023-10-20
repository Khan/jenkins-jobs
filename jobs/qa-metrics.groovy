// This script runs Quality Automation Metrics report from QA repo
// by schedule and URL trigger.

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.exec
//import vars.notify
//import vars.withTimeout


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


def runScript() {
    def tempDir = exec.outputOf(["mktemp", "-d", "-t", "qa-tools-XXXXXXXXXX"]);

    dir(tempDir) {
        sh("git clone --depth 1 git@github.com:Khan/qa-tools.git");
    }
    try {
        dir(tempDir+"/qa-tools") {
            sh("pip install -r requirements.txt");
            sh("cd quality_metrics && python metrics_script.py");
        }
    } finally {
        sh("rm -rf ${exec.shellEscape(tempDir)}");
    }
}

onWorker("ka-test-ec2", '6h') {
    // TODO(ruslan): this should be changed to qa channel after
    notify([slack: [channel: '#bot-testing',
                    when: ['FAILURE', 'UNSTABLE']]]) {
        stage("Run a script") {
            runScript();
        }
    }
}
