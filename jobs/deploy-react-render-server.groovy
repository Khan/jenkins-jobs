// Groovy script to deploy to react-render-dot-khanacademy.appspot.com
// from master on https://github.com/Khan/react-render-server.

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.clean
//import vars.kaGit
//import vars.notify
//import vars.withTimeout


new Setup(steps

).addBooleanParam(
    "SKIP_TESTS",
    """Do not run tests before deploying.
TODO(jlfwong): Make this actually skip tests by modifying
https://github.com/Khan/react-render-server/blob/master/deploy.sh""",
    false

).addChoiceParam(
    "CLEAN",
    """\
<ul>
  <li> <b>none</b>: Don't clean at all
  <li> <b>all</b>: Full clean that results in a pristine working copy
</ul>
""",
    ["none", "all"]

).apply();


def installDeps() {
   withTimeout('15m') {
      kaGit.safeSyncToOrigin("git@github.com:Khan/react-render-server",
                             "master");

      dir("react-render-server") {
         clean(params.CLEAN);

         // The secret is installed above the workspace when we run the
         // jenkins setup script (in Khan/aws-config), but we need the
         // secret to be in the working directory of the repository so that
         // it gets built into the docker container.
         sh("cp -a ../../secret secret");
         sh("cp -a ../../hostedgraphite.api_key hostedgraphite.api_key");

         sh("npm install --no-save");
      }
   }
}


def deploy() {
   withTimeout('90m') {
      dir("react-render-server") {
         sh("sh -ex ./deploy.sh");
      }
   }
}



onMaster('5h') {
   notify([slack: [channel: "#bot-testing",
                  sender: 'Taskqueue Totoro',
                  emoji: ':totoro:',
                  when: ['FAILURE', 'UNSTABLE', 'ABORTED']]]) {
      stage("Installing deps") {
         installDeps();
      }
      stage("Deploying") {
         deploy();
      }
   }
}
