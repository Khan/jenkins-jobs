// Pipeline job that downloads a tar file for sync service.

@Library("kautils")
import org.khanacademy.Setup;

new Setup(steps
).addStringParam(
        "LOCALE",
        """Set this to a single locale, e.g.,
\"fr\".""",
        ""
).addStringParam(
        "ARCHIVEID",
        """Set this to an archive ID  that
need to be downloaded.""",
        ""
).apply();


def runScript() {
    withTimeout('1h') {
        def locale = params.LOCALE;
        def archiveId = params.ARCHIVEID

        currentBuild.displayName = ("${currentBuild.displayName} " +
                "(${locale})" + "(${archiveId})");

        sh("jenkins-jobs/download-file.sh \"${locale}\" \"${archiveId}\"")
    }
}

onMaster('1h') {
    notify([slack     : [channel  : '#cp-eng',
                         sender   : 'I18N Imp',
                         emoji    : ':smiling_imp:', emojiOnFailure: ':imp:',
                         extraText: "@cp-support",
                         when     : ['FAILURE', 'UNSTABLE']]]) {

        runScript();
    }
}
