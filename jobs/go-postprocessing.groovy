// Pipeline job that downloads a tar file for sync service.

@Library("kautils")
import org.khanacademy.Setup;

new Setup(steps

).addCronSchedule("H/5 * * * *"

).addStringParam(
        "LOCALE",
        """Set this to a single locale, e.g.,
\"fr\".""",
        ""
).addStringParam(
        "DOWNLOADID",
        """Set this to a download ID  that
need to be downloaded.""",
        ""
).apply();


def runScript() {
    withTimeout('1h') {
        def locale = params.LOCALE;
        def downloadId = params.DOWNLOADID

        currentBuild.displayName = ("${currentBuild.displayName} " +
                "(${locale})" + "(${downloadId})");

        sh("jenkins-jobs/download-file.sh ${locale} ${downloadId}")
    }
}

onMaster('1h') {
    notify([slack     : [channel  : '#cp-eng',
                         sender   : 'I18N Imp',
                         emoji    : ':smiling_imp:', emojiOnFailure: ':imp:',
                         extraText: "@cp-support",
                         when     : ['FAILURE', 'UNSTABLE']],
            aggregator: [initiative: 'content-platform',
                         when      : ['SUCCESS', 'BACK TO NORMAL',
                                      'FAILURE', 'ABORTED', 'UNSTABLE']]]) {

        runScript();
    }
}
