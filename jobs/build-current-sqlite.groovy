// Pipeline job that creates the dev datastore and uploads it to gcs.
// This is the file that is then downloaded by `make current.sqlite`.
// (It is also downloaded by the `datstore-emulator` in
// dev/server/docker-compose.backend.yml.

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.kaGit
//import vars.notify
//import vars.onWorker
//import vars.withSecrets


new Setup(steps

).addCronSchedule("H H * * 0"

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

).apply();


// This runs in webapp/.
def updateDatabase() {
   sh("go run ./services/users/cmd/create_dev_users");
   sh("go run ./services/admin/cmd/make_admin -username testadmin");
}

def runScript() {
   kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", params.GIT_REVISION);
   kaGit.safeSyncToOrigin("git@github.com:Khan/frontend", "main");

   dir("webapp") {
      sh("rm -rf datastore");
      sh("mkdir -p datastore")
      try {
         // First, we need to get a local webserver running.  We
         // need the `ssh-agent` because apparently jenkins don't
         // have one by default, and our docker rules use it.  We
         // need the `env` because otherwise jenkins's BUILD_TAG
         // takes precedence over our own.
         // TODO(csilvers): clear more of env?
         sh("ssh-agent env -u BUILD_TAG USE_FIRESTORE_EMULATOR=true SKIP_FIRESTORE_SNAPSHOT_DOWNLOAD=true make start-dev-server-backend WORKING_ON=NONE");

         updateDatabase();

         // Send SIGTERM to the firestore emulator to have it write out its
         // backing file.
         //
         // TODO(marksandstrom) Is there a better, more direct way to do this?
         // We want to send SIGTERM to the process running the command
         // /usr/bin/firestore-emulator-plus.
         //
         // The command works as follows:
         // - list the command lines of the running processes
         //       ls /proc/*/cmdline
         // - echo the /proc/ entry and the command line on the same line
         //       echo -n "$cmd " | cat - $cmd
         // - replace the \0 characters (from cmdline) with spaces
         // - add a newline to the end of the cmdline output
         //       awk '{ print }'
         // - find the /usr/bin/firestore-emulator-plus process using grep
         // - extract the pid from the /proc/ entry -- cut -f/ -d3
         // - then, if there's output -- while read pid
         // - send SIGTERM to the pid -- kill $pid
         sh("docker exec webapp-datastore-emulator-1 dash -c 'ls /proc/*/cmdline | while read cmd; do echo -n \"$cmd \" | cat - \"$cmd\" 2>/dev/null | tr \"\\0\" \" \" | awk \"{ print }\" | grep \"/usr/bin/qemu-x86_64 /usr/bin/firestore-emulator-plus\" | grep -v dash | cut -d/ -f3 | while read pid; do kill $pid; done; done'")

         // Now upload the new database to gcs.
         sh("gsutil cp ${params.CURRENT_SQLITE_BUCKET}/dev_datastore.tar.gz ${params.CURRENT_SQLITE_BUCKET}/dev_datastore.tar.gz.bak");
         sh("docker exec -it webapp-datastore-emulator-1 gsutil cp /var/datastore/dev_datastore.tar.gz ${params.CURRENT_SQLITE_BUCKET}/dev_datastore.tar.gz");
      } finally {
          // No matter what, we stop the local webserver.
         sh("ssh-agent make stop-server");
      }
   }
}

// We run on a special worker machine because starting up all the
// dev backends requires hefty resources.
onWorker("build-worker", "1h") {
   notify([slack: [channel: '#infrastructure',
                   when: ['SUCCESS', 'FAILURE', 'ABORTED', 'UNSTABLE']]]) {
      stage("Running script") {
         runScript();
      }
   }
}
