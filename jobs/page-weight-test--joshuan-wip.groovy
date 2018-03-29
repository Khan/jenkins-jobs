// NOTE: DO NOT USE -- this allows Joshua Netterfield to actually test the
// Jenkins job while working on it, since there isn't a practical way of testing
// it locally. It's not ready for use yet.

// The pipeline job for calculating page weight.

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
import java.net.URLEncoder;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.exec
//import vars.kaGit
//import vars.notify
//import vars.withTimeout
//import vars.onTestWorker
//import vars.withSecrets


new Setup(steps

).allowConcurrentBuilds(

).addStringParam(
   "GIT_REVISION_BASE",
   """A commit-ish to consider as the baseline. If you are testing a Phabricator
diff, this should be of the form phabricator/base/xxxxxx.""",
   ""

).addStringParam(
   "GIT_REVISION_DIFF",
   """A commit-ish to compare against the baseline. If you are testing a Phabricator
diff, this should be of the form phabricator/diff/xxxxxx.""",
   ""

).addStringParam(
   "BUILD_PHID",
   "If invoked by Phabricator, the PHID of this build.",
   ""

).addStringParam(
   "REVISION_ID",
   "If invoked by Phabricator, the ID of the revision being tested (e.g., D41409).",
   ""

).apply();

currentBuild.displayName = ("${currentBuild.displayName} " +
      "(${params.GIT_REVISION_BASE} vs ${params.GIT_REVISION_DIFF})");


// We set these to real values first thing below; but we do it within
// the notify() so if there's an error setting them we notify on slack.
GIT_SHA_BASE = null;
GIT_SHA_DIFF = null;

def initializeGlobals() {
   // We want to make sure all nodes below work at the same sha1,
   // so we resolve our input commit to a sha1 right away.
   // Note that this is not needed for Phabricator diffs since those are
   // immutable, but it's still good practice.
   GIT_SHA_BASE = kaGit.resolveCommitish("git@github.com:Khan/webapp",
         params.GIT_REVISION_BASE);

   GIT_SHA_DIFF = kaGit.resolveCommitish("git@github.com:Khan/webapp",
         params.GIT_REVISION_DIFF);
}


def _setupWebapp() {
   kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", "master");
   dir("webapp") {
      sh("make clean_pyc");
      sh("make deps");

      // Update current.sqlite if not present, or once per week
      sh("""\
         #!/bin/bash
         if [ -e ./genfiles/current_sqlite_updated ]; then
            update_time=\$(cat ./genfiles/current_sqlite_updated);
         else
            update_time=0;
         fi
         current_time=\$(date +%s)
         if (( update_time < ( current_time - ( 60 * 60 * 24 * 7 ) ) )); then
            make current.sqlite
            date +%s > ./genfiles/current_sqlite_updated
         else
            echo "current.sqlite is new enough. Not updating."
         fi""".stripIndent());
   }
}

def _submitPhabricatorComment(comment) {
   // See https://phabricator.khanacademy.org/conduit/method/differential.revision.edit

   def conduitToken = readFile(
      "${env.HOME}/page-weight-phabricator-conduit-token.secret").trim();

   def message = groovy.json.JsonOutput.toJson([
      "__conduit__": [
         "token": conduitToken,
      ],
      "objectIdentifier": params.REVISION_ID,
      "transactions": [
         [
            "type": "comment",
            "value": comment,
         ],
      ],
   ]);

   def body = "params=${java.net.URLEncoder.encode(message)}"

   def response = httpRequest acceptType: 'APPLICATION_JSON', contentType: 'APPLICATION_JSON', httpMode: 'PATCH', requestBody: body, url: "https://phabricator.khanacademy.org/api/differential.revision.edit"

   assert response.status == 200;
}

