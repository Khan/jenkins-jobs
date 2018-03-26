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
//    _alert(alert.STARTING_DEPLOY, [deployKind: kind, branch: GIT_COMMIT]);
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

STARTING_DEPLOY = [
   "severity": "info",
   "simpleMessage": true,
   "text": _textWrap("""\
*Starting a %(deployType)sdeploy of branch `%(branch)s`.* :treeeee:

I'll post again when tests are done & the deploy is finished.
If you wish to cancel before then you can abort the deploy by
typing `sun: abort`.
""")];


ROLLING_BACK = [
   "severity": "info",
   "text": _textWrap("""\
Automatically rolling the default back to %(rollbackToAsVersion)s and tagging
%(gitTag)s as bad (in git)
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
   "text": _textWrap("""\
:ohnoes: :ohnoes: Auto-rollback failed!
Roll back to %(rollbackToAsVersion)s manually by running
`deploy/rollback.py --bad '%(gitTag)s' --good '%(rollbackTo)s'`
""")];


JUST_DEPLOYED = [
    "severity": "info",
    "simpleMessage": true,
    "text": _textWrap("""\
I've just uploaded <%(deployUrl)s|%(version)s>
(containing `%(branches)s`)."""),
];


MANUAL_TEST_THEN_SET_DEFAULT = [
   "severity": "info",
   "text": _textWrap("""\
%(deployUrl)s (branch %(branch)s) is uploaded to appengine!
Do some manual testing on it, perhaps via
`tools/manual_webapp_testing.py %(deployUrl)s`.
%(maybeVmMessage)sThen, either:
\n- set it as default: type `sun: set default` or visit %(setDefaultUrl)s
\n- abort the deploy: type `sun: abort` or visit %(abortUrl)s
"""),
   "attachments": [
      [
         "pretext": _textWrap("""\
<%(deployUrl)s|%(combinedVersion)s> (branch `%(branch)s`)
is uploaded to App Engine!

Do some manual testing, perhaps via
`tools/manual_webapp_testing.py %(deployUrl)s`, while I run the
<https://jenkins.khanacademy.org/job/deploy/job/e2e-test/lastBuild/|end-to-end tests>.
%(maybeVmMessage)sThen, either:"""),
         "fields": [
            [
               "title": "all looks good :rocket:",
               "value": (":speech_balloon: `sun: set default` " +
                         "(or <%(setDefaultUrl)s|use jenkins directly>)"),
               "short": true,
            ],
            _abortField,
         ],
         "color": "good",
         "mrkdwn_in": ["pretext", "text", "fields"],
      ],
   ],
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


STILL_WAITING = [
   "severity": "warning",
   "simpleMessage": true,
   "text": _textWrap("""\
Are you ready to %(action)s?
Waited %(minutesSoFar)s minutes so far.
Will wait %(minutesRemaining)s minutes more, then abort.
"""),
];

FINISH_WITH_WARNING = [
   "severity": "warning",
   "text": _textWrap("""\
:ohnoes: Monitoring detected errors for the new default
(%(combinedVersion)s). Please double-check manually that
everything is okay at https://www.khanacademy.org and in
the logs.  Then:
\n- finish up: type `sun: finish up` or visit %(finishUrl)s
\n- abort and roll back: type `sun: abort` or visit %(abortUrl)s
"""),
   "attachments": [
      [
         "pretext": _textWrap("""\
:ohnoes: Monitoring detected errors for the new default
(%(combinedVersion)s). Please double-check manually that everything is
okay on <https://www.khanacademy.org|the site> and in <%(logsUrl)s|the logs>.
"""),
         "fields": [
            ["title": "deploy anyway :yolo:",
             "value": (":speech_balloon: `sun: finish up` " +
                       "(or <%(finishUrl)s|use jenkins directly>)"),
             "short": true,
            ],
            _abortField,
         ],
         "mrkdwn_in": ["fields", "text", "pretext"],
      ],
   ],
];


FINISH_WITH_NO_WARNING = [
   "severity": "info",
   "text": _textWrap("""\
Monitoring passed for the new default (%(combinedVersion)s).
You can double-check manually that everything is okay at
https://www.khanacademy.org and in the logs.  Then:
\n- finish up: type `sun: finish up` or visit %(finishUrl)s
\n- abort and roll back: type `sun: abort` or visit %(abortUrl)s
"""),
   "attachments": [
      [
         "pretext": _textWrap("""\
Monitoring passed for the new default
(%(combinedVersion)s). You can double-check manually that everything is
okay on <https://www.khanacademy.org|the site> and in <%(logsUrl)s|the
logs>.
"""),
         "fields": [
            ["title": "finish up :checkered_flag:",
             "value": (":speech_balloon: `sun: finish up` " +
                       "(or <%(finishUrl)s|use jenkins directly>)"),
             "short": true,
            ],
            _abortField,
         ],
         "mrkdwn_in": ["fields", "text", "pretext"],
      ],
   ],
];


FAILED_MERGE_TO_MASTER = [
   "severity": "error",
   "text": _textWrap("""\
:ohnoes: Deploy of `%(combinedVersion)s` (branch `%(branch)s`)
succeeded, but we did not successfully merge `%(branch)s` into
`master`. Merge and then push manually via
`git --no-verify push origin master`.
""")];


FAILED_WITHOUT_ROLLBACK = [
   "severity": "error",
   "text": _textWrap("""\
:ohnoes: Deploy of `%(combinedVersion)s` (branch `%(branch)s`) failed: %(why)s.
""")];


FAILED_WITH_ROLLBACK = [
   "severity": "error",
   "text": _textWrap("""\
:ohnoes: Deploy of `%(combinedVersion)s` (branch `%(branch)s`) failed: %(why)s.
Rolled back to %(rollbackToAsVersion)s.
""")];


SUCCESS = [
   "severity": "info",
   "simpleMessage": true,
   "text": _textWrap("""\
:party_dino: Deploy of `%(combinedVersion)s` (branch `%(branch)s`) succeeded!
Time for a happy dance!
""")];


return this;
