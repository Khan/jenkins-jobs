// Groovy script that points people to the new way to deploy a znd.
//
// ZNDs are now deployed by webapp's deploy-znd GitHub Actions workflow,
// so this job only says where to go instead.  It keeps the Slack
// parameters so that anyone still running an old deploy_znd.py hears
// about it in the channel they asked for.

@Library("kautils")
// Classes we use, under jenkins-jobs/src/.
import org.khanacademy.Setup;
// Vars we use, under jenkins-jobs/vars/.  This is just for documentation.
//import vars.notify
//import vars.onMaster


RETIRED_MESSAGE = """\
This job is retired.  Deploy ZNDs with webapp's deploy-znd GitHub \
Actions workflow instead: either update webapp and run \
`deploy/deploy_znd.py` (it now dispatches the workflow with `gh`), or \
start it from https://github.com/Khan/webapp/actions/workflows/deploy-znd.yml"""

new Setup(steps

).addStringParam(
   "README",
   """<b>${RETIRED_MESSAGE}</b>""",
   ""

).addStringParam(
   "SLACK_CHANNEL",
   """The slack channel to which to send the message saying this job is
retired.  Set to @yourusername to send a Slack DM (from Slackbot).""",
   "#1s-and-0s-deploys"

).addStringParam(
   "SLACK_THREAD",
   """The slack thread (must be in SLACK_CHANNEL) to which to send that
message.  By default we do not send in a thread.""",
   ""

).apply();

currentBuild.displayName = "${currentBuild.displayName} (retired)";

onMaster('5m') {
   notify([slack: [channel: params.SLACK_CHANNEL,
                   thread: params.SLACK_THREAD,
                   sender: 'Mr Monkey',
                   emoji: ':monkey_face:',
                   when: ['FAILURE']]]) {
      notify.fail(RETIRED_MESSAGE);
   }
}
