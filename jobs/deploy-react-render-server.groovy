// Groovy script to deploy to react-render-dot-khanacademy.appspot.com
// from master on https://github.com/Khan/react-render-server.

@Library("kautils")
// Classes we use, under jenkins-tools/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-tools/vars/.  This is just for documentation.
//import vars.clean
//import vars.kaGit
//import vars.notify
//import vars.onMaster


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

).addBooleanParam(
    "SET_DEFAULT",
    """If set (the default), set the new version as default.
Otherwise, this is left to the deployer to do manually.""",
    true

).addBooleanParam(
    "PRIME",
    """If set, prime the version after deploying.
TODO(jlfwong): Prime by loading most recent corelibs/shared/etc into cache""",
    true

).apply();


def installDeps() {
   onMaster('15m') {
      kaGit.safeSyncTo("git@github.com:Khan/react-render-server", "master");

      dir("react-render-server") {
         clean(params.CLEAN);

         // The secret is installed into the workspace when we run the
         // jenkins setup script (in Khan/aws-config), but we need the
         // secret to be in the working directory of the repository so that
         // it gets built into the docker container.
         sh("cp -a ../secret secret");
         sh("cp -a ../hostedgraphite.api_key hostedgraphite.api_key");

         sh("npm install");
      }
   }
}


def deploy() {
   onMaster('90m') {
      dir("react-render-server") {
         sh("sh -x ./deploy.sh");
      }
   }
}

def setDefault() {
   onMaster('90m') {
      dir("react-render-server") {
         sh("sh -x ./set_default.sh");
      }
   }
}


notify([slack: [channel: '#1s-and-0s-deploys',
                sender: 'Mr Monkey',
                emoji: ':monkey_face:',
                when: ['BUILD START',
                       'SUCCESS', 'FAILURE', 'UNSTABLE', 'ABORTED']],
        aggregator: [initiative: 'infrastructure',
                     when: ['SUCCESS', 'BACK TO NORMAL',
                            'FAILURE', 'ABORTED', 'UNSTABLE']]]) {
   stage("Installing deps") {
      installDeps();
   }
   stage("Deploying") {
      deploy();
   }
   if (params.SET_DEFAULT) {
      stage("Setting default") {
         setDefault();
      }
   }
}
