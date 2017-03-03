// Pipeline job that creates current.sqlite from the sync snapshot
// (on gcs) and uploads the resulting current.sqlite to gcs.

@Library("kautils")
// Classes we use, under jenkins-tools/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-tools/vars/.  This is just for documentation.
//import vars.kaGit
//import vars.notify
//import vars.withSecrets
//import vars.withTimeout


new Setup(steps

).addCronSchedule("H 21 * * 2-6"

).addStringParam(
    "SNAPSHOT_BUCKETS",
    """GCS buckets to download snapshots from. We assume that the locale name
is the part of the bucket name that follows that last underscore character.""",
    "snapshot_en snapshot_es"

).addStringParam(
    "CURRENT_SQLITE_BUCKET",
    "GCS bucket to upload current.sqlite to",
    "gs://ka_dev_sync/current.sqlite"

).apply();


def runScript() {
   // We run on a special worker machine because this job uses so much
   // memory.  There's no `onFoo` macro for this, so we have to repeat
   // all that logic here ourselves.
   node("ka-content-sync-worker") {
      timestamps {
         kaGit.checkoutJenkinsTools();

         withVirtualenv() {
            withTimeout("7h") {
               kaGit.safeSyncTo("git@github.com:Khan/webapp", "master");

               // We need secrets to talk to gcs, prod.
               withSecrets() {
                  withEnv(
                     ["CURRENT_SQLITE_BUCKET=${params.CURRENT_SQLITE_BUCKET}",
                      "SNAPSHOT_BUCKETS=${params.SNAPSHOT_BUCKETS}"]) {
                     sh("jenkins-tools/build_current_sqlite.sh");
                  }
               }
            }
         }
      }
   }
}


notify([slack: [channel: '#infrastructure-alerts',
                when: ['SUCCESS', 'FAILURE', 'ABORTED', 'UNSTABLE']]]) {
   stage("Running script") {
      runScript();
   }
}
