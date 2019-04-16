// This job generates topic tree json for all locales and writes the 
// generated files to GCS which are used by api '/api/v1/topictree'
// for its response.

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;

new Setup(steps

).addCronSchedule("H H * * *"

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
            sh("jenkins-jobs/run-topictree-gen.sh");
        }
    }
}

onMaster('6h') {
    notify([slack: [channel: '#cp-eng',
                    when: ['FAILURE', 'UNSTABLE']],
            aggregator: [initiative: 'content-platform',
                         when: ['FAILURE', 'UNSTABLE']]]) {
        stage("Running script") {
            runScript();
        }
    }
}

