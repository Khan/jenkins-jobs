#!/usr/bin/env python

"""Analyzes the output of 'make check JUNIT_XML=1' and alerts on error.

When we run with JUNIT_XML, a summary of the test output it put inside
genfiles.  Likewise, a summary of the jstest output is put in
genfiles, as is a summary of the lint output.

This script analyzes these three output files and takes action --
namely, talking to hipchat -- for test-failures that it sees in them.
The hipchat messages have nice links to more details.

This script exits with rc 0 if no errors were seen, or a positive rc
(the number of errors) if errors were seen.

TEST OUTPUT
-----------
The XML test runner runtests.py uses will create a directory structure of
files with xUnit-compatible XML:

  genfiles/test-reports/
    TEST-unisubs.handlers_test.ImportHandlerTest-20130619153121.xml
    TEST-warmup_test.WarmupTest-20130619153121.xml

Here is a sample of the XML format. Note the "failure" node due to a failed
assertion. If an exception was raised it would be an "error" node instead.

<?xml version="1.0" ?>
<testsuite errors="0" failures="1" tests="1" time="0.084">
           name="warmup_test.WarmupTest-20130619153121"
  <testcase classname="warmup_test.WarmupTest"
            name="test_warmup_sanity_check" time="0.084">
    <failure message="False is not true" type="AssertionError">...</failure>
  </testcase>
  <system-out>...</system-out>
  <system-err>...</system-err>
</testsuite>

JSTEST OUTPUT
-------------
We look for two things in the output of 'make jstest':

1) Lines that begin with at least one tab character indicate test
failures and their detail output, e.g.,

  \tScratchpad Output Exec getImage with Draw Loop (Version: 3)
  \t\ttimeout of 2000ms exceeded

2) We extract the failure count from the final line of output that
provides a summary, e.g., "Finished running 121 tests, with 115 passes
and 6 failures."

LINT OUTPUT
-----------
The linter outputs one line per error, with that line indicating where
the error is.  Easy peasy.
"""

import argparse
import logging
import lxml.etree
import os
import re
import sys

# This requires having secrets.py (or ka_secrets.py) on your PYTHONPATH!
sys.path.append(os.path.join(os.path.abspath(os.path.dirname(__file__)),
                             'alertlib'))
import alertlib


# We can run tests in a mode where a python tests runs jstests or lint
# checks in a subshell.  This maps the python test name to a regexp
# that extracts the jstest/lint output from the python xml output.
_ALTERNATE_TESTS = {
    'testutil.manual_test.LintTest':
        ('lint',
         re.compile('AssertionError: LINT ERRORS:\s*(.*)', re.DOTALL)),
    'testutil.manual_test.JsTest':
        ('javascript',
         re.compile('AssertionError: JSTEST ERRORS:\s*(.*)', re.DOTALL)),
}

# Maps 'lint' to the alternate-test lint output.
_ALTERNATE_TESTS_VALUES = {}


def _alert(hipchat_room, slack_channel, failures, test_type, truncate=10,
           num_errors=None):
    """Alert with the first truncate failures, adding a header.

    If num_errors is not equal to len(failures), you can pass it in.
    (This happens when a system prints two error-lines for each file,
    for instance.)

    failures should be a list of tuples (hipchat link, slack link)

    If hipchat_room is None or the empty string, we suppress alerting
    to hipchat, and only log.
    """
    if not failures:
        return

    alert_lines = failures[:truncate]

    if num_errors is None:
        num_errors = len(failures)
    if num_errors == 1:
        pretext = 'Failed 1 %s' % test_type
    else:
        pretext = 'Failed %s %ss' % (num_errors, test_type)

    if len(failures) > truncate:
        alert_lines.append('...', '...')

    if hipchat_room:
        html_text = '%s:<br>\n%s' % (
            pretext, '<br>\n'.join(alert[0] for alert in alert_lines))
        html_alert = alertlib.Alert(html_text, severity=logging.ERROR,
                                    html=True)
        html_alert.send_to_hipchat(hipchat_room, sender='Jenny Jenkins')

    fallback_text = '%s:\n%s' % (
        pretext, '\n'.join(alert[1] for alert in alert_lines))
    slack_attachment = {
        'fallback': fallback_text,
        'pretext': pretext,
        'text': '\n'.join(alert[1] for alert in alert_lines),
        'color': 'danger',
    }
    slack_alert = alertlib.Alert(fallback_text, severity=logging.ERROR)
    slack_alert.send_to_logs()

    if slack_channel:
        # TODO(benkraft): find a retina-quality :lilturtle: and use that here
        slack_alert.send_to_slack(slack_channel, sender='Testing Turtle',
                                  icon_emoji=':turtle:',
                                  attachments=[slack_attachment])


