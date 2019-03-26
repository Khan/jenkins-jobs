// Pipeline job that creates current.sqlite from the sync snapshot
// (on gcs) and uploads the resulting current.sqlite to gcs.

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.kaGit
//import vars.notify
//import vars.onWorker
//import vars.withSecrets

new Setup(steps

).addCronSchedule("H/1 * * * *"

).addStringParam(
        "LOCALES",
        """Set this to a whitespace-separate list of locales process, e.g.,
\"fr es pt\". """,
        ""

).apply();


def runScript() {
    kaGit.safeSyncToOrigin("git@github.com:Khan/webapp",
            params.GIT_REVISION);

    dir("webapp") {
        sh("make clean_pyc");    // in case some .py files went away
        sh("make deps");
    }

    lock("using-a-lot-of-memory") {
        withSecrets() {
            withEnv(
                    ["CURRENT_SQLITE_BUCKET=${params.CURRENT_SQLITE_BUCKET}",
                     "SNAPSHOT_NAMES=${params.SNAPSHOT_NAMES}"]) {
                sh("jenkins-jobs/tap-setup.sh ${locale}");
            }
        }
    }
}


// We run on a special worker machine because this job uses so much
// memory.
onMaster('3h') {
    notify([slack: [channel: '#cp-eng',
                    when: ['SUCCESS', 'FAILURE', 'ABORTED', 'UNSTABLE']],
            aggregator: [initiative: 'infrastructure',
                         when: ['SUCCESS', 'BACK TO NORMAL',
                                'FAILURE', 'ABORTED', 'UNSTABLE']]]) {
        stage("Running script") {
            runScript();
        }
    }
}