// Cancel all the sibling builds of the given build.  This is a helper
// class that the Groovy Postbuild plugin can use, for example:
//
//    new CancelSiblings(manager.hudson, manager.build,
//                       manager.listener.logger).run()
//
// That is the entire script you need to enter into the "groovy
// postbuild" plugin, though you will need to set the classpath to
// include the directory holding this file.
//
// This is meant to be run via the "groovy postbuild" plugin when the
// build is canceled.  (It doesn't hurt to run it when the build
// fails, or even when it succeeds, though it will be a noop in those
// cases.)  This lets you automatically cancel downstream jobs when
// you yourself are canceled.  As long as the downstream jobs
// themselves are set up to run this as a post-build step, this will
// recursively cancel *all* downstream jobs.
//
// The "groovy postbuild" plugin gives access to a 'manager' object;
// you will pass parts of this manager object to this class.
//
// This returns the number of tasks canceled -- either from the queue
// for while running.

class CancelSiblings {
    Object hudson;
    Object build;
    Object printer;

    public CancelSiblings(Object hudson, Object build, Object printer) {
        this.hudson = hudson;
        this.build = build;
        this.printer = printer;

        // Mark the upstream job that spawned us.  (If we weren't
        // spawned by another job, we mark that fact, since that means
        // we can't have any siblings.)
        this.upstreamProject = null;
        this.upstreamBuild = null;
        if (this.build.hasProperty('causes')) {
            for (cause in this.build.causes) {
                if (cause.hasProperty('upstreamProject')) {
                    this.upstreamProject = cause.upstreamProject;
                    this.upstreamBuild = cause.upstreamBuild;  // the build #
                    break;
                }
            }
        }
    }

    public int cancelInQueue() {
        // Cancel builds waiting in the queue, so they never start.
        def numCancels = 0;
        for (build in this.hudson.queue.items) {
            if (build == this.build) { continue; } // don't cancel ourself!
            if (!build.hasProperty('causes')) { continue; }
            for (cause in build.causes) {
                if (!cause.hasProperty('upstreamProject')) { continue; }
                if (cause.upstreamProject == this.upstreamProject &&
                        cause.upstreamBuild == this.upstreamBuild) {
                    this.printer.println('Cancelling ' + build.toString());
                    this.hudson.queue.cancel(build.task);
                    numCancels++;
                    break;
                }
            }
        }
        return numCancels;
    }

    public int cancelRunning() {
        // Cancel running builds.
        def numCancels = 0;
        for (job in this.hudson.instance.items) {
            for (build in job.builds) {
                if (build == this.build) { continue; } // don't cancel ourself!
                if (!build.hasProperty('causes')) { continue; }
                if (!build.isBuilding()) { continue; }
                for (cause in build.causes) {
                    if (!cause.hasProperty('upstreamProject')) { continue; }
                    if (cause.upstreamProject == this.upstreamProject &&
                            cause.upstreamBuild == this.upstreamBuild) {
                        this.printer.println('Stopping ' + build.toString());
                        build.doStop();
                        numCancels++;
                        break;
                    }
                }
            }
        }
        return numCancels;
    }

    public int run() {
        // Optimization when we know we can't have any siblings.
        if (!this.upstreamProject) {
            return 0;
        }

        def numCancels = 0;
        numCancels += this.cancelInQueue();
        numCancels += this.cancelRunning();
        return numCancels;
    }
}
