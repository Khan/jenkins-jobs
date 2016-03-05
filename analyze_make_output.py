#!/usr/bin/env python

"""Analyzes the output of 'make check JUNIT_XML=1' and alerts on error.

When we run with JUNIT_XML, a summary of the test output it put inside
genfiles.  Likewise, a summary of the jstest output is put in
genfiles, as is a summary of the lint output.  When we run end-to-end tests,
they output a file similar to the python tests.

This script analyzes any or all of these output files and takes action --
namely, talking to Slack -- for test-failures that it sees in them.
The Slack messages have nice links to more details.

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
From the JS test output, we don't try to identify individual test case
failures, we just identify which test files failed and report those.

We also check for a few abnormal failure cases like kake build failures or
PhantomJS crashes.

LINT OUTPUT
-----------
The linter outputs one line per error, with that line indicating where
the error is.  Easy peasy.

END TO END TEST OUTPUT
----------------------
The E2E test runner also uses xUnit-compatible XML.  The format is slightly
different from that of runtests.py, in three ways:

1. All test results are in a single file.
2. The toplevel element is <testsuites> and may contain many <testsuite>
elements.
3. The XML file uses a namespace of "http://www.w3.org/1999/xhtml".

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
    'testutil.lint_test.LintTest':
        ('lint',
         re.compile('AssertionError: LINT ERRORS:\s*(.*)', re.DOTALL)),
    'testutil.js_test.JsTest':
        ('javascript',
         re.compile('AssertionError: JSTEST ERRORS:\s*(.*)', re.DOTALL)),
}

# Maps 'lint' to the alternate-test lint output.
_ALTERNATE_TESTS_VALUES = {}


def _alert(slack_channel, failures, test_type, truncate=10,
           num_errors=None, extra_text=''):
    """Alert with the first truncate failures, adding a header.

    If num_errors is not equal to len(failures), you can pass it in.
    (This happens when a system prints two error-lines for each file,
    for instance.)

    failures should be a list of strings with slack links.

    If slack_channel is None or the empty string, we suppress alerting to
    Slack, and only log.
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

    if extra_text:
        pretext = '%s %s' % (pretext, extra_text)

    if len(failures) > truncate:
        alert_lines.append('...')

    text = '\n'.join(alert for alert in alert_lines)
    fallback_text = '%s:\n%s' % (pretext, text)
    attachment = {
        'fallback': fallback_text,
        'pretext': pretext,
        'text': text,
        'color': 'danger',
    }
    alert = alertlib.Alert(fallback_text, severity=logging.ERROR)
    alert.send_to_logs()

    if slack_channel:
        alert.send_to_slack(slack_channel, sender='Testing Turtle',
                            icon_emoji=':turtle:', attachments=[attachment])


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


def find_bad_e2e_testcases(test_reports_file):
    doc = lxml.etree.parse(test_reports_file)
    return doc.xpath('/xhtml:testsuites/xhtml:testsuite'
                     '/xhtml:testcase[xhtml:failure or xhtml:error]',
                     namespaces={'xhtml': 'http://www.w3.org/1999/xhtml'})


def _clean_test_name(name):
    """Sanitize a test case name in approximately the same way as Jenkins.

    Jenkins converts any character for which `Character.isJavaIdentifierPart()`
    is false to an underscore.  This is hopefully a good enough approximation,
    at least for ASCII text.

    For Jenkins's version, see hudson.tasks.junit.CaseResult.getSafeName in the
    junit plugin, currently at
    https://github.com/jenkinsci/junit-plugin/blob/master/src/main/java/hudson/tasks/junit/CaseResult.java#L300
    """
    return re.sub(r'[^a-zA-Z0-9$]', '_', name)


def _clean_class_name(name):
    """Sanitize a test suite name the same way as Jenkins.

    Jenkins converts any character in r"/\:?#%" to an underscore.

    For Jenkins's version, see hudson.tasks.test.TestObject.safe in the junit
    plugin, currently at
    https://github.com/jenkinsci/junit-plugin/blob/master/src/main/java/hudson/tasks/test/TestObject.java#L386
    """
    return re.sub(r'[/\:?#%]', '_', name)


def _clean_link_text(name):
    """Remove angle brackets from the link text, and replace them.

    Slack's links are delimited by angle brackets, so a link's display text
    can't contain them.  Luckily, Unicode has a lot of vaguely similar-looking
    characters, and the text is intended for human consumption; we'll use
    U+2039 and U+203A.
    """
    return name.replace(">", u"\u203a").replace("<", u"\u2039")


def link_to_jenkins_test_report(display_name, build_url, module,
                                classname, testname):
    url = "%s/testReport/junit/%s/%s/%s/" % (
        build_url, module, classname, testname)
    return '<%s|%s>' % (url, display_name)


def add_links(build_url, testcase, sep='.'):
    """Return a slack-style link.

    Links to the testcase result in the Jenkins build at build_url.

    sep should be the separator to be used between the test's class name and
    the test's name in the display name of the test, traditionally ".".
    """
    # the "classname" attribute is actually "module.of.TestCase"
    name_parts = testcase.get("classname").split(".")
    name_parts.append(testcase.get("name"))
    display_name = _clean_link_text(sep.join(name_parts))
    module, classname = testcase.get("classname").rsplit(".", 1)
    return link_to_jenkins_test_report(
        display_name, build_url,
        _clean_class_name(module),
        _clean_class_name(classname),
        _clean_test_name(testcase.get("name")))


