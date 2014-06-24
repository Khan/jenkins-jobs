#!/usr/bin/env python

"""Runs the various steps of our github-style deploy pipeline.

We use jenkins to implement a github-style deploy:
   https://docs.google.com/a/khanacademy.org/document/d/1s7qvACA4Uq4ON6F4PWJ_eyBz9EJeTk-DJ6SysRrJcTI/edit
   https://github.com/blog/1241-deploying-at-github

Unfortunately, Jenkins is not really meant for this kind of
pipelining.  We have at least 5 jenkins jobs:
   * kick-off job
   * build job
   * test job
   * [deploying to appengine is done by the kick-off job]
   * set-default job
   * finish job

We need build and test to be separate jenkins jobs because we want
them to run in parallel.  And we need kick-off/set-default/finish to
be separate jobs because there's a manual step involved between each
of these three tasks, and jenkins doesn't have great support for
manual steps.  (We simulate manual steps here by having each of these
jobs end with a hipchat message that includes a link to the next job
in the chain.)

All of these jobs must operate under a deploy lock, so we only do one
deploy at a time.  Also, these jobs all share global state, that is
specified when the kick-off job is started.

To make it easier to reason about the control flow, we put all the
work that these jobs do into one file, here.  Each job will run this
script with a different 'stage' value.  They will all verify they are
running under the lock.  They will all have access to the global state.

This script assumes that all the jobs in the pipeline run in the same
workspace, which will also hold the lockfile.
"""

import argparse
import cStringIO
import contextlib
import errno
import json
import logging
import os
import shutil
import subprocess
import sys
import time
import urllib

sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import appengine_tool_setup
appengine_tool_setup.fix_sys_path()

from third_party import alertlib

import deploy.deploy
import deploy.set_default
import tools.delete_gae_versions


# Used for testing.  Does not set-default, does not delete.
_DRY_RUN = True


def _alert(props, text, severity=logging.INFO, html=True,
           prefix_with_username=True):
    """Send the given text to hipchat and the logs."""
    if prefix_with_username:
        deployer = props['DEPLOYER_USERNAME']
        if html:
            text = '<b>%s</b>: %s' % (deployer, text)
        else:
            text = '@%s %s' % (deployer, text)
    (alertlib.Alert(text, severity=severity, html=html)
     .send_to_logs()
     .send_to_hipchat(room_name=props['HIPCHAT_ROOM'],
                      sender=props['HIPCHAT_SENDER'],
                      notify=True))


def _run_command(cmd, failure_ok=False):
    logging.info('Running command: %s' % cmd)
    if failure_ok:
        subprocess.call(cmd)
    else:
        subprocess.check_call(cmd)


def _pipe_command(cmd):
    logging.info('Running pipe-command: %s' % cmd)
    retval = subprocess.check_output(cmd).rstrip()
    logging.info('>>> %s' % retval)
    return retval


@contextlib.contextmanager
def _password_on_stdin(pw_filename):
    """Run the context with stdin set to pw_filename's contents."""
    # Some code was originally written to read the password from
    # stdin.  Rather than refactoring so we can (also) pass in a
    # password directly, I just monkey-patch.
    with open(pw_filename) as f:
        password = f.read().strip()
    old_stdin = sys.stdin
    sys.stdin = cStringIO.StringIO(password)
    try:
        yield
    finally:
        sys.stdin = old_stdin


def _set_default_url(props, **extra_params):
    """Return a URL that points to the set-default job."""
    return ('%s/job/deploy-set-default/parambuild'
            '?GIT_REVISION=%s&VERSION_NAME=%s&%s'
            % (props['JENKINS_URL'].rstrip('/'),
               props['GIT_REVISION'],
               props['VERSION_NAME'],
               urllib.urlencode(extra_params)))


def _finish_url(props, **extra_params):
    """Return a URL that points to the deploy-finish job."""
    return ('%s/job/deploy-finish/parambuild?GIT_REVISION=%s&%s'
            % (props['JENKINS_URL'].rstrip('/'),
               props['GIT_REVISION'],
               urllib.urlencode(extra_params)))


