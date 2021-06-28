// Pipeline job that creates a new dockerfile image for the publish-worker,
// and uploads it to our docker container-registry.

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.kaGit
//import vars.notify
//import vars.onWorker


new Setup(steps

).addStringParam(
    "GIT_REVISION",
    """The name of a webapp branch to use when building the docker image.
Most of the time master (the default) is the correct choice. But it can
be any commit that is "later" than the one for the last publish worker.""",
    "master"

).addStringParam(
    "ZND_NAME",
    """If you specified the commit of a znd in GIT_REVISION, put the
znd's name here.  It will be appended to the docker image name, to
indicate that this image does not derive from prod.""",
    ""

).addBooleanParam(
    "VALIDATE_COMMIT",
    """If set, check that this commit is safe to publish.  <b>UNSET
WITH CAUTION!</b> -- only for situations where you are overwriting
someone else's publish-deploy on purpose.""",
    true

).apply();


def runScript() {
   kaGit.safeSyncToOrigin("git@github.com:Khan/webapp",
                          params.GIT_REVISION);

   dir("webapp") {
       if (params.VALIDATE_COMMIT) {
          sh("content_editing/tools/publish/validate_commit_for_publish.sh");
       }
       exec(["env", "ZND_NAME=${params.ZND_NAME}", "content_editing/tools/publish/build_publish_image.sh"]);
       // TODO(csilvers): report the publish-content version to slack?
       echo("For how to use this image, see");
       echo ("https://khanacademy.atlassian.net/wiki/spaces/CP/pages/299204611/Publish+Process+Technical+Documentation");
   }
}

onWorker("build-worker", "30m") {
   notify([slack: [channel: '#cp-eng',
                   when: ['SUCCESS', 'FAILURE', 'ABORTED', 'UNSTABLE']]]) {
      stage("Running script") {
         runScript();
      }
   }
}
