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
   "FAILFAST",
   "If set, stop running tests after the first failure.",
   false

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


// We want to notify that make-allcheck started, but don't need to
// notify how it did because the sub-jobs will each notify
// individually.
// TODO(csilvers): use notify.runWithNotification instead, and
//     just allocate the executor in the notify clean-up steps.
notify(
   [slack: [channel: '#1s-and-0s',
            when: ['STARTED', 'ABORTED']],
    bugtracker: [project: 'Infrastructure',
                 when: ['FAILURE']],
    aggregator: [initiative: 'infrastructure',
                 when: ['SUCCESS', 'BACK TO NORMAL',
                        'FAILURE', 'ABORTED', 'UNSTABLE']],
    timeout: "5h"]) {
   // We need this only to get the secrets to send to slack/asana/etc
   // when there are failures.
   // TODO(csilvers): move those secrets somewhere else instead.
   kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", "master", null);

   build(job: '../deploy/webapp-test',
         parameters: [
            string(name: 'GIT_REVISION', value: params.GIT_REVISION),
            string(name: 'TEST_TYPE', value: "all"),
            string(name: 'MAX_SIZE', value: "huge"),
            booleanParam(name: 'FAILFAST', value: params.FAILFAST),
            string(name: 'SLACK_CHANNEL', value: "#1s-and-0s"),
            booleanParam(name: 'FORCE', value: params.FORCE),
         ]);

   build(job: '../deploy/e2e-test',
         parameters: [
            string(name: 'SLACK_CHANNEL', value: "#1s-and-0s"),
            string(name: 'GIT_REVISION', value: params.GIT_REVISION),
            booleanParam(name: 'FAILFAST', value: params.FAILFAST),
         ]);
}
