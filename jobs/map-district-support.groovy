// The pipeline job for Map Districts Support

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.onMaster
//import vars.notify
//import vars.kaGit


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

def runScript() {
    kaGit.safeSyncToOrigin("git@github.com:Khan/webapp",
            params.WEBAPP_GIT_REVISION);
    kaGit.safeSyncToOrigin("git@github.com:Khan/qa-tools",
            params.QA_TOOLS_GIT_REVISION);

    dir("qa-tools/district_automation") {
        sh("pwd");
        sh("ls -la");
        sh("python job_tasks_smoketest.py");
    }
}

onMaster('6h') {
    notify([slack: [channel: '#bot-testing',
                    when: ['FAILURE', 'UNSTABLE']]]) {
        stage("Running script district_automation") {
            runScript();
        }
    }
}
