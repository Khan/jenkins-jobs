// Pipeline job to download captions from Amara, then upload them to YouTube.

@Library("kautils")
// Classes we use, under jenkins-tools/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-tools/vars/.  This is just for documentation.
//import vars.exec
//import vars.kaGit
//import vars.notify
//import vars.onMaster


new Setup(steps

).addCronSchedule("H 3 * * 3,6"

).addStringParam(
    "SKIP_TO_STAGE",
    """Skip some stages of the sync.
<ul>
  <li>Stage 0 = Download from dropbox. </li>
  <li>Stage 1 = Move professional translations into incoming folder.</li>
  <li>Stage 2 = Download from Amara.</li>
  <li>Stage 3 = Upload to Youtube.</li>
  <li>Stage 4 = Upload to production.</li>
</ul>""",
    ""

).apply();


def runScript() {
   onMaster('23h') {
      kaGit.safeSyncTo("git@github.com:Khan/webapp", "master");
      // sync-captions.sh calls webapp/tools/dropbox_sync_source.py
      // which tries to import `dropbox`.  This is not listed in
      // requirements.txt.  Not sure if it should be, but I just
      // install it here.
      dir("webapp") {
         sh("pip install dropbox==1.6");
         // The cert file that comes with this super-old dropbox isn't
         // up to date.  Use the more up-to-date system cert file.
         // Sadly, this seems to be hard-coded into dropbox.py
         dir dropboxDir = sh(
            script: "python -c 'import dropbox, os; print os.path.dirname(dropbox.__file__)'",
            returnStdout: true).trim();
         dir certFile = "${exec.shellEscape(dropboxDir)}/trusted-certs.crt";
         // This 'yes no' and '-i' does the mv only if the destination file
         // does not already exist.
         sh("yes no | mv -i ${certFile} ${certFile}.bak");
         sh("cp -f /etc/ssl/certs/ca-certificates.crt ${certFile}");
      }

      withEnv(["SKIP_TO_STAGE=${params.SKIP_TO_STAGE}"]) {
         sh("jenkins-tools/sync-captions.sh");
      }
   }
}


notify([slack: [channel: '#i18n',
                sender: 'Mr Monkey',
                emoji: ':monkey_face:',
                when: ['SUCCESS', 'FAILURE', 'UNSTABLE', 'ABORTED']],
        email: [to: 'jenkins-admin+builds, james',
                when: ['BACK TO NORMAL', 'FAILURE', 'UNSTABLE']]]) {
   stage("Syncing captions") {
      runScript();
   }
}
