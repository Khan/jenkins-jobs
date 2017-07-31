// Delete a non-default (znd) version of our application on App Engine.

@Library("kautils")
// Classes we use, under jenkins-tools/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-tools/vars/.  This is just for documentation.
//import vars.exec
//import vars.kaGit
//import vars.notify
//import vars.onMaster


new Setup(steps

).addStringParam(
    "ZND_NAME",
    """<b>REQUIRED</b>. The name of the znd to delete. Must exactly match the
name of an existing znd (e.g. znd-170101-sean-znd-test)""",
    ""

).apply();

currentBuild.displayName = "${currentBuild.displayName} (${params.ZND_NAME})";


def verifyZnd() {
   if (!params.ZND_NAME) {
      notify.fail("The ZND_NAME parameter is required.");
   }
   // We only allow this job to delete znd's -- we don't accidentally
   // want to delete a production instance!
   if (!params.ZND_NAME.startsWith("znd-")) {
      notify.fail("The given ZND_NAME '${params.ZND_NAME}' doesn't " +
                  "look like a proper znd name.");
   }
}


def deleteZnd() {
   onMaster('15m') {
      kaGit.safeSyncTo("git@github.com:Khan/webapp", "master");
      dir("webapp") {
         exec(["make python_deps"]);
         exec(["deploy/delete_gae_versions.py", params.ZND_NAME]);
      }
   }
}


notify([slack: [channel: '#1s-and-0s-deploys',
                sender: 'Mr Monkey',
                emoji: ':monkey_face:',
                when: ['SUCCESS', 'FAILURE', 'UNSTABLE', 'ABORTED']]]) {
   verifyZnd();
   stage("Deleting") {
      deleteZnd();
   }
}
