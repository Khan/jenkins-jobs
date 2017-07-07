// Pipeline job to
// 1) Download data from youtube from our i18n partner youtube accounts
// 2) Create intl/translations/videos_*.json needed for lite pages

@Library("kautils")
// Classes we use, under jenkins-tools/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-tools/vars/.  This is just for documentation.
//import vars.exec
//import vars.kaGit
//import vars.notify
//import vars.onMaster
//import vars.withSecrets


new Setup(steps

).addCronSchedule("H 5 * * 6,2"

).apply();


def updateRepo() {
   onMaster('1h') {
      kaGit.safeSyncTo("git@github.com:Khan/webapp", "master");

      // We do our work in the 'translations' branch.
      kaGit.safePullInBranch("webapp", "translations");

      // ...which we want to make sure is up-to-date with master.
      kaGit.safeMergeFromMaster("webapp", "translations");

      // We also make sure the intl/translations sub-repo is up to date.
      kaGit.safePull("webapp/intl/translations");

      dir("webapp") {
         sh("make python_deps");
      }
   }
}


def runAndCommit() {
   onMaster('22h') {
      def GIT_SHA1 = null;
      withSecrets() {
         dir("webapp") {
            exec(["tools/update_i18n_lite_videos.py", "intl/translations"])
            GIT_SHA1 = exec.outputOf(["git", "rev-parse", "HEAD"]);
         }
      }
      kaGit.safeCommitAndPushSubmodule(
         "webapp", "intl/translations",
         ["-m", "Automatic update of video_*.json",
          "-m", "(at webapp commit ${GIT_SHA1})"]);
   }
}


notify([slack: [channel: '#i18n',
                sender: 'I18N Imp',
                emoji: ':smiling_imp:', emojiOnFailure: ':imp:',
                when: ['SUCCESS', 'FAILURE', 'UNSTABLE', 'ABORTED']],
        email: [to: 'jenkins-admin+builds, james',
                when: ['BACK TO NORMAL', 'FAILURE', 'UNSTABLE']],
        aggregator: [initiative: 'infrastructure',
                     when: ['BACK TO NORMAL', 'FAILURE', 'UNSTABLE']]]) {
   stage("Updating webapp repo") {
      updateRepo();
   }
   stage("Updating lite videos") {
      runAndCommit();
   }
}