def _current_gae_version():
    """The current default appengine version-name, according to appengine."""
    r = urllib.urlopen('http://www.khanacademy.org/api/v1/dev/version')
    version_dict = json.load(r)
    # The version-id is <major>.<minor>.  We just care about <major>.
    return version_dict['version_id'].split('.')[0]


def _read_properties(lockdir):
    """Read the properties from lockdir/deploy.prop into a dict."""
    retval = {}
    with open(os.path.join(lockdir, 'deploy.prop')) as f:
        for l in f.readlines():
            (k, v) = l.strip().split('=', 1)
            retval[k] = v
    logging.info('Read properties from %s: %s' % (lockdir, retval))
    return retval


def _write_properties(lockdir, props):
    """Write the given properties dict into lockdir/deploy.prop."""
    logging.info('Wrote properties to %s: %s' % (lockdir, props))
    with open(os.path.join(lockdir, 'deploy.prop'), 'w') as f:
        for (k, v) in sorted(props.iteritems()):
            print >>f, '%s=%s' % (k, v)


def acquire_deploy_lock(props, wait_sec=3600, notify_sec=600):
    """Acquire the deploy lock (a directory in the jenkins workspace).

    The deploy lock holds information about who is doing the deploy,
    as well as parameters they specify for the deploy.  Future deploy
    stages can use this.  The information is stored in a java-style
    properties file, so Jenkins rules can also use this as well:
    the properties file is <lockdir>/deploy.prop.

    If we cannot acquire the lock after wait_sec, we print an appropriate
    message to hipchat and exit.

    Arguments:
        props: a map of property-name to value, stored with the lock.
        wait_sec: how many seconds to busy-wait for the lock to free up.
        notify_sec: while waiting for the lock, how often to ping
           hipchat that we're still waiting.

    Returns:
        True if we acquired the lock, False otherwise.
    """
    # Assuming someone is holding the lock, who is it?
    lockdir = props['LOCKDIR']
    try:
        current_props = _read_properties(lockdir)
    except (IOError, OSError):
        current_props = {}

    waited_sec = 0
    while waited_sec < wait_sec:
        try:
            os.mkdir(lockdir)
        except OSError, why:
            if why.errno != errno.EEXIST:      # file exists
                raise
        else:                        # lockdir acquired!
            _write_properties(lockdir, props)
            logging.info("Lockdir %s acquired." % lockdir)
            return True

        if waited_sec == 0:
            _alert(props,
                   "You're next in line to deploy! (branch %s.) "
                   "Currently deploying: %s"
                   % (props['GIT_REVISION'],
                      current_props.get('DEPLOYER_USERNAME', 'Unknown User')))
        elif waited_sec % notify_sec == 0:
            _alert(props,
                   "You're still next in line to deploy, after %s. "
                   "(Waited %.1g minutes so far)"
                   % (current_props['DEPLOYER_USERNAME'],
                      waited_sec / 60.0))

        time.sleep(10)     # how often to busy-wait
        waited_sec += 10

    _alert(props,
           "%s has been deploying for over %s minutes. "
           "Perhaps it's a stray lock?  If you are confident that "
           "no deploy is currently running (check the "
           "<a href='%s'>Jenkins dashboard</a>), you can "
           "<a href='%s'>manually unlock</a>.  Then re-deploy."
           % (current_props['DEPLOYER_USERNAME'],
              waited_sec / 60,
              props['JENKINS_URL'],
              _finish_url(props, STATUS='unlock')),
           severity=logging.ERROR)
    return False


def release_deploy_lock(props):
    try:
        shutil.rmtree(props['LOCKDIR'])
        logging.info('Released the deploy lock: %s' % props['LOCKDIR'])
        return True
    except (IOError, OSError):
        _alert(props,
               "Could not release the deploy-lock (%s); it's not being held"
               % props['LOCKDIR'],
               severity=logging.ERROR)
        return False


