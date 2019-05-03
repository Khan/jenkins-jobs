// Translation Analytics job that updates the translation Dashboard
// and syncs all translation stats.

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.kaGit
//import vars.notify
//import vars.withSecrets

new Setup(steps
).addCronSchedule("H/1 * * * *"
).addStringParam("LOCALE",
        "Locale to run TAP on",
        ""
).apply();


def runScript() {
    kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", "tap", [], true);

    dir("webapp") {
        sh("make clean_pyc");    // in case some .py files went away
        sh("make deps");
    }

    lock("using-a-lot-of-memory") {
        withSecrets() {
            sh("jenkins-jobs/tap-run.sh ${params.LOCALE}");
        }
    }
}

onMaster('1h') {
    notify([slack: [channel: '#cp-eng',
                    when: ['FAILURE', 'UNSTABLE']],
            aggregator: [initiative: 'content-platform',
                         when: ['FAILURE', 'UNSTABLE']]]) {
        stage("Running script") {
            runScript();
        }
    }
}
