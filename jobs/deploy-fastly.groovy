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
//import vars.withSecrets
//import vars.withTimeout
//import vars.withVirtualenv


new Setup(steps

).addStringParam(
    "GIT_REVISION",
    """<b>REQUIRED</b>. Usually: the name of a branch or commit to deploy.  If
deploying to prod, <b>this must be ahead of master</b>: use the commit from
your build (e.g. if the version is yymmdd-hhmm-ssssssssssss just use the
ssssssssssss).

Deploying to test, you can use any branch/commit.  Also possible: a tag like
phabricator/diff/&lt;id&gt; (using the latest ID from the diff's "history" tab or
<code>revisionid-to-diffid.sh D#####</code>).  Basically, this is passed to
<code>git checkout GIT_REVISION</code>.""",
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


currentBuild.displayName = ("${currentBuild.displayName} " +
                            "(${params.TARGET})");


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

def _activeVersion() {
   def cmd = [
       "make",
       "-s",
       "-C", "webapp/services/fastly-khanacademy",
        params.TARGET == "prod" ? "active-version" : "active-version-test",
   ];
   withTimeout('1m') {
      withVirtualenv.python3() {
         return exec.outputOf(cmd)
      }
   }
}

// When deploying normal webapp services we merge in master to make sure
// the latest code is running.  We should probably do that here too but
// it's a lot of machinery, so instead we just enforce we're *at* master.
def ensureUpToDate() {
    dir("webapp") {
        def master = exec.outputOf(["git", "rev-parse", "origin/master"]);
        def base = exec.outputOf(["git", "merge-base", "origin/master", "HEAD"]);
        if (master != base) {
            def head = exec.outputOf(["git", "rev-parse", "HEAD"]);
            notify.fail("You must merge master into your branch before deploying it (master is at ${master}, HEAD is at ${head}, their merge-base is ${base})");
        }
    }
}

def deploy() {
   withTimeout('15m') {
      withVirtualenv.python3() {
         withSecrets.slackAlertlibOnly() { // to report to #fastly
            dir("webapp/services/fastly-khanacademy") {
               // `make deploy` uses vt100 escape codes to color its diffs,
               // let's make sure they show up properly.
               ansiColor('xterm') {
                  sh(params.TARGET == "prod" ? "make deploy" : "make deploy-test");
               }
            }
         }
      }
   }
}

def setDefault() {
   withTimeout('5m') {
      withVirtualenv.python3() {
         withSecrets.slackAlertlibOnly() { // report to #fastly, #whats-happening
            dir("webapp/services/fastly-khanacademy") {
               sh(params.TARGET == "prod" ? "make set-default" : "make set-default-test");
            }
         }
      }
   }
}

def notifyWithVersionInfo(oldActive, newActive) {
   def subject = "fastly-${params.TARGET} is now at version ${newActive}";
   def body = "To roll back to the previous version, use `sun: fastly-rollback ${params.TARGET} to ${oldActive}`";
   def cmd = [
       "jenkins-jobs/alertlib/alert.py",
       "--slack=#fastly",
       "--chat-sender=Fastly Flamingo",
       "--icon-emoji=:flamingo:",
       "--severity=info",
       "--summary=${subject}",
   ];
   withSecrets.slackAlertlibOnly() {
      sh("echo ${exec.shellEscape(body)} | ${exec.shellEscapeList(cmd)}");
   }
}

onMaster('30m') {
   notify([slack: [channel: '#fastly',
                   sender: 'Mr Monkey',
                   emoji: ':monkey_face:',
                   when: ['BUILD START',
                          'SUCCESS', 'FAILURE', 'UNSTABLE', 'ABORTED']]]) {
      if (params.GIT_REVISION == "") {
         notify.fail("Must specify a GIT_REVISION");
      }

      stage("Installing deps") {
         installDeps();
      }

      def oldActive = _activeVersion();

      stage("Deploying") {
         if (params.TARGET == "prod") {
            ensureUpToDate();
         }
         deploy();
      }

      echo("NOTE: You may need to refresh this browser tab to see proper diff colorization");
      input("Diff looks good?");

      stage("Setting default") {
         setDefault();
      }

      def newActive = _activeVersion();
      notifyWithVersionInfo(oldActive, newActive);
   }
}