def merge_from_master(git_revision):
    """Merge master into the current branch if necessary.

    Given an argument that is either the name of a branch or a
    different kind of commit-ish (sha1, tag, etc), does two things:

    1) Ensures that HEAD matches that argument -- that is, that you're
       checked out where you expect to be -- and then does a
       git checkout <branch> so we are no longer in a detached-head
       state.

    2) Check if the input sha1 is a superset of master (that is,
       everything in master is part of this sha1's history too).
       If not:
    2a) If the argument is a branch-name, merge master into the branch.
    2b) If the argument is another commit-ish, fail.

    Raises an exception if the merge from master failed for any reason.
    Returns True otherwise.
    """
    if git_revision == 'master':
        raise ValueError("You must deploy from a branch, you can't deploy "
                         "from master")

    # Set our local branch to be the same as the origin branch.  This
    # is needed in cases when a previous deploy set the local (jenkins)
    # branch to commit X, but subsequent commits have moved the remote
    # (github) version of the branch to commit Y.  This also moves us
    # from a (potentially) detached-head state to a head-at-branch state.
    # Finally, it makes sure the ref exists locally, so we can do
    # 'git rev-parse branch' rather than 'git rev-parse origin/branch'.
    # This will fail if we're given a commit and not a branch; that's ok.
    _run_command(['git', 'checkout', '-B', git_revision,
                  'origin/%s' % git_revision], failure_ok=True)

    head_commit = _pipe_command(['git', 'rev-parse', 'HEAD'])
    master_commit = _pipe_command(['git', 'rev-parse', 'origin/master'])

    # Sanity check: HEAD should be at the revision we want to deploy from.
    if head_commit != _pipe_command(['git', 'rev-parse', git_revision]):
        raise RuntimeError('HEAD unexpectedly at %s, not %s'
                           % (head_commit, git_revision))

    # If the current commit is a super-set of master, we're done, yay!
    base = _pipe_command(['git', 'merge-base', git_revision, master_commit])
    if base == master_commit:
        logging.info('%s is a superset of master, no need to merge'
                     % git_revision)
        return True

    # Now we need to merge master into our branch.  First, make sure
    # we *are* a branch.  git show-ref returns line like 'd41eba92 refs/...'
    all_branches = _pipe_command(['git', 'show-ref']).splitlines()
    all_branch_names = [l.split()[1] for l in all_branches]
    if ('refs/remotes/origin/%s' % git_revision) not in all_branch_names:
        raise ValueError('%s is not a branch name on the remote, like these:'
                         '\n  %s' % ('\n  '.join(sorted(all_branch_names))))

    # The merge exits with rc > 0 if there were conflicts
    logging.info("Merging master into %s" % git_revision)
    try:
        _run_command(['git', 'merge', 'origin/master'])
    except subprocess.CalledProcessError:
        _run_command(['git', 'merge', '--abort'])
        raise RuntimeError('Merge conflict: must merge master into %s '
                           'manually.' % git_revision)

    # There's a race condition if someone commits to this branch while
    # this script is running, so check for that.
    try:
        _run_command(['git', 'push', 'origin', git_revision])
    except subprocess.CalledProcessError:
        _run_command(['git', 'reset', '--hard', head_commit])
        raise RuntimeError("Someone committed to %s while we've been "
                           "deploying!" % git_revision)

    logging.info("Done merging master into %s" % git_revision)
    return True


def _rollback_deploy(props):
    """Roll back to ROLLBACK_TO and delete the new deploy from appengine.

    Returns True if the rollback succeeded, False else.  It should
    'never' raise an exception.
    """
    current_gae_version = _current_gae_version()
    if current_gae_version != props['VERSION_NAME']:
        logging.info("Skipping rollback: looks like our deploy never "
                     "succeeded. (Us: %s, current: %s, rollback-to: %s)"
                     % (props['VERSION_NAME'], current_gae_version,
                        props['ROLLBACK_TO']))
        return True

    _alert(props,
           "Automatically rolling the default back to %s "
           "and deleting %s from appengine"
           % (props['ROLLBACK_TO'], props['VERSION_NAME']))
    try:
        with _password_on_stdin(props['DEPLOY_PW_FILE']):
            if deploy.set_default.main(props['ROLLBACK_TO'],
                                       email=props['DEPLOY_EMAIL'],
                                       passin=True,
                                       num_instances_to_prime=None,
                                       monitor=False,
                                       dry_run=_DRY_RUN) != 0:
                raise RuntimeError('set_default failed')
    except Exception:
        logging.exception('Auto-rollback failed')
        _alert(props,
               "(sadpanda) (sadpanda) Auto-rollback failed! "
               "Roll back to %s manually, then "
               "<a href='%s'>release the deploy lock</a>."
               % (props['ROLLBACK_TO'], _finish_url(props, STATUS='failure')),
               severity=logging.CRITICAL)
        return False

    try:
        with _password_on_stdin(props['DEPLOY_PW_FILE']):
            if tools.delete_gae_versions.main(props['VERSION_NAME'],
                                              email=props['DEPLOY_EMAIL'],
                                              passin=True,
                                              dry_run=_DRY_RUN) != 0:
                raise RuntimeError('delete_gae_version failed')
    except Exception:
        logging.exception('Auto-delete failed')
        _alert(props,
               "(sadpanda) (sadpanda) Auto-delete failed! "
               "Delete %s manually at your convenience."
               % props['VERSION_NAME'],
               severity=logging.WARNING)
    return True


