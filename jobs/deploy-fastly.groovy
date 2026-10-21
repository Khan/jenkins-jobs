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
//
// This job is the only way to deploy the vcl services, to both test and
// prod.  It can also deploy the Fastly Compute services, but only to
// test.  To deploy a Compute service to prod, use the regular deploy
// with `sun`.

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


// Each SERVICE choice names a fastly service and the target to deploy
// it to, and its label ends in that target ("[TEST]" or "[PROD]") so
// it's clear which one you're deploying.
//
// The vcl services (in services/fastly/<service>) can only be deployed
// by this job.  They are all prod-only, except khanacademy-org-vcl,
// which also has a test service.  Fastly Compute services (in
// services/<dir>) can only be deployed to test by this job; the
// regular deploy with `sun` is what deploys them to prod.
def vclService(name, target) {
   return [name: name, target: target, dir: "services/fastly/${name}",
           compute: false];
}
def computeService(name, dir) {
   return [name: name, target: "test", dir: dir, compute: true];
}
SERVICES = [
    "khanacademy-org-vcl [TEST]": vclService("khanacademy-org-vcl", "test"),
    "khanacademy-org-vcl [PROD]": vclService("khanacademy-org-vcl", "prod"),
    "fastly-khanacademy-compute [TEST]": computeService(
        "fastly-khanacademy-compute", "services/fastly-khanacademy-compute"),
    "fastly-frontend-telemetry [TEST]": computeService(
        "fastly-frontend-telemetry", "services/fastly-frontend-telemetry"),
    "fastly-realtime [TEST]": computeService(
        "fastly-realtime", "services/fastly-realtime"),
    "fastly-security-reports [TEST]": computeService(
        "fastly-security-reports", "services/fastly-security-reports"),
    "blog [PROD]": vclService("blog", "prod"),
    "content-property [PROD]": vclService("content-property", "prod"),
    "international [PROD]": vclService("international", "prod"),
    "kasandbox [PROD]": vclService("kasandbox", "prod"),
    "kastatic [PROD]": vclService("kastatic", "prod"),
    "khan-co [PROD]": vclService("khan-co", "prod"),
    "sendgrid [PROD]": vclService("sendgrid", "prod"),
];


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
    """\
The fastly service to deploy to, marked <b>[TEST]</b> or <b>[PROD]</b>
for which copy of it you are deploying.
<ul>
  <li> <b>vcl services</b> can only be deployed by this job, for both
       test and prod.  khanacademy-org-vcl has both a test and a prod
       service; all other vcl services are <b>prod only</b>.  After
       deploying <b>khanacademy-org-vcl [TEST]</b>, test your changes at
       <a href="https://www-boxes.khanacademy.org">https://www-boxes.khanacademy.org</a>.
  <li> <b>compute services</b> (fastly-khanacademy-compute,
       fastly-frontend-telemetry, fastly-realtime and
       fastly-security-reports) can only be deployed to <b>test</b> by
       this job.  To deploy them to prod, use the regular deploy with
       <code>sun</code>.
</ul>
""",
    SERVICES.keySet()

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

SERVICE = SERVICES[params.SERVICE].name;
TARGET = SERVICES[params.SERVICE].target;
SERVICE_DIR = SERVICES[params.SERVICE].dir;
IS_COMPUTE = SERVICES[params.SERVICE].compute;

currentBuild.displayName = ("${currentBuild.displayName} " +
                            "(${TARGET} ${SERVICE})");


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
              "active-version-${TARGET}"];
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
                  exec(["make", "deploy-${TARGET}"]);
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
               exec(["make", "set-default-${TARGET}"]);
            }
         }
      }
   }
}

def notifyWithVersionInfo(oldActive, newActive) {
   def subject = "fastly-${TARGET} (${SERVICE}) is now at version ${newActive}";
   // We don't use fastly-rollback with compute@edge, we use the normal
   // `emergency-rollback` jenkins job.
   def body = SERVICE == "fastly-vcl" ? "To roll back to the previous version, use `sun: fastly-rollback ${TARGET} to ${oldActive}`": "";
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
   // (Only vcl services can get here: compute services are test-only.)
   if (TARGET == "prod") {
      subject = "Fastly service ${SERVICE} was modified.";
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
      // If we ever wanted to support this for test, we'd have
      // to modify the filename to add `-${TARGET}` to the filename
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

      stage("Installing deps") {
         installDeps();
      }

      def oldActive = _activeVersion();

      stage("Deploying") {
         if (TARGET == "prod") {
            ensureUpToDate();
         }
         deploy();
      }

      // In vcl, we can read the diff, because we upload the source code.
      // So we do this and ask for confirmation as a double-check.  For
      // compute@edge we upload a binary so there's no diff we can do.
      if (!IS_COMPUTE) {
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
