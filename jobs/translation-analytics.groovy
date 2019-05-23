// Translation Analytics job that updates the translation Dashboard
// and syncs all translation stats.

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.kaGit
//import vars.notify
//import vars.withSecrets

// A list of locales that we run LTT update for, which means that
// fully-translated revisions on the English tree will be imported into the
// locale's stage. This is currently opt-in for each locale.
// TODO(tom) Do we need a parameter to force updating a locale that isn't on the
// list?
AUTOMATICALLY_UPDATED_LOCALES = [
    'bg', 'cs', 'de', 'es', 'fr', 'gu', 'hi', 'hy', 'id', 'ja', 'ka', 'ko',
    'nl', 'pt', 'pt-pt', 'sr', 'sv', 'ta', 'tr', 'zh-hans', 'lol'
]

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
        // We need one file that is generated at build time. We can't run with
        // USE_PROD_FILES disabled as we prefer to get the most recent translation files
        // from GCS, so just build the one file manually to start with.
        sh("build/kake/build_prod_main.py genfiles/combined_template_strings/combined_template_strings.json");
    }
}

def runTapForLocaleOnly() {
    withSecrets() {
        sh("echo runTapForLocaleOnly ${params.LOCALE}");
        sh("jenkins-jobs/tap-run.sh ${params.LOCALE} ${params.LOCALE} False");
    }
}

def runTapForLocaleAndEn() {
    withSecrets() {
        sh("echo runTapForLocaleAndEn ${params.LOCALE} ");
        sh("jenkins-jobs/tap-run.sh ${params.LOCALE} en False");
    }
}
def runLTTUpdate() {
    withSecrets() {
        sh("echo runLTTUpdate ${params.LOCALE}");
        sh("jenkins-jobs/ltt-update.sh ${params.LOCALE}");
    }
}
def runTapForLocaleAndStagedContent() {
    withSecrets() {
        sh("echo runTapForLocaleAndStagedContent ${params.LOCALE}");
        sh("jenkins-jobs/tap-run.sh ${params.LOCALE} ${params.LOCALE} True");
    }
}

onMaster('4h') {
    notify([slack     : [channel: '#cp-eng',
                         when   : ['FAILURE', 'UNSTABLE']],
            aggregator: [initiative: 'content-platform',
                         when      : ['FAILURE', 'UNSTABLE']]]) {

        currentBuild.displayName = "${currentBuild.displayName} (${params.LOCALE})";

        stage("Initial setup") {
            runScript();
        }
        stage("Published TAP") {
            parallel(
                    "LocaleFMS": {
                        runTapForLocaleOnly();
                    },
                    "EnglishFMS": {
                        runTapForLocaleAndEn();
                    }
            )
        }
        // LTT update depends on the locale/English TAP and is in turn
        // writes to the locale's stage, therefore we have to run the stage
        // TAP after this task runs.
        stage("LTT Update") {
            if (AUTOMATICALLY_UPDATED_LOCALES.contains(params.LOCALE)) {
                runLTTUpdate()
            }
        }
        stage("Stage TAP") {
            runTapForLocaleAndStagedContent();
        }
    }
}
