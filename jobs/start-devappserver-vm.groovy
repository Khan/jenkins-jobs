// Start up a VM for a developer use to run dev-appserver on.
//
// Developers can run a dev-appserver -- a local version of our
// production website -- on their local machines, but it's slow and
// uses a lot of resources.  To help with that, we provide a VM for this
// purpose as well.  Users can copy their webapp dir over to this VM
// and then ssh to it and run `make serve`.
//
// We don't really need to do any work here: the gce plugin will spawn
// a new VM with the jenkins-worker image by default.  Our main job is
// to make it easy for users to tear down the job when they're done.
// So we just run a `sleep` command.  When the user is done, they can
// kill the sleep command to release this VM back into the pool.  Or
// when the sleep finishes, that will happen for them.

@Library("kautils")
import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.exec
//import vars.notify
//import vars.onWorker


new Setup(steps

// We let many devs have their own appservers at once!
).allowConcurrentBuilds(

).addStringParam(
    "TIMEOUT",
    """How long we let this VM run before reaping it, in minutes.""",
    "300"

).addStringParam(
    "PORT",
    """The port to use on your local machine to talk to webapp.""",
    "8090"

).addStringParam(
    "HOTEL_PORT",
    """The port to use on your local machine to talk to hotel.""",
    "2000"

).addStringParam(
    "USERNAME",
    """Your username (just for display purposes).""",
    "<unknown>"

).apply();

currentBuild.displayName = ("${currentBuild.displayName} " +
                            "(${params.USERNAME})");


// TODO(csilvers): add a jenkins arg to allow for
// frontend/backend/fullstack/ssr, and maybe to pass in other flags.
STARTUP_SCRIPT = """
#!/bin/sh

cd webapp-workspace/webapp
. ../env/bin/activate
make serve

# Now we just want to make sure the ssh connection doesn't terminate,
# so we sleep for forever.  When the other side closes the connection,
# this sleep will terminate, then we'll kill all the other sleep's
# to cause the Jenkins job to terminate.
echo "Just hangin' out.  Hit control-D to exit and shut down the VM."
cat >/dev/null

killall sleep
"""

// We use a build worker, because running dev-appserver requires a lot
// of CPU and memory.
// TODO(csilvers): do we want an even beefier machine?
onWorker("build-worker", "${params.TIMEOUT}m") {
    notify([:]) { // no notifications needed!
        // TODO(csilvers): move this to Khan/aws-config:jenkins/worker-setup.sh
        exec(["wget", "-O/tmp/fastly.deb", "https://github.com/fastly/cli/releases/download/v3.3.0/fastly_3.3.0_linux_amd64.deb"]);
        exec(["sudo", "apt", "install", "/tmp/fastly.deb"]);

        writeFile(file: "/tmp/startup.sh", text: STARTUP_SCRIPT);
        exec(["chmod", 755, "/tmp/startup.sh"]);

        // We won't be syncing over the `.git` directory, so let's
        // get rid of it so folks aren't tempted to use git (and get
        // incorrect answers).
        if (fileExists("webapp/.git")) {
            exec(["mv", "webapp/.git", "/tmp"]);
        }

        def instanceId = sh(
            script: ("curl -s " +
                     "http://metadata.google.internal/computeMetadata/v1/instance/hostname " +
                     "-H 'Metadata-Flavor: Google' | cut -d. -f1"),
            returnStdout: true).trim();

        echo("1. To start the dev-appserver, run: ");
        echo("   dev/tools/devserver-sync-to-vm.sh ${instanceId}");
        echo("   gcloud --project khan-internal-services compute ssh --ssh-flag='-L ${params.PORT}:localhost:8090' --ssh-flag='-L ${params.HOTEL_PORT}:localhost:2000' ubuntu@${instanceId} -- /tmp/startup.sh");
        // TODO(csilvers): make it faster to sync only the files that changed.
        echo("2. Whenever you make code changes, run devserver-sync-to-vm.sh again");
        echo("3. When you are done, hit control-C to exit the ssh and terminate the VM");

        def timeout = params.TIMEOUT.toInteger();
        def firstWarningMinutesLeft = 10;
        def secondWarningMinutesLeft = 2;

        def timeToFirstWarning = (timeout - firstWarningMinutesLeft) * 60;
        def timeToNextWarning = (
            (firstWarningMinutesLeft - secondWarningMinutesLeft) * 60);

        // TODO(csilvers): send warning to slack as well as on the console.
        exec(["sleep", timeToFirstWarning.toString()]);
        exec(["wall", "*** System will be shutting down in ${firstWarningMinutesLeft} minutes ***"]);

        exec(["sleep", timeToNextWarning.toString()]);
        exec(["wall", "*** System will be shutting down in ${secondWarningMinutesLeft} minutes ***"]);

        exec(["sleep", secondWarningMinutesLeft.toString()]);
    }
}
