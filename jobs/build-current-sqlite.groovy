// Pipeline job that creates current.sqlite from the sync snapshot
// (on gcs) and uploads the resulting current.sqlite to gcs.

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.kaGit
//import vars.notify
//import vars.onWorker
//import vars.withSecrets


new Setup(steps

).addCronSchedule("H 21 * * *"

).addStringParam(
    "SNAPSHOT_NAMES",
    """We assume that the locale name is the part of the bucket name that
follows that last underscore character.""",
    "snapshot_en snapshot_es"

).addStringParam(
    "CURRENT_SQLITE_BUCKET",
    "GCS bucket to upload current.sqlite to",
    "gs://ka_dev_sync"

).addStringParam(
    "GIT_REVISION",
    """The name of a webapp branch to use when building current.sqlite.
Most of the time master (the default) is the correct choice. The main
reason to use a different branch is to test changes to the sync process
that haven't yet been merged to master.""",
    "master"

).addStringParam(
    "DEV_APPSERVER_ARGS",
    """Extra arguments that will be provided to dev_appserver.py.  Must be
    shell-quoted (as necessary).""",
    ""

).apply();


def runScript() {
   kaGit.safeSyncToOrigin("git@github.com:Khan/webapp",
                          params.GIT_REVISION);

   dir("webapp") {
       sh("make clean_pyc");    // in case some .py files went away
       sh("make deps");
   }

   // We need secrets to talk to gcs, prod.
   withSecrets() {
      withEnv(
         ["CURRENT_SQLITE_BUCKET=${params.CURRENT_SQLITE_BUCKET}",
          "DEV_APPSERVER_ARGS=${params.DEV_APPSERVER_ARGS}",
          "SNAPSHOT_NAMES=${params.SNAPSHOT_NAMES}"]) {
         sh("jenkins-jobs/build_current_sqlite.sh");
      }
   }
}


// We run on a special worker machine because this job uses so much
// memory.  
onWorker("ka-content-sync-ec2", "8h") {
   notify([slack: [channel: '#infrastructure',
                   when: ['SUCCESS', 'FAILURE', 'ABORTED', 'UNSTABLE']],
           aggregator: [initiative: 'infrastructure',
                        when: ['SUCCESS', 'BACK TO NORMAL',
                               'FAILURE', 'ABORTED', 'UNSTABLE']]]) {
      stage("Running script") {
         runScript();
      }
   }
}
