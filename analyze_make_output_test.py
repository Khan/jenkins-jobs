"""Tests for analyze_make_output.py"""

import os
import shutil
import tempfile
import unittest

import ka_root
from tools.jenkins import analyze_make_output


class TestBase(unittest.TestCase):
    def setUp(self):
        self.tmpdir = os.path.realpath(
            tempfile.mkdtemp(prefix=(self.__class__.__name__ + '.')))
        self.reports_dir = os.path.join(self.tmpdir, 'test-reports')
        os.mkdir(self.reports_dir)

        copy_from = ka_root.join('tools', 'jenkins',
                                 'analyze_make_output_test-files')
        for f in os.listdir(copy_from):
            if f.startswith('TEST'):
                shutil.copy2(os.path.join(copy_from, f),
                             os.path.join(self.reports_dir, f))
            else:
                shutil.copy2(os.path.join(copy_from, f),
                             os.path.join(self.tmpdir, f))

        self.errors = []
        analyze_make_output._alert = (
            lambda failures, *a, **kw: self.errors.extend(failures))

    def tearDown(self):
#!!        shutil.rmtree(self.tmpdir)
        self.tmpdir = None

    def _analyze_make_output(self,
                             jenkins_build_url=None,
                             test_reports_dir=None,
                             jstest_reports_file=None,
                             lint_reports_file=None,
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

        return analyze_make_output.main(jenkins_build_url, test_reports_dir,
                                        jstest_reports_file, lint_reports_file,
                                        dry_run)


class TestAlternateTest(TestBase):
    def test_prefer_failed_alternate(self):
        """The output of the failed run is preferred over lint_errors.txt."""
        os.unlink(os.path.join(self.reports_dir,
                               'TEST-testutil.manual_test.LintTest-success.xml'
                               ))
        actual = self._analyze_make_output()
        self.assertEqual(1, actual)
        self.assertIn('E302 expected 2 blank lines', self.errors[0])

    def test_prefer_successful_alternate(self):
        """The output of the successful run is prefrered over lint_errors."""
        os.unlink(os.path.join(self.reports_dir,
                               'TEST-testutil.manual_test.LintTest-fail.xml'
                               ))
        actual = self._analyze_make_output()
        self.assertEqual(0, actual)

    def test_do_not_need_alternate(self):
        """The output of the successful run is prefrered over lint_errors."""
        os.unlink(os.path.join(self.reports_dir,
                               'TEST-testutil.manual_test.LintTest-fail.xml'
                               ))
        os.unlink(os.path.join(self.reports_dir,
                               'TEST-testutil.manual_test.LintTest-success.xml'
                               ))
        actual = self._analyze_make_output()
        self.assertEqual(1, actual)
        self.assertIn('E999 lint error from txt-file.', self.errors[0])

