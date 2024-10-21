// Groovy script to deploy internal-services (well, one specific service).  We
// always deploy from origin/master.

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;

// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.exec
//import vars.kaGit
//import vars.notify
//import vars.withTimeout

// The easiest setup ever! -- we just use the defaults.
new Setup(steps

).addChoiceParam(
    "SERVICE",
    """<b>REQUIRED</b>. The service to deploy, such as "buildmaster" or "ingress".""",
    ["buildmaster"],
).addStringParam(
    "BRANCH",
    """<b>REQUIRED</b>. The branch to deploy from. Typically "master".""",
    "master"
).addChoiceParam(
    "GCLOUD_PROJECT",
    """<b>REQUIRED</b>. The Google Cloud project to deploy to.""",
    ["khan-test", "khan-internal-services"],
).apply();

currentBuild.displayName = "${currentBuild.displayName} (${params.SERVICE} - ${params.BRANCH})";

def installDeps() {
   kaGit.safeSyncToOrigin("git@github.com:Khan/buildmaster2", "${params.BRANCH}");
}

def deploy() {
  withTimeout('15m') {
    // Enforce branch restriction: If the branch is not "master", only allow deployment to "khan-test"
    if (params.BRANCH != "master" && params.GCLOUD_PROJECT != "khan-test") {
      error("Only 'khan-test' project can be deployed from non-master branches.");
    }

    dir("internal-services/${params.SERVICE}") {
      withEnv([
          // We also use the jenkins service-account, rather than
          // prod-deploy, because it has the right permissions.
          "CLOUDSDK_CORE_ACCOUNT=526011289882-compute@developer.gserviceaccount.com",
          "GCLOUD_PROJECT=${params.GCLOUD_PROJECT}"
      ]) {
        try {
          // Call make deploy_to_cloud_run with the correct project and branch
          sh "make deploy"
          echo "Deployment successful!"
        } catch (Exception e) {
          echo "Deployment failed"
          currentBuild.result = 'FAILURE'
          throw e
        }
      }
    }
  }
}

onMaster('90m') {
  notify([slack: [channel: '#infrastructure-devops', // we may be deploying Mr. Monkey himself!
                  sender : 'Mr Meta Monkey',
                  emoji  : ':monkey_face:',
                  when   : ['BUILD START', 'SUCCESS', 'FAILURE', 'UNSTABLE', 'ABORTED']]]) {

    stage("Installing deps") {
      installDeps();
    }

    stage("Deploying") {
      deploy();
    }
  }
}
