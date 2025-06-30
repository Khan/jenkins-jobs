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
            args += ["--modules", service.trim()];
            exec(args);
         }
      }
   }
}

onMaster('30m') {
   notify([slack: [channel: '#eng-deploys-backend',
                sender: 'Mr Monkey',
                emoji: ':monkey_face:',
                when: ['FAILURE', 'UNSTABLE', 'ABORTED']]]) {
      verifyArgs();
      stage("Initializing webapp") {
         _setupWebapp();
      }
      stage("Deleting") {
         withVirtualenv.python3() {
            deleteVersion();
         }
      }
   }
}
