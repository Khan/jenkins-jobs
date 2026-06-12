// Pipeline job that creates a new dockerfile image for the video-pipeline,
// uploads it to our docker container-registry, and maybe refreshes the GKE
// deployment.

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.kaGit
//import vars.notify
//import vars.onWorker


// TODO(jackz): If ZND deploys are needed, refer to the publish worker
// makefile and account for the ZND_NAME override.
new Setup(steps

).addStringParam(
    "GIT_REVISION",
    """The name of a webapp branch to use when building the docker image.
Most of the time master (the default) is the correct choice. But it can
be any commit that is "later" than the one for the last video-pipeline.""",
    "master"

).addBooleanParam(
    "REFRESH_VIDEO_PIPELINE",
    """If set, (re-)start the video-pipeline deployment on GKE after pushing
the new docker image.""",
    true

).apply();

currentBuild.displayName = "${currentBuild.displayName} (${params.GIT_REVISION})";

def runScript() {
    kaGit.safeSyncToOrigin("git@github.com:Khan/webapp",
                          params.GIT_REVISION);

    // Prune docker images before building if under 1.5GB of disk space.
    sh('[ $(df -BM --output=avail . | tr -cd 0-9) -gt 1500 ] || docker image prune -af')

    dir("webapp/services/content-editing/cmd/video_pipeline") {
       exec(["make", "build-and-push"]);
       echo("gcr.io/khan-internal-services/video-pipeline has been pushed:");
       exec(["make", "show-version"]);
       if (params.REFRESH_VIDEO_PIPELINE) {
          exec(["make", "deploy-transcoder"]);
          echo("https://console.cloud.google.com/kubernetes/deployment/us-central1/contentplatform/default/video-transcoder/overview?project=khan-internal-services has been refreshed");
       }
    }
}

onWorker("build-worker", "90m") {
   notify([slack: [channel: '#cp-eng',
                   when: ['SUCCESS', 'FAILURE', 'ABORTED', 'UNSTABLE']]]) {
      stage("Running script") {
         runScript();
      }
   }
}
