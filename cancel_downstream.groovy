// Cancel all the immediate-downstream builds of the given build.
// The build is specified via commandline args: job name and build
// number.  For instance: "$0 make-check 1234"
//
// This is meant to be run via the "groovy postbuild" plugin when the
// build is canceled.  (It doesn't hurt to run it when the build
// fails, or even when it succeeds, though it will be a noop in those
// cases.)  This lets you automatically cancel downstream jobs when
// you yourself are canceled.  As long as the downstream jobs
// themselves are set up to run this as a post-build step, this will
// recursively cancel *all* downstream jobs.
//
// The "groovy postbuild" plugin gives access to 'hudson' and to
// 'manager.build', which is the current build.
//
// This returns the number of tasks canceled -- either from the queue
// for while running.


def upstreamProject = manager.build.project.name;
def upstreamBuildNumber = manager.build.number;

def numCancels = 0;

// First, cancel builds waiting in the queue, so they never start.
for (build in hudson.queue.items) {
    for (cause in build.causes) {
        if (!cause.hasProperty('upstreamProject')) { continue; }
        if (cause.upstreamProject == upstreamProject &&
                cause.upstreamBuild == upstreamBuildNumber) {
            println 'Cancelling ' + build.toString();
            hudson.queue.cancel(build.task);
            numCancels++;
            break;
        }
    }
}

// Now, cancel running builds.
for (job in hudson.instance.items) {
    for (build in job.builds) {
        if (!build.hasProperty('causes')) { continue; }
        if (!build.isBuilding()) { continue; }
        for (cause in build.causes) {
            if (!cause.hasProperty('upstreamProject')) { continue; }
            if (cause.upstreamProject == upstreamProject &&
                    cause.upstreamBuild == upstreamBuildNumber) {
                println 'Stopping ' + build.toString();
                build.doStop();
                numCancels++;
                break;
            }
        }
    }
}

numCancels;

