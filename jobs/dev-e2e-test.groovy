// The pipeline job to test the dev-only e2e tests
//
// This just calls webapp-test with different paramters in order to do the
// running.

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

).addStringParam(
   "SLACK_CHANNEL",
   "The slack channel to which to send failure alerts.",
   "#classroom-eng"

).addBooleanParam(
   "FORCE",
   """If set, run the tests even if the database says that the tests
have already passed at this GIT_REVISION.""",
   false

).addCronSchedule(
   '0 5,8,11,14,17,20 * * 1-5'        // Run every three hours during workdays

).apply();

currentBuild.displayName = ("${currentBuild.displayName} " +
                            "(${params.GIT_REVISION})");


onMaster('5h') {
   notify(
      [slack: [channel: params.SLACK_CHANNEL,
               when: ['STARTED', 'ABORTED']],
       aggregator: [initiative: 'infrastructure',
                    when: ['SUCCESS', 'BACK TO NORMAL',
                           'FAILURE', 'ABORTED', 'UNSTABLE']]]) {
      // We need this only to get the secrets to send to slack/asana/etc
      // when there are failures.
      // TODO(csilvers): move those secrets somewhere else instead.
      kaGit.safeSyncToOrigin("git@github.com:Khan/webapp", "master", null);

      build(job: '../deploy/webapp-test',
            parameters: [
               string(name: 'GIT_REVISION', value: params.GIT_REVISION),
               string(name: 'JOB_DISPLAY_NAME', value: "dev-e2e-test"),
               string(name: 'TEST_TYPE', value: "all_non_manual"),
               string(name: 'MAX_SIZE', value: "huge"),
               string(name: 'TEST_FILE_GLOB', value: "*_e2etest.py"),
               string(name: 'NUM_RETRIES', value: "1"),
               booleanParam(name: 'FAILFAST', value: params.FAILFAST),
               string(name: 'SLACK_CHANNEL', value: params.SLACK_CHANNEL),
               booleanParam(name: 'FORCE', value: params.FORCE),
            ]);

   }
}
