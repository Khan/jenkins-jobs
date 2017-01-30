// Cancel all the immediate-downstream builds of the given build.
// This is a helper class that the Groovy Postbuild plugin can use,
// for example:
//
//    new CancelDownstream(manager.hudson, manager.build,
//                         manager.listener.logger).run()
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

class CancelDownstream {
    Object hudson;
    Object upstreamBuild;
    Object printer;

    public CancelDownstream(Object hudson, Object upstreamBuild,
                            Object printer) {
        this.hudson = hudson;
        this.upstreamBuild = upstreamBuild;
        this.printer = printer;
    }

    public int cancelInQueue() {
        // Cancel builds waiting in the queue, so they never start.
        def numCancels = 0;
        for (build in this.hudson.queue.items) {
            if (!build.hasProperty('causes')) { continue; }
            for (cause in build.causes) {
                if (!cause.hasProperty('upstreamProject')) { continue; }
                if (cause.upstreamProject == this.upstreamBuild.project.name &&
                        cause.upstreamBuild == this.upstreamBuild.number) {
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
            if (!job.hasProperty('builds')) { continue; }   // a folder, maybe
            for (build in job.builds) {
                if (!build.hasProperty('causes')) { continue; }
                if (!build.isBuilding()) { continue; }
                for (cause in build.causes) {
                    if (!cause.hasProperty('upstreamProject')) { continue; }
                    if (cause.upstreamProject == 
                        this.upstreamBuild.project.name &&
                            cause.upstreamBuild == this.upstreamBuild.number) {
                        this.printer.println('Stopping ' + build.toString());
                        build.doStop();
                        // Wait for this build to finish, so it can do
                        // clean-up of its own.
                        while (build.isBuilding()) {
                            sleep(1000);
                        }
                        this.printer.println(build.toString() + ' stopped.');
                        numCancels++;
                        break;
                    }
                }
            }
        }
        return numCancels;
    }

    public int run() {
        def numCancels = 0;
        numCancels += this.cancelInQueue();
        numCancels += this.cancelRunning();
        return numCancels;
    }
}
