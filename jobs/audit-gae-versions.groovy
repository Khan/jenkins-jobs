// Automatically clean up old versions of webapp, during/after a deploy.
// This is kicked off by deploy-webapp but run asynchronously to avoid slowing
// down the deploy.

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.exec
//import vars.kaGit
//import vars.notify
//import vars.withTimeout
new Setup(steps

).addStringParam(
   "GIT_REVISION",
   """A commit-ish to check out.  This only affects the version of the
deletion script used; it will probably match the deployed version's code,
but it doesn't need to.""",
   "master"

).addBooleanParam(
    "DRY_RUN",
    """If set, don't actually do the deletion, but simply log which versions
would be deleted.  Useful for testing.""",
    false

).apply();

def deleteVersions() {
   withTimeout('30m') {
      kaGit.safeSyncTo("git@github.com:Khan/webapp", params.GIT_REVISION);
      dir("webapp") {
         sh("make python_deps");
         def args = ["deploy/audit_gae_versions.py"]
         if (params.DRY_RUN) {
            args += ["--dry-run"];
         }
         exec(args);
      }
   }
}


notify([slack: [channel: '#1s-and-0s-deploys',
                sender: 'Mr Monkey',
                emoji: ':monkey_face:',
                when: ['FAILURE', 'UNSTABLE', 'ABORTED']],
        aggregator: [initiative: 'infrastructure',
                     when: ['SUCCESS', 'BACK TO NORMAL',
                            'FAILURE', 'ABORTED', 'UNSTABLE']],
        timeout: "30m"]) {
   stage("Deleting") {
      deleteVersions();
   }
}
