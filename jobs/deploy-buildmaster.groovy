// Groovy script to deploy buildmaster2

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

).addStringParam(
    "GIT_BRANCH",
    """<b>REQUIRED</b>. The branch to deploy from. Typically "master".""",
    "master"

).addChoiceParam(
    "GCLOUD_PROJECT",
    """<b>REQUIRED</b>. The Google Cloud project to deploy to.""",
    ["khan-test", "khan-internal-services"],

).addChoiceParam(
    "SERVICE_OR_JOB",
    """<b>REQUIRED</b>. The service or job to deploy. Note that "all" will deploy everything other than migrate-db and generate-db-migration-scripts""",
    ["all", "buildmaster", "reaper-job", "trigger-reminders-job", "warm-rollback-job", "migrate-db", "generate-db-migration-scripts"]

).apply();

currentBuild.displayName = "${currentBuild.displayName} - ${params.GIT_BRANCH} - ${params.GCLOUD_PROJECT} - ${params.SERVICE_OR_JOB}";

def buildAndDeploy() {
  withTimeout('15m') {
    kaGit.safeSyncToOrigin("git@github.com:Khan/buildmaster2",
                           params.GIT_BRANCH);

    // Enforce branch restriction: If the branch is not "master", only allow deployment to "khan-test"
    // if (params.GIT_BRANCH != "master" && params.GCLOUD_PROJECT != "khan-test" && params.SERVICE_OR_JOB != "generate-db-migration-scripts") {
    //   error("Only 'khan-test' project can be deployed from non-master branches.");
    // }

    dir("buildmaster2") {
      withEnv(["GCLOUD_PROJECT=${params.GCLOUD_PROJECT}"]) {
        // Deploy based on the selected service or job
        switch (params.SERVICE_OR_JOB) {
          case "all":
            sh("make deploy");
            break
          case "buildmaster":
            sh("make deploy_buildmaster");
            break
          case "reaper-job":
            sh("make deploy_reaper");
            break
          case "trigger-reminders-job":
            sh("make deploy_trigger_reminders");
            break
          case "warm-rollback-job":
            sh("make deploy_warm_rollback");
            break
          case "migrate-db":
            if (params.GIT_BRANCH != "master") {
              error("Database migrations can only be run from the master branch.");
            }
            sh("make run_database_migration");
            break
          case "generate-db-migration-scripts":
            if (params.GIT_BRANCH == "master") {
              error("Database migrations should be generated from PR branches.");
            }
            sh("make generate_db_migration_and_push");
            break
          default:
            error("Invalid service or job selected: ${params.SERVICE_OR_JOB}");
        }
        echo("Deployment successful!");
      }
    }
  }
}

onMaster('90m') {
  notify([slack: [channel: '#infrastructure-deploys',
                  sender : 'Mr Meta Monkey',  // we may be deploying Mr. Monkey himself!
                  emoji  : ':monkey_face:',
                  when   : ['BUILD START', 'SUCCESS', 'FAILURE', 'UNSTABLE', 'ABORTED']]]) {
    stage("Deploying") {
      buildAndDeploy();
    }
  }
}
