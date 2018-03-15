// Pipeline job to
// 1) Download data from youtube from our i18n partner youtube accounts
// 2) Create intl/translations/videos_*.json needed for lite pages

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.exec
//import vars.kaGit
//import vars.notify
//import vars.withTimeout
//import vars.withSecrets


new Setup(steps

).addCronSchedule("H 5 * * 6,2"

).apply();


def updateRepo() {
   withTimeout('1h') {
      kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", "master");

      // We do our work in the 'translations' branch.
      kaGit.safePullInBranch("webapp", "translations");

      // ...which we want to make sure is up-to-date with master.
      kaGit.safeMergeFromMaster("webapp", "translations");

      // We also make sure the intl/translations sub-repo is up to date.
      kaGit.safePull("webapp/intl/translations");

      dir("webapp") {
         sh("make clean_pyc");    // in case some .py files went away
         sh("make python_deps");
      }
   }
}


def runAndCommit() {
   withTimeout('22h') {
      def GIT_SHA1 = null;
      withSecrets() {
         dir("webapp") {
            exec(["tools/update_i18n_lite_videos.py", "intl/translations"])
            GIT_SHA1 = exec.outputOf(["git", "rev-parse", "HEAD"]);
         }
      }
      dir("webapp/intl/translations") {
         exec(["git", "add", "videos_*.json"]);
      }
      kaGit.safeCommitAndPushSubmodule(
         "webapp", "intl/translations",
         ["-m", "Automatic update of videos_*.json",
          "-m", "(at webapp commit ${GIT_SHA1})",
          "videos_*.json"]);
   }
}


notify([slack: [channel: '#cp-eng',
                sender: 'I18N Imp',
                emoji: ':smiling_imp:', emojiOnFailure: ':imp:',
                extraText: "@cp-support",
                when: ['FAILURE', 'UNSTABLE', 'ABORTED']],
        email: [to: 'jenkins-admin+builds',
                when: ['BACK TO NORMAL', 'FAILURE', 'UNSTABLE']],
        aggregator: [initiative: 'infrastructure',
                     when: ['SUCCESS', 'BACK TO NORMAL',
                            'FAILURE', 'ABORTED', 'UNSTABLE']],
        timeout: "23h"]) {
   stage("Updating webapp repo") {
      updateRepo();
   }
   stage("Updating lite videos") {
      runAndCommit();
   }
}