def manual_test(props):
    """Send a message to hipchat saying to do pre-set-default manual tests."""
    _alert(props,
           "Version <a href='http://%s.khan-academy.appspot.com/'>%s</a> "
           "(branch %s) is uploaded to appengine! "
           "Do some manual testing on it, "
           "then click to <a href='%s'>set it as default</a>."
           % (props['VERSION_NAME'], props['VERSION_NAME'],
              props['GIT_REVISION'],
              _set_default_url(props, AUTO_ROLLBACK=props['AUTO_ROLLBACK'])))
    return True


def set_default(props, monitoring_time=10):
    """Call set_default.py to make a specified deployed version live.

    If the user asked for monitoring, also do the monitoring, potentially
    rolling back if there's a problem.

    Returns True if no more human intervention is required: the
    set-default succeeded, or it failed and was successfully
    auto-rolled back.  Return False otherwise: the set-default failed,
    or it succeeded but monitoring detected problems that a human
    should look into.  This function should 'never' raise an
    exception.
    """
    logging.info("Changing default from %s to %s"
                 % (props['ROLLBACK_TO'], props['VERSION_NAME']))
    try:
        with _password_on_stdin(props['DEPLOY_PW_FILE']):
            deploy.set_default.main(props['VERSION_NAME'],
                                    email=props['DEPLOY_EMAIL'],
                                    passin=True,
                                    num_instances_to_prime=100,
                                    monitor=monitoring_time,
                                    dry_run=_DRY_RUN)
            rc = 0
    except subprocess.CalledProcessError, why:
        logging.exception('set-default failed')
        rc = why.returncode
    except Exception:
        logging.exception('set-default failed')
        rc = 1

    if rc == 0:
        _alert(props,
               "Default set: %s -> %s (%s)."
               % (props['ROLLBACK_TO'],
                  props['VERSION_NAME'],
                  props['DEPLOYER_USERNAME']),
               prefix_with_username=False)
        _alert(props,
               "Monitoring passed, but you should "
               "<a href='https://www.khanacademy.org'>double-check</a> "
               "everything is ok, then click one of these: "
               "<a href='%s'>OK! Release the deploy lock.</a> ~ "
               "<a href='%s'>TROUBLE! Roll back.</a>"
               % (_finish_url(props, STATUS='success'),
                  _finish_url(props, STATUS='rollback',
                              ROLLBACK_TO=props['ROLLBACK_TO'])),
               severity=logging.WARNING)
        return True

    if rc == 2:
        if props['AUTO_ROLLBACK']:
            _alert(props,
                   "(sadpanda) set_default monitoring detected problems!")
            return _rollback_deploy(props)
        else:
            _alert(props,
                   "(sadpanda) set_default monitoring detected problems! "
                   "Make sure everything is ok, then click one of these: "
                   "<a href='%s'>OK! Release the deploy lock.</a> ~ "
                   "<a href='%s'>TROUBLE! Roll back.</a>"
                   % (_finish_url(props, STATUS='success'),
                      _finish_url(props, STATUS='rollback',
                                  ROLLBACK_TO=props['ROLLBACK_TO'])
                      ),
                   severity=logging.WARNING)
            return False

    _alert(props,
           "(sadpanda) (sadpanda) set-default failed! "
           "Either 1) set the default to %s manually, then "
           "<a href='%s'>release the deploy lock</a>; "
           "or 2) <a href='%s'>just abort the deploy</a>."
           % (props['VERSION_NAME'],
              _finish_url(props, STATUS='success'),
              _finish_url(props, STATUS='failure')),
           severity=logging.CRITICAL)
    return False


