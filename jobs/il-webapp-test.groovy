// The pipeline job to run unit test suites for the Independent Learning team.
//
// This really just delegates to webapp-test for various branches.

@Library("kautils")
// Classes we use, under jenkins-tools/src/.
import org.khanacademy.Setup;
//import vars.notify


new Setup(steps

).addStringParam(
   "GIT_REVISION",
   """The git commit-hash to run tests at, or a symbolic name referring
to such a commit-hash.""",
   "il-support"

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
   'H 4 * * 1-5'        // Run every weekday morning at 4am

).apply();

currentBuild.displayName = ("${currentBuild.displayName} " +
                            "(${params.GIT_REVISION})");


// Don't actually need to notify anyone explicitly, since the sub-jobs will
// notify the appropriate people.
notify([]) {
   build(job: '../deploy/webapp-test',
         parameters: [
            string(name: 'GIT_REVISION', value: params.GIT_REVISION),
            string(name: 'TEST_TYPE', value: "all"),
            booleanParam(name: 'FAILFAST', value: params.FAILFAST),
            string(name: 'SLACK_CHANNEL', value: "#il-eng"),
            booleanParam(name: 'FORCE', value: params.FORCE),
         ]);
}

