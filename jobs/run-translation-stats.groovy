// Jenkins job that pushes
// translation stats to bigquery and modeanalytics
// for translators to keep track of progress

@Library("kautils")
import org.khanacademy.Setup;

new Setup(steps

).addStringParam(
        "LOCALE",
        """Set this to a single locale, e.g.,
\"fr\".""",
        ""
).apply();

def runScript() {
    def locale = params.LOCALE;

    kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", "master");
    dir("webapp") {
       sh("make -B deps");  // force a remake of all deps all the time
    }

    if (!locale) {
        // Meaning this is a scheduled job that needs to run on all locales
        // This could be copied from across AUTOMATICALLY_UPDATED_LOCALES
        def list = ['bg', 'cs', 'de', 'es', 'fr', 'gu', 'hi', 'hy', 'id', 'ja', 'ka', 'ko',
                    'nl', 'pt', 'pt-pt', 'sr', 'sv', 'ta', 'tr', 'zh-hans'];
        for (item in list) {
            println(item);
            sh("jenkins-jobs/run_translation_stats.sh ${item}");

        }
    } else {
        sh("jenkins-jobs/run_translation_stats.sh ${locale}");
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
