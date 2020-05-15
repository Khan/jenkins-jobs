// The pipeline job for Map Districts Support
// 
// TODO(davidbraley): rename this file and the task something more fitting,
//  perhaps simulate-student-usage.groovy?
// 
// This script simulates students watching videos for the purpose of generating data 

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.onWorker
//import vars.notify
//import vars.kaGit
//import vars.withSecrets


new Setup(steps

).addStringParam(
   "WEBAPP_GIT_REVISION",
   """The git commit-hash to run tests at, or a symbolic name referring
to such a commit-hash.  Can also be a list of branches to deploy separated
by `+` ('br1+br2+br3').  In that case we will merge the branches together --
dying if there's a merge conflict -- and run tests on the resulting code.""",
   "master"

).addStringParam(
   "QA_TOOLS_GIT_REVISION",
   """The git commit-hash to run tests at, or a symbolic name referring
to such a commit-hash.  Can also be a list of branches to deploy separated
by `+` ('br1+br2+br3').  In that case we will merge the branches together --
dying if there's a merge conflict -- and run tests on the resulting code.""",
   "master"

).addCronSchedule(
    // Run every Wednesday at 10am.  The time is arbitrary, but during business
    // hours so we can fix things if they break.
    '0 10 * * 3'

).apply()

def setup() {
    // Sync Webapp
    kaGit.safeSyncToOrigin("git@github.com:Khan/webapp",
        params.WEBAPP_GIT_REVISION);

    // Create a tempDir to fetch qa-tools into
    def tempDir = sh(
        script: ("mktemp -d -t qa-tools-XXXXXXXXXX"),
        returnStdout: true
    ).trim()

    try{
        // Get qa-tools
        dir(tempDir) {
            sh("git clone git@github.com:Khan/qa-tools.git")
            dir("qa-tools") {
                sh("git checkout ${params.QA_TOOLS_GIT_REVISION}")
            }
        }

        // Initialize dependencies, install required tooling, and copy 
        // qa-tools into webapp
        withSecrets() {
            dir("webapp") {
                sh("make deps")
                sh("pip install pyautogui")
                sh("cp -r ${tempDir}/qa-tools .")
            }
        }
    } finally {
        // Shouldn't need this any more
        sh("rm -rf ${tempDir}")
    }

}

def runScript() {
    withSecrets() {
        dir("webapp") {
            // sh("tools/runsmoketests.py --prod --driver chrome qa-tools/district_automation/job_tasks_smoketest.py");
            sh("tools/runsmoketests.py --prod --driver chrome-headless qa-tools/district_automation/job_tasks_smoketest.py");
        }
    }
}

def cleanUp() {
    sh("rm -rf webapp/qa-tools")
}


onWorker("ka-test-ec2", '6h') {
    notify([slack: [channel: '#bot-testing',
                    when: ['FAILURE', 'UNSTABLE']]]) {
        
        try {
            stage("Setting Up") {
                setup();
            }
            stage("Simulating Student Usage") {
                runScript();
            }
        } finally {
            stage("Cleanning Up") {
                cleanUp()
            }
        }
    }
}
