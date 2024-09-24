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
  <li> <b>staging</b>: TODO [compute@edge only]
</ul>
""",
    ["test", "prod", "staging"]

).addChoiceParam(
    "SERVICE",
    """\
<ul>
  <li> <b>vcl</b>
  <li> <b>compute@edge</b>
</ul>
""",
    ["vcl", "compute@edge"]

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

SERVICE_DIR = params.SERVICE == "vcl" ? "services/fastly-khanacademy" : "services/fastly-khanacademy-compute";


currentBuild.displayName = ("${currentBuild.displayName} " +
                            "(${params.TARGET})");


def installDeps() {
   withTimeout('15m') {
      kaGit.safeSyncToOrigin("git@github.com:Khan/webapp",
                             params.GIT_REVISION);

      dir("webapp") {
         clean(params.CLEAN);
         dir(SERVICE_DIR) {
             sh("make deps");
         }
      }
   }
}

def _activeVersion() {
   def cmd = ["make", "-s", "-C", "webapp/${SERVICE_DIR}",
              "active-version-${params.TARGET}"];
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
            dir("webapp/${SERVICE_DIR}") {
               // `make deploy` uses vt100 escape codes to color its diffs,
               // let's make sure they show up properly.
               ansiColor('xterm') {
                  exec(["make", "deploy-${params.TARGET}"]);
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
            dir("webapp/${SERVICE_DIR}") {
               exec(["make", "set-default-${params.TARGET}"]);
            }
         }
      }
   }
}

def notifyWithVersionInfo(oldActive, newActive) {
   def subject = "fastly-${params.TARGET} (${params.SERVICE}) is now at version ${newActive}";
   // We don't use fastly-rollback with compute@edge, we use the normal
   // `emergency-rollback` jenkins job.
   def body = params.SERVICE == "vcl" ? "To roll back to the previous version, use `sun: fastly-rollback ${params.TARGET} to ${oldActive}`": "";
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

def deployToVcl() {
   stage("Deploying") {
      if (params.TARGET != "test") {
         ensureUpToDate();
      }
      deploy();
   }

   echo("NOTE: You may need to refresh this browser tab to see proper diff colorization");
   input("Diff looks good?");

   stage("Setting default") {
      setDefault();
   }
}

def deployToCompute() {
   // Unlike vcl, we don't have a way to separate "deploy" from "set-default"
   // in compute@edge.  So the way we do things is we just do two separate
   // deploys -- that is, two separate deploy-fastly jenkins jobs -- one
   // to staging and one to prod.
   stage("Deploying") {
      if (params.TARGET != "test") {
         ensureUpToDate();
      }
      deploy();
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

      // In vcl, you set-default in the same jenkins job.  But compute@edge
      // doesn't have a set-default mode, so the way we do things is to have
      // two different jenkins jobs, one to staging and one to prod.  That
      // means we skip the next part in compute@edge.
      if (params.SERVICE == "vcl") {
         echo("NOTE: You may need to refresh this browser tab to see proper diff colorization");
         input("Diff looks good?");

         stage("Setting default") {
            setDefault();
         }
      }

      def newActive = _activeVersion();
      notifyWithVersionInfo(oldActive, newActive);
   }
}
