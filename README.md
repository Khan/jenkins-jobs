# jenkins-jobs

This repository contains the scripts run by Khan Academy's Jenkins jobs.

## Groovy jobs

Each job runs a groovy script from the `jobs/` directory.  See the [groovy-template](https://jenkins.khanacademy.org/job/groovy-template/) for how to set up a new one.  Shared groovy code lives in the `vars/` directory, and shared Java called by Groovy lives in `src/`.  Random shell scripts used by jobs live at the repo toplevel.

TODO(benkraft): Document more about how to write groovy scripts here.

## Deploy

Each Jenkins job that runs pulls the latest `master`, so as soon as you push, new jobs will start to use your new code.  Note that certain configuration properties (most importantly job parameters) are updated each time the job runs, so new parameters won't show up until it runs once.

## Related repositories

* For the scripts that set up our Jenkins master and workers, see [aws-config](https://github.com/Khan/aws-config/).
* For the buildmaster which drives the deploy system, see [internal-services](https://github.com/Khan/internal-services/tree/master/buildmaster).
