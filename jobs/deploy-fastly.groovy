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

Deploying to test, you can use any branch/commit.""",
    ""

).addChoiceParam(
    "SERVICE",
    """The fastly service to deploy to.""",
    ["khanacademy-org-vcl", "khanacademy-org-compute",
     "blog", "content-property", "international", "kasandbox", "kastatic",
     "khan-co", "sendgrid"],

).addChoiceParam(
    "TARGET",
    """\
<ul>
  <li> <b>test</b>: khanacademy-org-vcl and khanacademy-org-compute <b>only</b>
  <li> <b>staging</b>: khanacademy-org-compute <b>only</b>
  <li> <b>prod</b>: all services
</ul>
""",
    ["test", "staging", "prod"]

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

SERVICE_DIR = [
    // The special cases (that aren't "services/fastly/<service>") go here.
    "khanacademy-org-compute": "services/fastly-khanacademy-compute",
].get(params.SERVICE, "services/fastly/${params.SERVICE}")

currentBuild.displayName = ("${currentBuild.displayName} " +
                            "(${params.TARGET} ${params.SERVICE})");


def installDeps() {
   withTimeout('60m') {
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
   def body = params.SERVICE == "fastly-vcl" ? "To roll back to the previous version, use `sun: fastly-rollback ${params.TARGET} to ${oldActive}`": "";
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

   // Let's tell #whats-happening as well, at least for prod deploys.
   // But we don't alert for fastly-compute, since that is deployed
   // automatically as part of the normal deploy process, and gets
   // alerted on #whats-happening via that mechanism.
   if (params.TARGET == "prod" && params.SERVICE != "khanacademy-org-compute") {
      subject = "Fastly service ${params.SERVICE} was modified.";
      body = "New version: ${newActive}";
      cmd = [
          "jenkins-jobs/alertlib/alert.py",
          "--slack=#whats-happening",
          "--chat-sender=fastly",
          "--icon-emoji=:fastly:",
          "--severity=info",
          "--summary=${subject}",
      ];
      withSecrets.slackAlertlibOnly() {
         sh("echo ${exec.shellEscape(body)} | ${exec.shellEscapeList(cmd)}");
      }

      // Let's also keep track of what deploys we handled, to make it
      // easier to find changes to fastly that did not go through this
      // script.  Note that `>>` uses a buffered write so is safe even
      // if two deploy scripts are running in parallel.  However, this
      // does depend on this job only having one workspace, so we don't
      // have to worry about jobs running in parallel in any case.
      // (and note we do not enable concurrent builds in our setup call.)

      // For this we need the id of the service.  This is in the yaml file.
      // Because we know we are prod-only, we know the yaml file to use is
      // <service-dir-basename>.yaml (and not <basename>-test.yaml).
      // If we ever wanted to support this for staging or test, we'd have
      // to modify the filename to add `-${params.TARGET}` to the filename
      // below.
      dir("webapp/${SERVICE_DIR}") {
         def serviceId = sh(
            script: "grep service_id: \"`pwd | xargs basename`\".yaml | cut -d: -f2 | tr -cd 0-9A-Za-z",
            returnStdout: true,
         ).trim();
         // sync-start:fastly-deploys-file fastly_notifier.py
         sh("echo '${serviceId}:${newActive}' >> '${env.WORKSPACE}/deployed_versions.txt'");
         // sync-end:fastly-deploys-file
      }
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

      if (params.TARGET == "test" &&
          params.SERVICE != "khanacademy-org-vcl" &&
          params.SERVICE != "khanacademy-org-compute") {
         notify.fail("'test' mode is only supported for khanacademy-org services");
      }
      if (params.TARGET == "staging" &&
          params.SERVICE != "khanacademy-org-compute") {
         notify.fail("'test' mode is only supported for khanacademy-org-compute");
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

      // In vcl, we can read the diff, because we upload the source code.
      // So we do this and ask for confirmation as a double-check.  For
      // compute@edge we upload a binary so there's no diff we can do.
      if (params.SERVICE != "khanacademy-org-compute") {
         echo("NOTE: You may need to refresh this browser tab to see proper diff colorization");
         input("Diff looks good?");
      }

      stage("Setting default") {
         setDefault();
      }

      def newActive = _activeVersion();
      notifyWithVersionInfo(oldActive, newActive);
   }
}
