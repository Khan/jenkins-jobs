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
).addStringParam("LOCALE",
        "Locale to run TAP on",
        ""
).apply();


def runScript() {
    kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", "tap", [], true);

    dir("webapp") {
        // remove any existing database
        sh("rm -f current.sqlite");
        sh("make clean_pyc");    // in case some .py files went away
        sh("make deps");
        sh("make current.sqlite");
        sh("build/kake/build_prod_main.py genfiles/combined_template_strings/combined_template_strings.json");
    }
}

def runTapForLocaleOnly() {
    withSecrets() {
        sh("echo runTapForLocaleOnly ${params.LOCALE}");
        sh("jenkins-jobs/tap-run.sh ${params.LOCALE}");
    }
}

def runTapForLocaleAndEn() {
    withSecrets() {
        sh("echo runTapForLocaleAndEn ${params.LOCALE}");
        sh("jenkins-jobs/tap-run.sh ${params.LOCALE} ${"en"} ${"false"}");
    }
}
def runTapForLocaleAndStagedContent() {
    withSecrets() {
        sh("echo runTapForLocaleAndStagedContent ${params.LOCALE}");
        sh("jenkins-jobs/tap-run.sh ${params.LOCALE} ${params.LOCALE} ${"true"}");
    }
}

onMaster('4h') {
    notify([slack     : [channel: '#cp-eng',
                         when   : ['FAILURE', 'UNSTABLE']],
            aggregator: [initiative: 'content-platform',
                         when      : ['FAILURE', 'UNSTABLE']]]) {
        stage("Running script") {
            runScript();
        }
        stage("Parallel processing the TAP runs") {
            steps {
                parallel(
                        "firstTap": {
                            runTapForLocaleOnly();
                        },
                        "secondTap": {
                            runTapForLocaleAndEn();
                        },
                        "thirdTap": {
                            runTapForLocaleAndStagedContent();
                        }
                )
            }
        }
    }
}


