// Translation Analytics job that updates the translation Dashboard
// and syncs all translation stats.

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
        "LOCALE",
).apply();


def runScript() {
    kaGit.safeSyncToOrigin("git@github.com:Khan/webapp",
            params.GIT_REVISION);

    dir("webapp") {
        sh("make clean_pyc");    // in case some .py files went away
        sh("make deps");
    }

    def locale = params.LOCALE;

    lock("using-a-lot-of-memory") {
        withSecrets() {
            sh("jenkins-jobs/tap-setup.sh ${locale}");
        }
    }
}

onMaster('3h') {
    notify([slack: [channel: '#cp-eng',
                    when: ['FAILURE', 'UNSTABLE']],
            aggregator: [initiative: 'content-platform',
                         when: ['FAILURE', 'UNSTABLE']]]) {
        stage("Running script") {
            runScript();
        }
    }
}