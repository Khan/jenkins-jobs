// Delete a version of our application on App Engine.

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
import groovy.json.JsonSlurperClassic;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.exec
//import vars.kaGit
//import vars.notify
//import vars.withTimeout
//import vars.withVirtualenv


new Setup(steps

// We run this job once every few minutes; 100 builds covers about
// 30 minutes.  Let's keep at least a days' around, for debugging.
).resetNumBuildsToKeep(
   9000,

).addStringParam(
    "SERVICE_VERSIONS",
    """<b>REQUIRED</b>. A JSON object where each key is a service name and
the value is a list of versions to delete for that service.
Example: {"serviceA": ["version1", "version2"], "serviceB": ["version3"]}""",
    ""

).apply();

currentBuild.displayName = "${currentBuild.displayName} (${params.SERVICE_VERSIONS})";


def verifyArgs() {
   if (!params.SERVICE_VERSIONS) {
      notify.fail("The SERVICE_VERSIONS parameter is required.");
   }
   try {
      def parsed = new JsonSlurperClassic().parseText(params.SERVICE_VERSIONS);
      if (!(parsed instanceof Map)) {
         notify.fail("SERVICE_VERSIONS must be a JSON object.");
      }
      parsed.each { service, versions ->
         if (!(versions instanceof List)) {
            notify.fail("Each service entry must be a list of versions.");
         }
         versions.each { version ->
            if (!(version instanceof String)) {
               notify.fail("Versions must be strings.");
            }
         }
      }
   } catch (Exception e) {
      notify.fail("Invalid JSON format in SERVICE_VERSIONS: ${e.message}");
   }
}

def _setupWebapp() {
   withTimeout('25m') {
      kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", "master");
   }
}

def deleteVersion() {
   withTimeout('25m') {
      dir("webapp") {
         // See https://issues.apache.org/jira/browse/GROOVY-6934
         // JsonSlurper is not thread safe or serializable. Let's use JsonSlurperClassic instead.
         def serviceVersions = new JsonSlurperClassic().parseText(params.SERVICE_VERSIONS);

         serviceVersions.each { service, versions ->
            def args = ["deploy/delete_gae_versions.py"]
            args += (versions as List);  // Ensure Groovy treats it as a list
            args += ["--services", service.trim()];
            exec(args);
         }
      }
   }
}

onMaster('30m') {
   notify([slack: [channel: '#1s-and-0s-deploys',
                sender: 'Mr Monkey',
                emoji: ':monkey_face:',
                when: ['FAILURE', 'UNSTABLE', 'ABORTED']]]) {
      verifyArgs();
      stage("Initializing webapp") {
         _setupWebapp();
      }
      stage("Deleting") {
         withVirtualenv.python3() {
            // Acquire the shared mutex with deploy-webapp's set default step.
            // If set default takes a long time, we may get multiple
            // delete-version jobs queued for the same version(s). This should
            // no-op just fine.
            //
            // While we would like to prioritize deploy-webapp over
            // delete-versions by skipping this job when locked, we also don't
            // want to lose any delete requests that wouldn't be automatically
            // retried, such as ZND deletion requests from users. Since GCP
            // support has advised us to keep our traffic tag count as low as
            // possible, we really don't want to miss any ZND deletions. Setting
            // lock priority lower than in the set default step should be a good
            // middle ground.
            //
            // https://plugins.jenkins.io/lockable-resources/#plugin-content-lock-queue-priority
            //
            // We do this because the update-traffic command creates a
            // LongRunningOperation that expects a specific target traffic
            // allocation across all revisions with traffic tags and/or a
            // traffic % higher than 0%. If a revision is deleted while the
            // traffic migration is in-progress, the target state will never be
            // achieved so the operation will wait until its full 60 minute
            // timeout.
            lock(resource: 'update-traffic-lock', priority: 10) {
               deleteVersion();
            }
         }
      }
   }
}
