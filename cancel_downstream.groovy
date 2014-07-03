// Cancel all the immediate-downstream builds of the given build.
// The build is specified via commandline args: job name and build
// number.  For instance: "$0 make-check 1234"
//
// This is meant to be run as a post-build step when the build is
// canceled.  (It doesn't hurt to run it when the build fails, or even
// when it succeeds, though it will be a noop in those cases.)  This
// lets you automatically cancel downstream jobs when you yourself are
// canceled.  As long as the downstream jobs themselves are set up to
// run this as a post-build step, this will recursively cancel *all*
// downstream jobs.
//
// This returns the number of tasks canceled -- either from the queue
// for while running.


import jenkins.model.*;

def upstreamProject = args[0];
def upstreamBuildNumber = args[1];

def numCancels = 0;

// First, cancel builds waiting in the queue, so they never start.
for (build in jenkins.model.Jenkins.instance.queue.items) {
    for (cause in build.causes) {
        if (!cause.hasProperty('upstreamProject')) { continue; }
        if (cause.upstreamProject == upstreamProject &&
                cause.upstreamBuild.toString() == upstreamBuildNumber) {
            println 'Cancelling ' + build.toString();
            jenkins.model.Jenkins.instance.queue.cancel(build.task);
            numCancels++;
            break;
        }
    }
}

// Now, cancel running builds.
for (job in jenkins.model.Jenkins.instance.items) {
    for (build in job.builds) {
        if (!build.hasProperty('causes')) { continue; }
        if (!build.isBuilding()) { continue; }
        for (cause in build.causes) {
            if (!cause.hasProperty('upstreamProject')) { continue; }
            if (cause.upstreamProject == upstreamProject &&
                    cause.upstreamBuild.toString() == upstreamBuildNumber) {
                println 'Stopping ' + build.toString();
                build.doStop();
                numCancels++;
                break;
            }
        }
    }
}

numCancels;

