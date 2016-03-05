#!/usr/bin/env python

"""Tests for analyze_make_output.py"""

import os
import shutil
import tempfile
import unittest

import analyze_make_output


class TestBase(unittest.TestCase):
    def setUp(self):
        self.tmpdir = os.path.realpath(
            tempfile.mkdtemp(prefix=(self.__class__.__name__ + '.')))
        self.reports_dir = os.path.join(self.tmpdir, 'test-reports')
        os.mkdir(self.reports_dir)

        self.errors = []
        analyze_make_output._alert = (
            lambda _, failures, *a, **kw: self.errors.extend(failures))

        analyze_make_output._ALTERNATE_TESTS_VALUES = {}

    def copy_in(self, f):
        copy_from = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                 'analyze_make_output_test-files')
        if f.startswith('TEST'):
            shutil.copy2(os.path.join(copy_from, f),
                         os.path.join(self.reports_dir, f))
        else:
            shutil.copy2(os.path.join(copy_from, f),
                         os.path.join(self.tmpdir, f))

    def tearDown(self):
        shutil.rmtree(self.tmpdir)
        self.tmpdir = None

    def _analyze_make_output(self,
                             jenkins_build_url=None,
                             test_reports_dir=None,
                             jstest_reports_file=None,
                             lint_reports_file=None,
                             e2e_test_reports_file=None,
                             dry_run=False):
        """(Sets defaults for params that aren't passed in.)"""
        if jenkins_build_url is None:
            jenkins_build_url = 'http://www.example.com/'
        if test_reports_dir is None:
            test_reports_dir = self.reports_dir
        if jstest_reports_file is None:
            jstest_reports_file = os.path.join(self.tmpdir,
                                               'jstest_output.txt')
        if lint_reports_file is None:
            lint_reports_file = os.path.join(self.tmpdir,
                                             'lint_errors.txt')
        if e2e_test_reports_file is None:
            e2e_test_reports_file = os.path.join(self.tmpdir,
                                                 'end_to_end_test_output.xml')

        return analyze_make_output.main(jenkins_build_url, test_reports_dir,
                                        jstest_reports_file, lint_reports_file,
                                        e2e_test_reports_file, None, dry_run)


class LintReportingTest(TestBase):
    def test_prefer_failed_alternate(self):
        """The output of the failed run is preferred over lint_errors.txt."""
        self.copy_in('TEST-testutil.lint_test.LintTest-fail.xml')
        self.copy_in('lint_errors.txt')
        actual = self._analyze_make_output()
        self.assertEqual(1, actual)
        self.assertIn('E302 expected 2 blank lines', self.errors[0])

    def test_prefer_successful_alternate(self):
        """The output of the successful run is preferred over lint_errors."""
        self.copy_in('TEST-testutil.lint_test.LintTest-success.xml')
        self.copy_in('lint_errors.txt')
        actual = self._analyze_make_output()
        self.assertEqual([], self.errors)
        self.assertEqual(0, actual)

    def test_do_not_need_alternate(self):
        """The output in lint_errors.txt is used if no test result wad found."""
        self.copy_in('lint_errors.txt')
        actual = self._analyze_make_output()
        self.assertEqual(1, actual)
        self.assertIn('E999 lint error from txt-file.', self.errors[0])


class JsTestReportingTest(TestBase):
    def test_js_failure_reported(self):
        self.copy_in('TEST-testutil.js_test.JsTest.xml')
        actual = self._analyze_make_output()
        self.assertEqual(2, actual)
        self.assertEqual(self.errors, [
            '<http://www.example.com/testReport/junit/js_test/JsTest/test_run_jstests/|javascript/profile-reports-package/checkbox_test.jsx>',
            '<http://www.example.com/testReport/junit/js_test/JsTest/test_run_jstests/|javascript/video-package/poppler_test.js>'
        ])


class PythonTestReportingTest(TestBase):
    def test_python_failure_reported(self):
        self.copy_in('TEST-intl.i18n_test.CountryNameTest.xml')
        actual = self._analyze_make_output()
        self.assertEqual(2, actual)
        self.assertEqual(self.errors, [
            '<http://www.example.com/testReport/junit/intl.i18n_test/CountryNameTest/test_country_name_for_english_request/|intl.i18n_test.CountryNameTest.test_country_name_for_english_request>',
            '<http://www.example.com/testReport/junit/intl.i18n_test/CountryNameTest/test_country_name_for_french_request/|intl.i18n_test.CountryNameTest.test_country_name_for_french_request>'
        ])


class CombinedReportingTest(TestBase):
    def test_will_report_multiple_kinds_of_failures(self):
        self.copy_in('TEST-intl.i18n_test.CountryNameTest.xml')
        self.copy_in('TEST-testutil.lint_test.LintTest-fail.xml')
        self.copy_in('TEST-testutil.js_test.JsTest.xml')
        actual = self._analyze_make_output()

        # 5 errors: 2 from Python, 2 from JS, 1 from lint
        self.assertEqual(5, actual)

if __name__ == '__main__':
    unittest.main()
