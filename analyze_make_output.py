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

sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import appengine_tool_setup
appengine_tool_setup.fix_sys_path()

from third_party import alertlib


def _alert(failures, test_type, truncate=10, num_errors=None):
    """Alert with the first truncate failures, adding a header.

    If num_errors is not equal to len(failures), you can pass it in.
    (This happens when a system prints two error-lines for each file,
    for instance.)
    """
    if not failures:
        return

    alert_lines = failures[:truncate]

    if num_errors is None:
        num_errors = len(failures)
    if num_errors == 1:
        alert_lines.insert(0, 'Failed 1 %s:' % test_type)
    else:
        alert_lines.insert(0, 'Failed %s %ss:' % (num_errors, test_type))

    if len(failures) > truncate:
        alert_lines.append('...')

    (alertlib.Alert('<br>\n'.join(alert_lines),
                    severity=logging.ERROR,
                    html=True)
     .send_to_hipchat('1s and 0s', sender='Jenny Jenkins'))


def find_bad_testcases(test_reports_dir):
    """Yield each failure or error testcase as lxml.etree.Element

    See the xUnit XML format described in the module docstring to see the
    testcase element tree in context.
    """
    for filename in os.listdir(test_reports_dir):
        doc = lxml.etree.parse(os.path.join(test_reports_dir, filename))
        for bad_testcase in doc.xpath("/testsuite/testcase[failure or error]"):
            yield bad_testcase


def add_links(build_url, testcase):
    """Return '<a href="...">module.of.TestCase.test_name</a>'

    Links to the testcase result in the Jenkins build at build_url.
    """
    display_name = "%s.%s" % (testcase.get("classname"),
                              testcase.get("name"))
    # the "classname" attribute is actually "module.of.TestCase"
    module, classname = testcase.get("classname").rsplit(".", 1)
    return '<a href="%s/testReport/junit/%s/%s/%s/">%s</a>' % (
        build_url, module, classname, testcase.get("name"), display_name)


def report_test_failures(test_reports_dir, jenkins_build_url):
    """Alert for test (as opposed to jstest or lint) failures.

    Returns the number of errors seen.
    """
    jenkins_build_url = jenkins_build_url.rstrip("/")

    # Sort output so it is easy to compare across runs.
    failures = []
    for bad_testcase in find_bad_testcases(test_reports_dir):
        failures.append(add_links(jenkins_build_url, bad_testcase))
    failures.sort()
    _alert(failures, 'Python test')
    return len(failures)


def report_jstest_failures(jstest_reports_file):
    """Alert for jstest (as opposed to python-test or lint) failures."""
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
                num_errors = int(m.group(1))
    _alert(failures, 'JavaScript test', num_errors=num_errors)
    return num_errors


def report_lint_failures(lint_reports_file):
    """Alert for lint (as opposed to python-test or jstest) failures."""
    with open(lint_reports_file, 'rU') as infile:
        failures = infile.readlines()
    _alert(failures, 'Lint check')
    return len(failures)


def main():
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
    parser.add_argument('--dry-run', '-n', action='store_true',
                        help='Log instead of sending to hipchat.')
    args = parser.parse_args()

    if args.dry_run:
        alertlib.enter_test_mode()
        logging.getLogger().setLevel(logging.INFO)

    num_errors = 0
    if args.test_reports_dir:
        num_errors += report_test_failures(args.test_reports_dir,
                                           args.jenkins_build_url)
    if args.jstest_reports_file:
        num_errors += report_jstest_failures(args.jstest_reports_file)
    if args.lint_reports_file:
        num_errors += report_lint_failures(args.lint_reports_file)

    return num_errors


if __name__ == '__main__':
    # We cap num-errors at 127 because rc >= 128 is reserved for signals.
    sys.exit(min(main(), 127))