def report_test_failures(test_reports_dir, jenkins_build_url, slack_channel):
    """Alert for test (as opposed to jstest or lint) failures.

    Returns the number of errors seen.
    """
    if not os.path.exists(test_reports_dir):
        return 0

    # Sort output so it is easy to compare across runs.
    failures = set()
    for bad_testcase in find_bad_testcases(test_reports_dir):
        failures.add(add_links(jenkins_build_url, bad_testcase))
    _alert(slack_channel, sorted(failures), 'Python test')
    return len(failures)


def report_jstest_failures(jstest_reports_file, jenkins_build_url,
                           slack_channel):
    """Alert for jstest (as opposed to python-test or lint) failures."""
    if not os.path.exists(jstest_reports_file):
        return 0

    failures = []

    def _add_failure(text):
        failures.append(link_to_jenkins_test_report(text, jenkins_build_url,
                                                    'js_test',
                                                    'JsTest',
                                                    'test_run_jstests'))

    with open(jstest_reports_file, 'rU') as infile:
        for line in infile.readlines():
            if 'FATAL ERROR building' in line:
                # If the test bundle failed building, we'll see a log line like
                # this from kake, and the tests didn't even start.
                _add_failure(line)
            elif line.startswith('PhantomJS has crashed.'):
                # If PhantomJS crashes, we might not even get to the test
                # failure reporting.
                _add_failure(line)
            elif line.startswith('Timed out waiting for tests'):
                # We bail on timeouts, so we won't get to the test failure
                # reporting, because the tests might not even be running!
                _add_failure(line)
            elif line.startswith('tools/runjstests.py -r browser'):
                # We emit this line in case of failures to make it easy to
                # re-run only the failed tests.
                bad_files = line[len('tools/runjstests.py -r browser'):]
                map(_add_failure, bad_files.split())

    _alert(slack_channel, failures, 'JavaScript test',
           num_errors=len(failures))
    return len(failures)


def report_lint_failures(lint_reports_file, slack_channel):
    """Alert for lint (as opposed to python-test or jstest) failures."""
    if not os.path.exists(lint_reports_file):
        return 0

    with open(lint_reports_file, 'rU') as infile:
        failures = set(infile.readlines())
    _alert(slack_channel, sorted(failures), 'Lint check')
    return len(failures)


def report_e2e_failures(e2e_test_reports_file, jenkins_build_url,
                        slack_channel):
    if not os.path.exists(e2e_test_reports_file):
        return 0

    failures = set()
    for bad_testcase in find_bad_e2e_testcases(e2e_test_reports_file):
        failures.add(add_links(jenkins_build_url, bad_testcase, sep=': '))
    _alert(slack_channel, sorted(failures), 'end-to-end test',
           extra_text="(see the <%s/console|logs> for details)" %
           jenkins_build_url)
    return len(failures)


def main(jenkins_build_url, test_reports_dir,
         jstest_reports_file, lint_reports_file, e2e_test_reports_file,
         slack_channel, dry_run):
    if dry_run:
        alertlib.enter_test_mode()
        logging.getLogger().setLevel(logging.INFO)

    jenkins_build_url = jenkins_build_url.rstrip("/")
    num_errors = 0

    if test_reports_dir:
        num_errors += report_test_failures(test_reports_dir,
                                           jenkins_build_url,
                                           slack_channel)

    # If we ran any of the alternate-tests above, we'll fake having
    # emitted otuput to the output file.

    if jstest_reports_file:
        if 'javascript' in _ALTERNATE_TESTS_VALUES:
            with open(jstest_reports_file, 'w') as f:
                # TODO(csilvers): send a cStringIO to report_* instead.
                f.write(_ALTERNATE_TESTS_VALUES['javascript'])
        num_errors += report_jstest_failures(jstest_reports_file,
                                             jenkins_build_url,
                                             slack_channel)

    if lint_reports_file:
        if 'lint' in _ALTERNATE_TESTS_VALUES:
            with open(lint_reports_file, 'w') as f:
                f.write(_ALTERNATE_TESTS_VALUES['lint'])
        num_errors += report_lint_failures(lint_reports_file, slack_channel)

    if e2e_test_reports_file:
        num_errors += report_e2e_failures(e2e_test_reports_file,
                                          jenkins_build_url, slack_channel)

    return num_errors


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--jenkins_build_url',
                        help=('$BUILD_URL from the Jenkins environment; '
                              'e.g. http://jenkins.ka.org/job/run-tests/1/'))
    parser.add_argument('--test_reports_dir',
                        help='Where runtests.py --xml puts the output xml, '
                             'generally genfiles/test-reports.')
    parser.add_argument('--jstest_reports_file',
                        help='Where "make jstest" puts the output report, '
                             'generally genfiles/jstest_output.txt.')
    parser.add_argument('--lint_reports_file',
                        help='Where "make lint" puts the output report, '
                             'generally genfiles/lint_errors.txt.')
    parser.add_argument('--e2e_test_reports_file',
                        help='Where "end_to_end_webapp_testing.py" puts '
                             'the output report, generally '
                             'genfiles/end_to_end_test_output.xml.')
    parser.add_argument('-S', '--slack-channel',
                        default="#1s-and-0s",
                        help=("What channel to send slack notifications to; "
                              "set to the empty string to turn off slack "
                              "notifications"))
    parser.add_argument('--dry-run', '-n', action='store_true',
                        help='Log instead of sending to Slack.')
    args = parser.parse_args()

    rc = main(args.jenkins_build_url, args.test_reports_dir,
              args.jstest_reports_file, args.lint_reports_file,
              args.e2e_test_reports_file, args.slack_channel, args.dry_run)
    # We cap num-errors at 127 because rc >= 128 is reserved for signals.
    sys.exit(min(rc, 127))
