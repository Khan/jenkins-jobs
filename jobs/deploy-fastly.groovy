// Groovy script to deploy our custom vcl to fastly.  This script
// support two modes: deploying to the test service (accessible via
// www-boxes.khanacademy.org) and deploying to the live service.  The
// first is like a znd, the second is like a standard deploy.
//
// This script does *not* merge your fastly changes in with master.
// You need to do a regular deploy for that.  So typically you
// will do this step as part of a regular deploy, like so:
//
// 1. Run this script to deploy to the test domain
//    (https://www-boxes.khanacademy.org)
// 2. Do a regular deploy that includes your changes to
//    services/fastly-khanacademy
// 3. When the regular deploy says "time to set default", run this
//    jenkins job to deploy to prod.  Make sure to follow the console
//    output so you can click on the "diff looks good" button
// 4. Once you've verified everything is working ok here, set-default
//    for your regular deploy

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
    """<b>REQUIRED</b>. Usually: the name of a branch to deploy.  Also possible:
a commit-sha1 to deploy, or a tag like phabricator/diff/<id> (using the latest ID
from the diff's "history" tab or <code>revisionid-to-diffid.sh D#####</code>).
Basically, this is passed to <code>git checkout GIT_REVISION</code>.""",
    ""

).addChoiceParam(
    "TARGET",
    """\
<ul>
  <li> <b>test</b>: https://www-boxes.khanacademy.org
  <li> <b>prod</b>: https://www.khanacademy.org
</ul>
""",
    ["test", "prod"]

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
      kaGit.safeSyncToOrigin("git@github.com:Khan/webapp",
                             params.GIT_REVISION);

      dir("webapp") {
         clean(params.CLEAN);
         dir("services/fastly-khanacademy") {
             sh("make deps");
         }
      }
   }
}


def deploy() {
   withTimeout('15m') {
      dir("webapp/services/fastly-khanacademy") {
         // `make deploy` uses vt100 escape codes to color its diffs,
         // let's make sure they show up properly.
         ansiColor('xterm') {
            sh(params.TARGET == "prod" ? "make deploy" : "make deploy-test");
         }
      }
   }
}

def setDefault() {
   withTimeout('5m') {
      dir("webapp/services/fastly-khanacademy") {
         sh(params.TARGET == "prod" ? "make set-default" : "make set-default-test");
      }
   }
}


onMaster('30m') {
   notify([slack: [channel: '#fastly',
                   sender: 'Mr Monkey',
                   emoji: ':monkey_face:',
                   when: ['BUILD START',
                          'SUCCESS', 'FAILURE', 'UNSTABLE', 'ABORTED']],
           aggregator: [initiative: 'infrastructure',
                        when: ['SUCCESS', 'BACK TO NORMAL',
                               'FAILURE', 'ABORTED', 'UNSTABLE']]]) {
      if (params.GIT_REVISION == "") {
         notify.fail("Must specify a GIT_REVISION");
      }

      stage("Installing deps") {
         installDeps();
      }

      stage("Deploying") {
         deploy();
      }

      echo("NOTE: You may need to refresh this browser tab to see proper diff colorization");
      input("Diff looks good?");

      stage("Setting default") {
         setDefault();
      }
   }
}
