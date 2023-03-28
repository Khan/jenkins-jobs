// Pipeline job that creates a new dockerfile image for the translation-pipeline,
// uploads it to our docker container-registry, and maybe refreshes crowdin-go
// deployment in GKE.

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
be any commit that is "later" than the one for the last translation-pipeline.""",
    "master"

).addStringParam(
    "ZND_NAME",
    """If you specified the commit of a znd in GIT_REVISION, put the
znd's name here.  It will be appended to the docker image name, to
indicate that this image does not derive from prod.""",
    ""

).addBooleanParam(
    "REFRESH_CROWDIN_GO",
    """If set, (re-)start the crowdin-go deployment on GKE after pushing the
new docker image.""",
    true

).apply();

currentBuild.displayName = "${currentBuild.displayName} (${params.GIT_REVISION})";

def runScript() {
    kaGit.safeSyncToOrigin("git@github.com:Khan/webapp",
                          params.GIT_REVISION);

    // Prune docker images before building if under 1.5GB of disk space.
    sh("[ $(df -BM --output=avail . | tr -cd 0-9) -gt 1500 ] || docker image prune -af")

    dir("webapp/services/content-editing/translation_pipeline") {
       exec(["make", "push", "ZND_NAME=${params.ZND_NAME}"]);
       // TODO(csilvers): report the image version to slack?
       echo("gcr.io/khan-internal-services/crowdin-go has been pushed");
       if (params.REFRESH_CROWDIN_GO) {
          exec(["make", "crowdin-go"]);
          echo("console.cloud.google.com/kubernetes/deployment/us-central1-b/internal-services/crowdin-go/crowdin-go/overview?project=khan-internal-services has been refreshed");
       } else {
          echo("Next step is to run `make -C services/content-editing/translation_pipeline refresh` or similar.");
       }
       echo("For more details, see");
       echo("https://khanacademy.atlassian.net/wiki/spaces/CP/pages/1800470706/Common+Support+Tasks#Translation-Tasks");
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
