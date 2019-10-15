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

).addStringParam(
    "GIT_REVISION",
    """<b>REQUIRED</b>. Usually: the name of a branch to deploy.  Also
possible: a commit-sha1 to deploy. Basically, this is passed to
<code>git checkout GIT_REVISION</code>.
If you intend to set default in production do not forget to check
<code>SET_DEFAULT</code>.""",
    "master"

).addBooleanParam(
    "FLEX_DEPLOY",
    """If set, deploy to an Appengine Flex instance instead of
Appengine Standard.  If you set this to true, you should set
SET_DEFAULT to false -- see the react-render-server README for
details.""",
    false

).addBooleanParam(
    "SET_DEFAULT",
    """If set, set the new version as default.
Otherwise, this is left to the deployer to do manually.""",
    false

).addBooleanParam(
    "PRIME",
    """If set (the default), prime the version after deploying.
TODO(jlfwong): Prime by loading most recent corelibs/shared/etc into cache""",
    true

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
                             params.GIT_REVISION);

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
         withEnv(["DOCKER=${params.FLEX_DEPLOY ? "1" : ""}"]) {
            sh("sh -ex ./deploy.sh");
         }
      }
   }
}

def setDefault() {
   withTimeout('90m') {
      dir("react-render-server") {
         withEnv(["DOCKER=${params.FLEX_DEPLOY ? "1" : ""}"]) {
            sh("sh -ex ./set_default.sh");
         }
      }
   }
}


onMaster('5h') {
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
}