def _find(rootdir):
    """Yield every file under root."""
    for (root, _, files) in os.walk(rootdir):
        for f in files:
            yield os.path.join(root, f)


def find_bad_testcases(test_reports_dir):
    """Yield each failure or error testcase as lxml.etree.Element

    See the xUnit XML format described in the module docstring to see the
    testcase element tree in context.
    """
    for filename in _find(test_reports_dir):
        doc = lxml.etree.parse(filename)

        for testcase in doc.xpath("/testsuite/testcase"):
            if testcase.get("classname") in _ALTERNATE_TESTS:
                (test_type, _) = _ALTERNATE_TESTS[testcase.get("classname")]
                _ALTERNATE_TESTS_VALUES[test_type] = ""

        for bad_testcase in doc.xpath("/testsuite/testcase[failure or error]"):
            if bad_testcase.get("classname") not in _ALTERNATE_TESTS:
                yield bad_testcase
            else:
                # If we're an alternate test (we're the test that runs
                # the linter, say), instead of reporting the error
                # here, we store its value in a global, and report the
                # error along with the lint failures.
                error_text = bad_testcase.getchildren()[0].text.rstrip()
                test_type, regex = _ALTERNATE_TESTS[
                    bad_testcase.get("classname")
                ]
                m = regex.search(error_text)
                assert m, error_text
                _ALTERNATE_TESTS_VALUES[test_type] = m.group(1)


def add_links(build_url, testcase):
    """Return a tuple of strings (hipchat link, slack link)

    Links to the testcase result in the Jenkins build at build_url.
    """
    display_name = "%s.%s" % (testcase.get("classname"),
                              testcase.get("name"))
    # the "classname" attribute is actually "module.of.TestCase"
    module, classname = testcase.get("classname").rsplit(".", 1)
    url = "%s/testReport/junit/%s/%s/%s/" % (
        build_url, module, classname, testcase.get("name"))

    return ('<a href="%s">%s</a>' % (url, display_name),
            '<%s|%s>' % (url, display_name))


def report_test_failures(test_reports_dir, jenkins_build_url,
                         hipchat_room, slack_channel):
    """Alert for test (as opposed to jstest or lint) failures.

    Returns the number of errors seen.
    """
    if not os.path.exists(test_reports_dir):
        return 0

    jenkins_build_url = jenkins_build_url.rstrip("/")

    # Sort output so it is easy to compare across runs.
    failures = []
    for bad_testcase in find_bad_testcases(test_reports_dir):
        failures.append(add_links(jenkins_build_url, bad_testcase))
    failures.sort()
    _alert(hipchat_room, slack_channel, failures, 'Python test')
    return len(failures)