def _submitPhabricatorHarbormasterMsg(type) {
   // type can be "pass", "fail", or "work"
   // See https://phabricator.khanacademy.org/conduit/method/harbormaster.sendmessage/

   def conduitToken = readFile(
      "${env.HOME}/page-weight-phabricator-conduit-token.secret").trim();

   def message = groovy.json.JsonOutput.toJson([
      "__conduit__": [
         "token": conduitToken,
      ],
      "buildTargetPHID": params.BUILD_PHID,
      "type": type,
   ])

   def body = "params=${java.net.URLEncoder.encode(message)}"

   def response = httpRequest acceptType: 'APPLICATION_JSON', contentType: 'APPLICATION_JSON', httpMode: 'POST', requestBody: body, url: "https://phabricator.khanacademy.org/api/harbormaster.sendmessage"

   assert response.status == 200;
}

def _computePageWeightDelta() {
   // This will be killed when the job ends -- see https://wiki.jenkins.io/display/JENKINS/ProcessTreeKiller
   sh("make serve &");
   sh("while ! curl localhost:8080 > /dev/null 2>&1; do echo Waiting for webapp; sleep 1; done; echo OK: webapp is available.");
   sh("while ! curl localhost:3000 > /dev/null 2>&1; do echo Waiting for kake; sleep 1; done; echo OK: kake is available.");

   // https://issues.jenkins-ci.org/browse/JENKINS-45837 :party_parrot_sad:
   def script = exec.shellEscapeList(["tools/compute_page_weight_delta.sh", GIT_SHA_BASE, GIT_SHA_DIFF]);

   // xvfb-run also munges stderr
   sh(script: "xvfb-run -a bash -c '${script} 2>/dev/null' | tee page_weight_delta.txt");

   def pageWeightDeltaInfo = readFile("page_weight_delta.txt");

   if (params.BUILD_PHID != "") {
       _submitPhabricatorComment(pageWeightDeltaInfo);
       _submitPhabricatorHarbormasterMsg("pass");
   }
}


def calculatePageWeightDeltas() {
   // NOTE(joshuan): This is mostly stolen from onTestWorker.groovy.
   // Consider adding a label param to onTestWorker.groovy?
   node("ka-page-weight-monitoring-ec2") {
      timestamps {
         dir("/home/ubuntu/webapp-workspace") {
            kaGit.checkoutJenkinsTools();
            withVirtualenv() {
               try {
                  withTimeout('2h') {
                     // We document what machine we're running on, to help
                     // with debugging.
                     def instanceId = exec.outputOf(
                        ["curl", "-s",
                         "http://169.254.169.254/latest/meta-data/instance-id"]);
                     def ip = exec.outputOf(
                        ["curl", "-s",
                         "http://169.254.169.254/latest/meta-data/public-ipv4"]);
                     echo("Running on ec2 instance ${instanceId} at ip ${ip}");
                     withEnv(["PATH=/usr/local/google_appengine:" +
                              "/home/ubuntu/google-cloud-sdk/bin:" +
                              "${env.HOME}/git-bigfile/bin:" +
                              "${env.PATH}"]) {
                        _setupWebapp();

                        // This is apparently needed to avoid hanging with
                        // the chrome driver.  See
                        // https://github.com/SeleniumHQ/docker-selenium/issues/87
                        // We also work around https://bugs.launchpad.net/bugs/1033179
                        withEnv(["DBUS_SESSION_BUS_ADDRESS=/dev/null",
                                 "TMPDIR=/tmp"]) {
                           withSecrets() {   // we need secrets to talk to bq/GCS
                              dir("webapp") {
                                 _computePageWeightDelta();
                              }
                           }
                        }
                     }
                  }
               } catch (e) {
                  if (params.BUILD_PHID != "") {
                     _submitPhabricatorComment("Failed to compute page weight deltas.");
                     _submitPhabricatorHarbormasterMsg("fail");
                  }
                  throw e;
               }
            }
         }
      }
   }
}

// Notify does more than notify on Slack. It also acts as a node and sets a timeout.
// We don't need notifications for this job, currently, but using this instead of a
// node and `onMaster` keeps this consistent with other jobs.
// TODO(joshuan): Consider renaming `notify`.
notify([timeout: "2h"]) {
   initializeGlobals();

   stage("Calculating page weight deltas") {
      calculatePageWeightDeltas();
   }
}
