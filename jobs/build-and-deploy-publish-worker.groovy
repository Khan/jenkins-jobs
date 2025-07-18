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

).addStringParam(
    "DEPLOYER_EMAIL",
    """Your @khanacademy.org email address is required if deploying the newly built publish-worker image to production.""",
    ""
).apply();

currentBuild.displayName = "${currentBuild.displayName} (${params.GIT_REVISION})";

def setupWebapp() {
    withTimeout('1h') {
        kaGit.safeSyncToOrigin("git@github.com:Khan/webapp",
            params.GIT_REVISION);
    }
}

def runScript() {
    // Prune docker images before building if under 1.5GB of disk space.
    sh('[ $(df -BM --output=avail . | tr -cd 0-9) -gt 1500 ] || docker image prune -af');

    dir("webapp") {
        if (params.VALIDATE_COMMIT) {
            sh("services/content-editing/publish/tools/validate_commit_for_publish.sh");
        }
        def buildOutput = exec.outputOf(["env", "ZND_NAME=${params.ZND_NAME}", "services/content-editing/publish/tools/build_publish_image.sh"]);
        echo("For how to use this image, see");
        echo("https://khanacademy.atlassian.net/wiki/spaces/CP/pages/299204611/Publish+Process+Technical+Documentation");

        // If they say 'no' to this, it aborts the rest of the job.
        // If they say 'yes', then control flow continues.
        input("Deploy to production?");

        def matcher = (buildOutput =~ /export PUBLISH_VERSION=([\w.-]+)/);
        def publishVersion = matcher ? matcher[0][1] : null;
        matcher = null;
        if (!publishVersion) {
            notify.fail("Could not extract PUBLISH_VERSION from build_publish_image.sh output");
        }

        def deployerEmail = params.DEPLOYER_EMAIL;
        if (!deployerEmail || !deployerEmail.endsWith("@khanacademy.org")) {
            notify.fail("Could not determine a valid deployer email. DEPLOYER_EMAIL is missing or is a @khanacademy.org address: ${deployerEmail}");
        }
        def goArgs = [
            "go", "run", "services/content-editing/cmd/update-publish-worker/main.go",
            "--publish-image-version=${publishVersion}",
            "--publish-image-version-including-master",
            "--yes-really-update-publish-worker-in-production",
            "--deployer-email=${deployerEmail}"
        ];
        ansiColor('xterm') {
            exec(goArgs);
        }
        echo("For additional information about the publish worker deploy script, see");
        echo("https://khanacademy.atlassian.net/wiki/spaces/CP/pages/3566764137/Publish+Worker+Automated+Deploy");
    }
}

onWorker("build-worker", "60m") {
    notify([slack: [channel: '#cp-eng', when: ['SUCCESS', 'FAILURE', 'ABORTED', 'UNSTABLE']]]) {
        stage("Initializing webapp") {
            setupWebapp();
        }
        stage("Running script") {
            runScript();
        }
    }
}
