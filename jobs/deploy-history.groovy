// Job to figure out the history of the last five webapp deploys
// This lets us quickly print (via a sun command) commits that were deployed
// recently to assist in debugging new issues.

@Library("kautils")

import org.khanacademy.Setup;
// Vars we use, under jenkins-tools/vars.  This is just for documentation.
//import vars.exec
//import vars.kaGit
//import vars.notify
//import vars.onMaster

new Setup(steps

).addStringParam(
    "SLACK_CHANNEL",
    "The slack channel to which to sent the report.",
    "#1s-and-0s-deploys"
).apply();

REPOSITORY = "git@github.com:Khan/webapp";
CHAT_SENDER = "History Hawk";
EMOJI = ":hawk:";

def doSetup() {
    onMaster('30m') {
        kaGit.safeSyncToOrigin(REPOSITORY, "master");
        dir("webapp") {
           sh("make clean_pyc");    // in case some .py files went away
           sh("make python_deps");
        }
    }
}

def _findMostRecentTags() {
    return exec.outputOf(['deploy/git_tags.py']).split()[-6..-1].reverse();
}

def _sendChangelog(def startCommitish, def endCommitish) {
    def start = kaGit.resolveCommitish(REPOSITORY, startCommitish);
    def end = kaGit.resolveCommitish(REPOSITORY, endCommitish);
    def pythonCommand = [
        "deploy/chat_messaging.py",
        "--chat-sender", CHAT_SENDER,
        "--icon-emoji", EMOJI,
        "--slack-channel", params.SLACK_CHANNEL,
        start,
        end
    ]
    // Secrets required to talk to slack.
    withSecrets() {
        dir('webapp') {
            exec(pythonCommand);
        }
    }
}

def _sendSimpleMessage(def msg) {
    def args = ["jenkins-tools/alertlib/alert.py",
                "--slack=${params.SLACK_CHANNEL}",
                "--chat-sender=${CHAT_SENDER}",
                "--icon-emoji=${EMOJI}",
                "--slack-simple-message"];
    // Secrets required to talk to slack.
    withSecrets() {
        sh("echo ${exec.shellEscape(msg)} | ${exec.shellEscapeList(args)}");
    }
}

def reportHistory() {
    onMaster('5m') {
        dir('webapp') {
            tags = _findMostRecentTags();
        }
        _sendSimpleMessage("Here's the five most recent successful deploys (newest first):");
        for (def i = 0; i < tags.size() - 1; i++) {
            def start = tags[i + 1];
            def end = tags[i];
            _sendSimpleMessage("*${end}*");
            _sendChangelog(start, end);
        }
    }
}

notify([slack: [channel: params.SLACK_CHANNEL,
                sender: CHAT_SENDER,
                emoji: EMOJI,
                when: ['FAILURE', 'UNSTABLE', 'ABORTED']],
        aggregator: [initiative: 'infrastructure',
                     when: ['SUCCESS', 'BACK TO NORMAL',
                            'FAILURE', 'ABORTED', 'UNSTABLE']]]) {
    stage("setup") {
        doSetup();
    }
    stage("changelog") {
        reportHistory();
    }
}
