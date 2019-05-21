// A helper file, imported by deploy-webapp.groovy, for talking to slack.
//
// deploy-webapp.groovy writes to slack in a lot of different cases.
// And it uses very customized slack messages to provide a good user
// experience.  This leads to a lot of very verbose text to specify
// the exact slack messages.  (It doesn't help we choose to support
// both html and text.)  To keep deploy-webapp.groovy readable, I
// put all the slack messaging here in a separate file.
//
// deploy-webapp.groovy can use this via something like:
//    alert = load("jenkins-jobs/deploy-webapp_slackmsgs.groovy");
// and then
//    _alert(alertMsgs.SETTING_DEFAULT, [combinedVersion: COMBINED_VERSION,
//                                       abortUrl: "${env.BUILD_URL}stop"]);
// The second parameter is a dict used to interpolate variables into
// the string using a python-like syntax of '%(varname)s'.

// The format for each of these messages is a dict that includes all
// the information needed to port a slack message.  Any of these may
// be missing if not appropriate for this message.
//    text: the main text of the message!  It can have interpolation
//          string.  Example: "Ready to deploy branch %(branch)s."
//    attachments: a list of dicts, each an attachment-dict as per
//        https://api.slack.com/docs/message-attachments.  If specified,
//        `text` is automatically added to the `fallback` field if it's
//        missing, but is otherwise ignored.  Any part may have
//        interpolation strings.
//    simpleMessage: if true, use normal Markdown rather than
//        formatting `text` as an attachment.
//    severity: 'info', 'warning', or 'error'.
//
// A note about formatting: a message can either be formatted "plain"
// (with markdown), as a single attachment, or as multiple attachments.
// The first has `text` plus `simpleMessage`==true.  The second has
// `text` plus `simpleMessage`==false.  The third has just
// `attachments`.


// A very simple whitespace markup language: two+ blank lines -> newline,
// all other newlines -> space
@NonCPS     // for replaceAll()
def _textWrap(String s) {
   return s.replaceAll("\\n+", { match -> match.length() > 1 ? "\n" : " "; });
}


// TODO(csilvers): include jenkins links for aborting and set-default/finish.
// An attachment field for instructions on how to abort.
_abortField = [
   "title": "abort the deploy :skull:",
   "value": ":speech_balloon: `sun: abort` (or <%(abortUrl)s|click me>)",
   "short": true,
];


ROLLING_BACK = [
   "severity": "info",
   "text": _textWrap("""\
Automatically rolling the default back to %(rollbackToAsVersion)s, if needed,
and tagging %(gitTag)s as bad (in git).  This may take a few minutes, after
which you can decide whether to try again, or move on to the next deploy.
""")];


ROLLED_BACK_TO_BAD_VERSION = [
   "severity": "warning",
   "text": _textWrap("""\
:poop: WARNING: Rolled back to %(rollbackToAsVersion)s, but that version has
itself been marked as bad.  You may need to manually run
set_default.py to roll back to a safe version.  (Run 'git tag' to see
all versions, good and bad.)
""")];


ROLLBACK_FAILED = [
   "severity": "critical",
   // the <!subteam> thing is "@dev-support".
   "text": _textWrap("""\
:ohnoes: :ohnoes: Auto-rollback failed!
Roll back to %(rollbackToAsVersion)s manually by running
`deploy/rollback.py --bad '%(gitTag)s' --good '%(rollbackTo)s'`
cc <!subteam^S41PPSJ21>
""")];


JUST_DEPLOYED = [
    "severity": "info",
    "simpleMessage": true,
    "text": _textWrap("""\
I've just uploaded <%(deployUrl)s|%(version)s>
(containing `%(branches)s`) to %(services)s."""),
];


_settingDefaultText = _textWrap("""\
I'm setting default to `%(combinedVersion)s`, and monitoring the logs.
If you notice a problem before monitoring finishes, you can cancel the
deploy.
""");


SETTING_DEFAULT = [
   "severity": "info",
   "text": ("${_settingDefaultText}\n" +
            "To cancel, type `sun: abort` or visit %(abortUrl)s"),
   "attachments": [
      [
         "pretext": _settingDefaultText,
         "fields": [_abortField],
         "mrkdwn_in": ["pretext", "fields"],
      ],
   ],
];


VERSION_NOT_CHANGED = [
   "severity": "warning",
   "simpleMessage": true,
   "text": _textWrap("""\
I've gotten bored waiting for the version to start changing.
Something may be wrong; in any case, after it changes,
you'll need to start the
<https://jenkins.khanacademy.org/job/deploy/job/e2e-test/build|smoke tests>
yourself, or abort.
"""),
];


FAILED_MERGE_TO_MASTER = [
   "severity": "error",
   // the <!subteam> thing is "@dev-support".
   "text": _textWrap("""\
:ohnoes: Deploy of `%(combinedVersion)s` (branch `%(branch)s`)
succeeded, but we did not successfully merge `%(branch)s` into
`master`. <!subteam^S41PPSJ21> will need to fix things up, or
see "Advanced Troubleshooting" in the deploy system user guide.
""")];


FAILED_WITHOUT_ROLLBACK = [
   "severity": "error",
   "text": _textWrap("""\
:ohnoes: Deploy of `%(version)s` (branch `%(branch)s`)
to %(services)s failed: %(why)s.
""")];


FAILED_WITH_ROLLBACK = [
   "severity": "error",
   "text": _textWrap("""\
:ohnoes: Deploy of `%(combinedVersion)s` (branch `%(branch)s`) failed: %(why)s.
Rolled back to %(rollbackToAsVersion)s.
""")];


DATASTORE_BIGQUERY_ADAPTER_JAR_NOT_SWITCHED = [
   "severity": "error",
   // the <!subteam> thing is "@dev-support".
   "text": _textWrap("""\
:ohnoes: Switch from datastore_bigquery_adapter.`%(newVersion)`.jar failed.
<!subteam^S41PPSJ21> needs to look at why switching the version from
gs://khanalytics/datastore-bigquery-adapter-jar-versions/ to
gs://khanalytics/datastore_bigquery_adapter.jar, failed.
""")];


SUCCESS = [
   "severity": "info",
   "simpleMessage": true,
   "text": _textWrap("""\
:party_dino: Deploy of `%(combinedVersion)s` (branch `%(branch)s`) succeeded!
Time for a happy dance!
*Reminder:* post about your deploy in <#C2RFQGYKU>
""")]; // link to #release-notes


BUILDMASTER_OUTAGE = [
   "severity": "error",
   "simpleMessage": true,
   "text": _textWrap("""\
:ohnoes: Jenkins is unable to reach buildmaster right now while trying to verify
that %(step)s. You'll want to check the <%(logsUrl)s|jenkins logs> directly to
tell Jenkins to proceed.
Perhaps buildmaster is down. <!subteam^S41PPSJ21> will look into it.
""")];

return this;
