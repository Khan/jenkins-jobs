// The pipeline job for Map Districts Support

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;

new Setup(steps

).addCronSchedule(
    // Run every Wednesday at 10am.  The time is arbitrary, but during business
    // hours so we can fix things if they break.
    '0 10 * * 3'

).apply()

def runScript() {
    kaGit.safeSyncToOrigin("git@github.com:Khan/webapp",
            params.GIT_REVISION);
    kaGit.safeSyncToOrigin("git@github.com/Khan/qa-tools",
            params.GIT_REVISION);

    dir("qa-tools/district_automation") {
        sh("job_tasks_smoketest.py");
    }
}

onMaster('6h') {
    notify([slack: [channel: '#bot-testing',
                    when: ['FAILURE', 'UNSTABLE']]) {
        stage("Running script district_automation") {
            runScript();
        }
    }
}