def finish_with_unlock(props, caller):
    """Manually release the deploy lock.  Caller is the 'manual' person.

    This is called when something is messed up and the lock is being
    held even though no deploy is going on.
    """
    _alert(props,
           "%s has manually released the deploy lock." % caller)
    return release_deploy_lock(props)


def finish_with_success(props):
    """Release the deploy lock because the deploy succeeded."""
    _alert(props,
           "(gangnamstyle) Deploy of %s (branch %s) succeeded! "
           "Time for a happy dance!"
           % (props['VERSION_NAME'], props['GIT_REVISION']))
    return release_deploy_lock(props)


def finish_with_failure(props):
    """Release the deploy lock after the deploy failed."""
    _alert(props,
           "(pokerface) Deploy of %s (branch %s) failed.  I'm sorry."
           % (props['VERSION_NAME'], props['GIT_REVISION']),
           severity=logging.ERROR)
    return release_deploy_lock(props)


def finish_with_rollback(props):
    """Does a rollback and releases the lock if it succeeds."""
    if not _rollback_deploy(props):
        return False
    return release_deploy_lock(props)


def _create_properties(lockdir, deployer_username, git_revision,
                       auto_deploy, auto_rollback, rollback_to,
                       jenkins_url, hipchat_room, hipchat_sender,
                       deploy_email, deploy_pw_file):
    """Return a dict of property-name to property value.

    Arguments:
        lockdir: the lock-directory, ideally an absolute path.  The
           existence of this directory indicates ownership of the lock.
        deployer_username: the jenkins username of the person doing the
           deploy.
        git_revision: the branch-name (it can also just be a commit id)
           being deployed.
        auto_deploy: If 'true', don't ask whether to set the new version
           as the default, do so automatically.
        auto_rollback: If 'true', and set_default.py logs-monitoring
           indicates the new deploy may be problematic, automatically
           roll back to the old deploy.
        rollback_to: the current appengine version before this deploy,
           that is, the appengine version-name we would roll back to
           if this deploy turned out to be problematic.
        jenkins_url: The url of the jenkins server.
        hipchat_room: The room to send all hipchat notifications to.
        hipchat_sender: The name to use as the sender of hipchat
           notifications.
        deploy_email: The AppEngine user to deploy as.
        deploy_pw_file: The file holding deploy_email's appengine
           password.
    """
    retval = {
        'LOCKDIR': lockdir,
        'DEPLOYER_USERNAME': deployer_username,
        'GIT_REVISION': git_revision,
        'VERSION_NAME': deploy.deploy.Git().dated_current_git_version(
            git_revision),
        'AUTO_DEPLOY': str(auto_deploy).lower(),
        'AUTO_ROLLBACK': str(auto_rollback).lower(),
        'ROLLBACK_TO': rollback_to,
        'JENKINS_URL': jenkins_url,
        'HIPCHAT_ROOM': hipchat_room,
        'HIPCHAT_SENDER': hipchat_sender,
        'DEPLOY_EMAIL': deploy_email,
        'DEPLOY_PW_FILE': deploy_pw_file,
        # TODO(csilvers): add a random token? and use to verify lock.
        }
    logging.info('Setting deploy-properties: %s' % retval)
    return retval


