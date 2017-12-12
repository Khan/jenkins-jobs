// Delete a version of our application on App Engine.

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.exec
//import vars.kaGit
//import vars.notify
//import vars.withTimeout


new Setup(steps

).addStringParam(
    "VERSION",
    """<b>REQUIRED</b>. The name of the version to delete. Must exactly match
the name of an existing version (e.g. znd-170101-sean-znd-test or
170101-1200-deadbeef1234)""",
    ""

).apply();

currentBuild.displayName = "${currentBuild.displayName} (${params.VERSION})";


def verifyArgs() {
   if (!params.VERSION) {
      notify.fail("The VERSION parameter is required.");
   }
}


def deleteVersion() {
   withTimeout('15m') {
      kaGit.safeSyncTo("git@github.com:Khan/webapp", "master");
      dir("webapp") {
         sh("make python_deps");
         exec(["deploy/delete_gae_versions.py", params.VERSION]);
      }
   }
}


notify([slack: [channel: '#1s-and-0s-deploys',
                sender: 'Mr Monkey',
                emoji: ':monkey_face:',
                when: ['SUCCESS', 'FAILURE', 'UNSTABLE', 'ABORTED']],
        aggregator: [initiative: 'infrastructure',
                     when: ['SUCCESS', 'BACK TO NORMAL',
                            'FAILURE', 'ABORTED', 'UNSTABLE']],
        timeout: "30m"]) {
   verifyArgs();
   stage("Deleting") {
      deleteVersion();
   }
}
