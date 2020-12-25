// The pipeline job to run the full suite of webapp tests.
//
// This just calls a few other jobs in order to do the running.

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
//import vars.notify


new Setup(steps

).addStringParam(
   "GIT_REVISION",
   """The git commit-hash to run tests at, or a symbolic name referring
to such a commit-hash.""",
   "master"

).addBooleanParam(
   "FORCE",
   """If set, run the tests even if the database says that the tests
have already passed at this GIT_REVISION.""",
   false

).addCronSchedule(
   '0 2 * * 1-5'        // Run every weekday morning at 2am

).apply();

currentBuild.displayName = ("${currentBuild.displayName} " +
                            "(${params.GIT_REVISION})");

def runAllTests() {
    build(job: '../deploy/webapp-test',
          parameters: [
             string(name: 'GIT_REVISION', value: params.GIT_REVISION),
             string(name: 'BASE_REVISION', value: ""),
             string(name: 'MAX_SIZE', value: "huge"),
             string(name: 'SLACK_CHANNEL', value: "#1s-and-0s"),
             booleanParam(name: 'FORCE', value: params.FORCE),
            // The single test make_test_db_test takes 99 minutes to
            // run.  All the other tests between them take 340 minutes
            // to run.  So 5 other CPUs is right to have everyone
            // finish after ~99 minutes.
            string(name: 'NUM_WORKER_MACHINES', value: "3"),
            string(name: 'CLIENTS_PER_WORKER', value: "2"),
          ]);
}

def runSmokeTests() {
    build(job: '../deploy/e2e-test',
          parameters: [
             string(name: 'SLACK_CHANNEL', value: "#1s-and-0s"),
             string(name: 'GIT_REVISION', value: params.GIT_REVISION),
            // It takes about 15 minutes to run all the e2e tests when
            // using the default of 20 workers.  There's no need for
            // us to finish way before the parallel unittest-run does,
            // though, and that one takes ~65 minutes.  So let's just
            // run 5 workers instead, so we take about an hour.
            string(name: 'NUM_WORKER_MACHINES', value: "5"),
          ]);
}

onMaster('5h') {
   // We want to notify that make-allcheck started, but don't need to
   // notify how it did because the sub-jobs will each notify
   // individually.
   // TODO(csilvers): remove onMaster(), and just allocate
   // the executor in the notify clean-up steps.
   notify(
      [slack: [channel: '#1s-and-0s',
               when: ['STARTED', 'ABORTED']],
       bugtracker: [project: 'Infrastructure',
                    when: ['FAILURE']],
       aggregator: [initiative: 'infrastructure',
                    when: ['SUCCESS', 'BACK TO NORMAL',
                           'FAILURE', 'ABORTED', 'UNSTABLE']]]) {
      // We need this only to get the secrets to send to slack/asana/etc
      // when there are failures.
      // TODO(csilvers): move those secrets somewhere else instead.
      kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", "master", null);

      parallel([
         "webapp-test": { runAllTests(); },
         "smoke-tests": { runSmokeTests(); },
      ])
   }
}