def main(action, lockdir,
         acquire_lock_args=(), monitoring_time=None, caller=None):
    """action is one of:
    * acquire-lock: acquire the deploy lock.
    * merge-from-master: merge master into current branch if necessary.
    * manual-test: send a hipchat message saying to do pre-set-default testing.
    * set-default: set this version to GAE default after it's been uploaded.
    * finish-with-unlock: manually release the deploy lock.
    * finish-with-success: ditto, but because the deploy succeeded.
    * finish-with-failure: ditto, but because the deploy failed (pre-set-dflt)
    * finish-with-rollback: ditto, because the deploy failed (post-set-default)

    If action is acquire-lock, then acquire_lock_args should be
    specified as a list of the arguments to _create_properties().

    monitoring_time is ignored except by set_default.

    caller is ignored except by finish-with-unlock.

    The commands either return False or raise an exception if we
    should stop the pipeline there (possibly requiring a manual step
    to continue).  We, likewise, return False if this pipeline step
    failed, or True otherwise.
    """
    if action == 'acquire-lock':
        props = _create_properties(*acquire_lock_args)
    else:
        try:
            props = _read_properties(lockdir)
        except IOError:
            logging.exception('Running without having acquired the lock?')
            raise

    try:
        if action == 'acquire-lock':
            return acquire_deploy_lock(props)

        if action == 'merge-from-master':
            return merge_from_master(props['GIT_REVISION'])

        if action == 'manual-test':
            return manual_test(props)

        if action == 'set-default':
            return set_default(props, monitoring_time=monitoring_time)

        if action == 'finish-with-unlock':
            return finish_with_unlock(props, caller or 'Unknown User')

        if action == 'finish-with-success':
            return finish_with_success(props)

        if action == 'finish-with-failure':
            return finish_with_failure(props)

        if action == 'finish-with-rollback':
            return finish_with_rollback(props)

        raise RuntimeError("Unknown action '%s'" % action)
    except Exception:
        logging.exception(action)
        return False


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('action',
                        choices=('acquire-lock',
                                 'merge-from-master',
                                 'manual-test',
                                 'set-default',
                                 'finish-with-unlock',
                                 'finish-with-success',
                                 'finish-with-failure',
                                 'finish-with-rollback',
                                 ),
                        help='Action to perform')
    parser.add_argument('--lockdir',
                        default='tmp/deploy.lockdir',
                        help=("The lock-directory, ideally an absolute path. "
                              "The existence of this directory indicates "
                              "ownership of the deploy lock."))
    # These flags are only needed for acquire-lock.
    parser.add_argument('--deployer_username',
                        default='unknown-user',
                        help=("The jenkins username of the person doing the "
                              "deploy."))
    parser.add_argument('--git_revision',
                        help=("The branch-name (it can also just be a "
                              "commit id) being deployed."))
    parser.add_argument('--auto_deploy',
                        default='false',
                        help=("If 'true', don't ask whether to set the new "
                              "version as the default, do so automatically."))
    parser.add_argument('--auto_rollback',
                        default='false',
                        help=("If 'true', and set_default.py logs-monitoring "
                              "indicates the new deploy may be problematic, "
                              "automatically roll back to the old deploy."))
    parser.add_argument('--jenkins_url',
                        default='http://jenkins.khanacademy.org/',
                        help=("The url of the jenkins server."))
    parser.add_argument('--hipchat_room',
                        default='HipChat Tests',
                        help=("The room to send all hipchat notifications "
                              "to."))
    parser.add_argument('--hipchat_sender',
                        default='Testybot',
                        help=("The name to use as the sender of hipchat "
                              "notifications."))
    parser.add_argument('--deploy_email',
                        default='prod-deploy@khanacademy.org',
                        help=("The AppEngine user to deploy as."))
    parser.add_argument('--deploy_pw_file',
                        default='%s/prod-deploy.pw' % os.environ['HOME'],
                        help=("The file holding deploy_email's "
                              "appengine password."))

    # These flags are only used by set-default.
    parser.add_argument('--monitoring_time', type=int,
                        default=10,
                        help=("How long to monitor in set-default, in "
                              "minutes (0 to disable monitoring)."))

    args = parser.parse_args()

    # Make sure the _alert() logging shows up, and have the log-prefix
    # be prettier.
    logging.basicConfig(format="[%(levelname)s] %(message)s")
    logging.getLogger().setLevel(logging.INFO)

    rc = main(args.action, os.path.abspath(args.lockdir),
              acquire_lock_args=(os.path.abspath(args.lockdir),
                                 args.deployer_username,
                                 args.git_revision,
                                 args.auto_deploy == 'true',
                                 args.auto_rollback == 'true',
                                 _current_gae_version(),
                                 args.jenkins_url,
                                 args.hipchat_room,
                                 args.hipchat_sender,
                                 args.deploy_email,
                                 args.deploy_pw_file),
              monitoring_time=args.monitoring_time,
              caller=args.deployer_username)
    sys.exit(0 if rc else 1)
