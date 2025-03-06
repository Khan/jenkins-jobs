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


def runScript() {
   kaGit.safeSyncToOrigin("git@github.com:Khan/webapp",
                          params.GIT_REVISION);

   dir("webapp") {
        try {
            // First, we need to get a local webserver running.
            // We need the `env` because otherwise jenkins's
            // BUILD_TAG takes precedence over our own.
            // TODO(csilvers): clear more of env?
            sh("ssh-agent env -u BUILD_TAG make start-dev-server-backend WORKING_ON=NONE")
            sh("rm -rf datastore")
            sh("mkdir -p datastore")
            sh("go run ./services/users/cmd/create_dev_users")
            sh("go run ./services/admin/cmd/make_admin -username testadmin")
            sh("gsutil cp gs://${params.CURRENT_SQLITE_BUCKET}/dev_datastore.tar gs://${params.CURRENT_SQLITE_BUCKET}/dev_datastore.tar.bak")
            // A rare case we *don't* want the `-t` flag to docker:
            // if we include it, tar refuses to emit output to a terminmal.
            sh("docker exec -i webapp-datastore-emulator-1 tar -C /var/datastore -c WEB-INF | gsutil cp - gs://${params.CURRENT_SQLITE_BUCKET}/dev_datastore.tar")
        } finally {
            // No matter what, we stop the local webserver.
            sh("ssh-agent make stop-server")
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