def report_jstest_failures(jstest_reports_file, hipchat_room, slack_channel):
    """Alert for jstest (as opposed to python-test or lint) failures."""
    if not os.path.exists(jstest_reports_file):
        return 0

    failures = []
    num_errors = 0
    with open(jstest_reports_file, 'rU') as infile:
        for line in infile.readlines():
            if line.startswith('\t'):
                failures.append(line[1:])  # trim first \t
            elif line.startswith('Finished running '):
                m = re.match('Finished running \d+ tests, '
                             'with \d+ passes and (\d+) failures.', line)
                assert m, line
                num_errors += int(m.group(1))
            elif line.startswith('Timeout: tests did not start'):
                failures.append(line)
                # Timeouts are ignored in the "Finished running x tests"
                # reports, so we have to count these errors manually.
                num_errors += 1
            elif line.startswith('PhantomJS has crashed.'):
                failures.append(line)
                # Crashes are ignored in the "Finished running x tests"
                # reports, so we have to count these errors manually.
                num_errors += 1
    _alert(hipchat_room, slack_channel, failures, 'JavaScript test',
           num_errors=num_errors)
    return num_errors


def report_lint_failures(lint_reports_file, hipchat_room, slack_channel):
    """Alert for lint (as opposed to python-test or jstest) failures."""
    if not os.path.exists(lint_reports_file):
        return 0

    with open(lint_reports_file, 'rU') as infile:
        failures = infile.readlines()
    _alert(hipchat_room, slack_channel, failures, 'Lint check')
    return len(failures)


def main(jenkins_build_url, test_reports_dir,
         jstest_reports_file, lint_reports_file, hipchat_room, slack_channel,
         dry_run):
    if dry_run:
        alertlib.enter_test_mode()
        logging.getLogger().setLevel(logging.INFO)

    num_errors = 0

    if test_reports_dir:
        num_errors += report_test_failures(test_reports_dir,
                                           jenkins_build_url,
                                           hipchat_room, slack_channel)

    # If we ran any of the alternate-tests above, we'll fake having
    # emitted otuput to the output file.

    if jstest_reports_file:
        if 'javascript' in _ALTERNATE_TESTS_VALUES:
            with open(jstest_reports_file, 'w') as f:
                # TODO(csilvers): send a cStringIO to report_* instead.
                f.write(_ALTERNATE_TESTS_VALUES['javascript'])
        num_errors += report_jstest_failures(jstest_reports_file,
                                             hipchat_room, slack_channel)

    if lint_reports_file:
        if 'lint' in _ALTERNATE_TESTS_VALUES:
            with open(lint_reports_file, 'w') as f:
                f.write(_ALTERNATE_TESTS_VALUES['lint'])
        num_errors += report_lint_failures(lint_reports_file,
                                           hipchat_room, slack_channel)

    return num_errors


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--jenkins_build_url',
                        help=('$BUILD_URL from the Jenkins environment; '
                              'e.g. http://jenkins.ka.org/job/run-tests/1/'))
    parser.add_argument('--test_reports_dir',
                        default='genfiles/test-reports',
                        help='Where runtests.py --xml puts the output xml')
    parser.add_argument('--jstest_reports_file',
                        default='genfiles/jstest_output.txt',
                        help='Where "make jstest" puts the output report')
    parser.add_argument('--lint_reports_file',
                        default='genfiles/lint_errors.txt',
                        help='Where "make lint" puts the output report')
    parser.add_argument('-c', '--hipchat-room',
                        default="1s and 0s",
                        help=("What room to send hipchat notifications to; "
                              "set to the empty string to turn off hipchat "
                              "notifications"))
    parser.add_argument('-S', '--slack-channel',
                        default="#1s-and-0s",
                        help=("What channel to send slack notifications to; "
                              "set to the empty string to turn off slack "
                              "notifications"))
    parser.add_argument('--dry-run', '-n', action='store_true',
                        help='Log instead of sending to hipchat.')
    args = parser.parse_args()

    rc = main(args.jenkins_build_url, args.test_reports_dir,
              args.jstest_reports_file, args.lint_reports_file,
              args.hipchat_room, args.slack_channel, args.dry_run)
    # We cap num-errors at 127 because rc >= 128 is reserved for signals.
    sys.exit(min(rc, 127))
