// Build a version of webapp, and deploy it to a single module for testing.

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.exec
//import vars.kaGit
//import vars.notify
//import vars.withTimeout


new Setup(steps

// TODO(benkraft): more.
).addStringParam(
    "GIT_SHA1",
    """<b>REQUIRED</b>. The sha to build.""",
    ""

).apply();

