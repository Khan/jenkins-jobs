// Hackathon project to run a job that pushes
// translation stats to bigquery and modeanalytics
// for translators to keep track of progress

@Library("kautils")
import org.khanacademy.Setup;

new Setup(steps

).addCronSchedule("H/1 * * * *"

).addStringParam(
        "LOCALE",
).apply();


def runScript() {
    def locale = params.LOCALE;

    if (!locale) {
        // Meaning this is a scheduled job that needs to run on all locales
        // This could be copied from across AUTOMATICALLY_UPDATED_LOCALES
        def list = ['bg', 'cs', 'de', 'es', 'fr', 'gu', 'hi', 'hy', 'id', 'ja', 'ka', 'ko',
                    'nl', 'pt', 'pt-pt', 'sr', 'sv', 'ta', 'tr', 'zh-hans'];
        for (item in list) {
            println(item);
            withSecrets() {
                sh("jenkins-jobs/run_translation_stats.sh ${item}");
            }
        }
    } else {
        lock("using-a-lot-of-memory") {
            withSecrets() {
                sh("jenkins-jobs/run_translation_stats.sh ${locale}");
            }
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