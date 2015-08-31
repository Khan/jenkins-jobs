#!/usr/bin/env python
# -*- coding: utf-8 -*-


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
import collections
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
import urllib2

# This requires having secrets.py (or ka_secrets.py) on your PYTHONPATH!
sys.path.append(os.path.join(os.path.abspath(os.path.dirname(__file__)),
                             'alertlib'))
import alertlib

# We assume that webapp is a sibling to the jenkins-tools repo.
sys.path.append(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                             '..', 'webapp', 'tools'))
import appengine_tool_setup
appengine_tool_setup.fix_sys_path()

import deploy.deploy
import deploy.rollback
import deploy.set_default
import ka_secrets             # for (optional) email->hipchat
from tools import manual_webapp_testing


# Used for testing.  Does not set-default, does not tag versions as bad.
_DRY_RUN = False

_WEBAPP_ROOT = os.path.dirname(os.path.abspath(ka_secrets.__file__))

InvocationDetails = collections.namedtuple(
    'InvocationDetails',
    [
        'lockdir',
        'deployer_username',
        'git_revision',
        'auto_deploy',
        'gae_version',
        'jenkins_url',
        'jenkins_job_url',
        'monitoring_time',
        'hipchat_room',
        'chat_sender',
        'icon_emoji',
        'slack_channel',
        'deploy_email',
        'deploy_pw_file',
        'token',
    ]
)


def _hipchatify(s):
    """Return the string s with Slack emoji replaced by HipChat emoticons."""
    # TODO(bmp): Kill this whole function when HipChat dies
    hipchat_substitutions = {
        ':+1:': '(successful)',
        ':white_check_mark:': '(continue)',
        ':worried:': '(sadpanda)',
        ':no_good:': '(failed)',
        ':poop:': '(poo)',  # not a line of code I thought I'd ever write
        ':smile_cat:': '(gangnamstyle)',
        ':flushed:': '(pokerface)',
    }
    for emoji, emoticon in hipchat_substitutions.viewitems():
        s = s.replace(emoji, emoticon)

    return s


def _alert_to_hipchat(props, alert, color=None, notify=True):
    """Send the Alert to Hipchat with default sender and destination."""
    alert.send_to_hipchat(room_name=props['HIPCHAT_ROOM'],
                          sender=props['CHAT_SENDER'],
                          color=color,
                          notify=notify)


def _alert_to_slack(props, alert, simple_message=False, attachments=None):
    """Send the Alert to Slack with default sender and destination."""
    alert.send_to_slack(channel=props['SLACK_CHANNEL'],
                        sender=props['CHAT_SENDER'],
                        icon_emoji=props['ICON_EMOJI'],
                        simple_message=simple_message,
                        attachments=attachments)


def _prefix_with_username(props, text):
    """Prefix the give text with the deployer's username."""
    return '%s %s' % (props['DEPLOYER_USERNAME'], text)


def _alert(props, text, severity=logging.INFO, color=None, html=False,
           prefix_with_username=True, attachments=None):
    """Send the given text to HipChat, Slack and the logs all at once.

    Expects slack emoji and attempts to automatically translate them to HipChat
    style. No other translations are attempted.

    NOTE: This is a transition method and it's signature will likely change
    once HipChat is deprecated.
    """
    if prefix_with_username:
        text = _prefix_with_username(props, text)

    hc_alert = alertlib.Alert(_hipchatify(text), severity=severity, html=html)
    _alert_to_hipchat(props, hc_alert, color=color, notify=True)

    slack_alert = alertlib.Alert(text, severity=severity, html=html)
    _alert_to_slack(props, slack_alert, attachments=attachments)

    slack_alert.send_to_logs()


def _safe_urlopen(*args, **kwargs):
    """Does a urlopen with retries on error."""
    num_tries = 0
    while True:
        try:
            with contextlib.closing(urllib2.urlopen(*args, **kwargs)) as req:
                return req.read()
        except Exception:
            num_tries += 1
            if num_tries == 3:
                raise
            else:
                logging.warning('url-fetch of %s %s failed, retrying...'
                                % (args, kwargs))


def _run_command(cmd, failure_ok=False):
    """Return True if command succeeded, False else.  May raise on failure."""
    logging.info('Running command: %s' % cmd)
    if failure_ok:
        return subprocess.call(cmd, cwd=_WEBAPP_ROOT) == 0
    else:
        subprocess.check_call(cmd, cwd=_WEBAPP_ROOT)
        return True


def _pipe_command(cmd):
    logging.info('Running pipe-command: %s' % cmd)
    retval = subprocess.check_output(cmd, cwd=_WEBAPP_ROOT).rstrip()
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
            '?TOKEN=%s&VERSION_NAME=%s&%s'
            % (props['JENKINS_URL'].rstrip('/'),
               props['TOKEN'],
               props['VERSION_NAME'],
               urllib.urlencode(extra_params)))


def _finish_url(props, **extra_params):
    """Return a URL that points to the deploy-finish job."""
    return ('%s/job/deploy-finish/parambuild?TOKEN=%s&%s'
            % (props['JENKINS_URL'].rstrip('/'),
               props['TOKEN'],
               urllib.urlencode(extra_params)))


def _gae_version(git_revision):
    # If git_revision is a branch, make sure it's available locally,
    # so dated_current_git_version can reference it.
    if _run_command(['git', 'ls-remote', '--exit-code',
                     '.', 'origin/%s' % git_revision],
                    failure_ok=True):
        _run_command(['git', 'fetch', 'origin',
                      '+refs/heads/%s:refs/remotes/origin/%s'
                      % (git_revision, git_revision)])
        git_revision = 'origin/%s' % git_revision
    return deploy.deploy.Git(_WEBAPP_ROOT).dated_current_git_version(
        git_revision)


def _current_gae_version():
    """The current default appengine version-name, according to appengine."""
    data = _safe_urlopen('https://www.khanacademy.org/api/internal/dev/version')
    version_dict = json.loads(data)
    # The version-id is <major>.<minor>.  We just care about <major>.
    return version_dict['version_id'].split('.')[0]


def _create_properties(args):
    """Return a dict of property-name to property value.

    :param InvocationDetails args: the lock arguments to dictify
    """
    retval = {
        'LOCKDIR': args.lockdir,
        'DEPLOYER_USERNAME': args.deployer_username,
        'GIT_REVISION': args.git_revision,

        # Note: GIT_SHA1 and VERSION_NAME will be updated after
        # merge_from_master(), which modifies the branch.
        'GIT_SHA1': args.git_revision,
        'VERSION_NAME': _gae_version(args.git_revision),

        'AUTO_DEPLOY': str(args.auto_deploy).lower(),
        'ROLLBACK_TO': args.gae_version,
        'JENKINS_URL': args.jenkins_url,
        'HIPCHAT_ROOM': args.hipchat_room,
        'CHAT_SENDER': args.chat_sender,
        'ICON_EMOJI': args.icon_emoji,
        'SLACK_CHANNEL': args.slack_channel,
        'DEPLOY_EMAIL': args.deploy_email,
        'DEPLOY_PW_FILE': args.deploy_pw_file,
        'TOKEN': args.token,

        # These hold state about the deploy as it's going along.
        'LAST_ERROR': '',
        # A comma-separated list of choices from the 'action' argparse arg.
        'POSSIBLE_NEXT_STEPS': 'acquire-lock,finish-with-unlock,relock'
    }

    logging.info('Setting deploy-properties: %s' % retval)
    return retval


def _read_properties(lockdir):
    """Read the properties from lockdir/deploy.prop into a dict."""
    properties = {}
    with open(os.path.join(lockdir, 'deploy.prop'), 'rb') as f:
        for l in f:
            k, v = l.strip().split('=', 1)
            properties[k] = v

    # Do some sanity checking.
    assert properties['LOCKDIR'] == lockdir, (properties['LOCKDIR'], lockdir)

    logging.info('Read properties from %s: %s' % (lockdir, properties))
    return properties


def _write_properties(props):
    """Write the given properties dict into lockdir/deploy.prop.

    Also writes lockdir/state.json, with the same information in a more
    Hubot-readable form.
    """
    logging.info('Wrote properties to %s: %s' % (props['LOCKDIR'], props))
    with open(os.path.join(props['LOCKDIR'], 'deploy.prop'), 'wb') as f:
        for k, v in sorted(props.viewitems()):
            f.write('%s=%s\n' % (k, v))
    with open(os.path.join(props['LOCKDIR'], 'deploy.json'), 'wb') as f:
        json.dump(props, f, indent=4, sort_keys=True)


def _update_properties(props, new_values):
    """Update props from the new_values dict, and write the result to disk.

    This routine also automatically updates dependent property values.
    For instance, whenever you change GIT_SHA, you also want to change
    VERSION_NAME.
    """
    new_values = new_values.copy()
    if 'GIT_SHA1' in new_values:
        new_values.setdefault(
            'VERSION_NAME', _gae_version(new_values['GIT_SHA1']))

    if 'LOCKDIR' in new_values:
        new_values.setdefault(
            'LOCK_ACQUIRE_TIME', int(time.time()))

    if 'POSSIBLE_NEXT_STEPS' in new_values:
        # finish-with-failure is always possible; it is called when
        # you manually cancel a jenkins job.  finish-with-rollback
        # too, which is mostly a synonym.  And finish-with-unlock and
        # relock, which are called manually when the script gets
        # messed up, and which we never want to block.
        next_steps = set(new_values['POSSIBLE_NEXT_STEPS'].split(','))
        next_steps.update({'finish-with-failure', 'finish-with-rollback',
                           'finish-with-unlock', 'relock'})
        new_values['POSSIBLE_NEXT_STEPS'] = ','.join(sorted(next_steps))

    props.update(new_values)
    _write_properties(props)


def acquire_deploy_lock(props, jenkins_build_url=None,
                        wait_sec=3600, notify_sec=600):
    """Acquire the deploy lock (a directory in the jenkins workspace).

    The deploy lock holds information about who is doing the deploy,
    as well as parameters they specify for the deploy.  Future deploy
    stages can use this.  The information is stored in a java-style
    properties file, so Jenkins rules can also use this as well:
    the properties file is <lockdir>/deploy.prop.

    Once the current lock-holder has held the lock for longer than
    wait_sec, we print an appropriate message to hipchat and exit.

    Arguments:
        props: a map of property-name to value, stored with the lock.
        jenkins_build_url: the 'build url' of the jenkins job trying
           to acquire the lock.  (This is $BUILD_URL inside jenkins,
           and looks something like
           https://jenkins.khanacademy.org/job/testjob/723/).
        wait_sec: how many seconds to busy-wait for the lock to free up.
        notify_sec: while waiting for the lock, how often to ping
           hipchat that we're still waiting.

    Raises:
        RuntimeError or OSError if we failed to acquire the lock.
    """
    # Assuming someone is holding the lock, who is it?
    lockdir = props['LOCKDIR']
    try:
        current_props = _read_properties(lockdir)
        # How long has the current lock-holder been holding this lock?
        waited_sec = int(time.time()) - int(current_props['LOCK_ACQUIRE_TIME'])
    except (IOError, OSError):
        current_props = {}
        waited_sec = 0

    done_first_alert = False
    while waited_sec < wait_sec:
        try:
            os.mkdir(lockdir)
        except OSError as why:
            if why.errno != errno.EEXIST:      # file exists
                raise
        else:                        # lockdir acquired!
            # We don't worry with timezones since the lock is always
            # local to a single machine, which has a consistent timezone.
            props['LOCK_ACQUIRE_TIME'] = int(time.time())
            logging.info("Lockdir %s acquired." % lockdir)
            msg = ""
            if done_first_alert:   # tell them they're no longer in line.
                msg += "Thank you for waiting! "
            msg += ("Starting deploy of branch %s.  I'll post to HipChat when "
                    "both a) tests are done and b) the deploy is finished."
                    % props['GIT_REVISION'])
            if jenkins_build_url and props['AUTO_DEPLOY'] != 'true':
                msg += ("  If you wish to cancel before then:\n"
                        ":no_good: abort: %s/stop"
                        % jenkins_build_url.rstrip('/'))
            _alert(props, msg, color='green')
            return

        recover_msg = ("If this is a mistake and you are sure nobody else "
                       "is deploying, fix it by visiting "
                       "%s/job/deploy-finish/build, setting STATUS=unlock "
                       "and clicking 'Build'."
                       % (props['JENKINS_URL'].rstrip('/')))
        if not done_first_alert:
            _alert(props,
                   "You're next in line to deploy! (branch %s.) "
                   "Currently deploying (%.0f minutes in so far): "
                   "%s (branch %s). %s"
                   % (props['GIT_REVISION'],
                      waited_sec / 60.0,
                      current_props.get('DEPLOYER_USERNAME', 'Unknown User'),
                      current_props.get('GIT_REVISION', 'unknown'),
                      recover_msg),
                   color='yellow')
            done_first_alert = True
        elif waited_sec % notify_sec == 0:
            _alert(props,
                   "You're still next in line to deploy, after %s (branch %s)."
                   " (Waited %.0f minutes so far). %s"
                   % (current_props.get('DEPLOYER_USERNAME', 'Unknown User'),
                      current_props.get('GIT_REVISION', 'unknown'),
                      waited_sec / 60.0,
                      recover_msg),
                   color='yellow')

        time.sleep(10)     # how often to busy-wait
        waited_sec += 10

    # Figure out where in the pipeline the previous job is, and
    # suggest a course of action based on that.
    next_steps = current_props['POSSIBLE_NEXT_STEPS'].split(',')
    if ('merge-from-master' in next_steps or 'manual-test' in next_steps or
            'set-default' in next_steps):
        # They haven't set the default yet, so we can just fail.
        msg = (":no_good: cancel their deploy: %s"
               % _finish_url(current_props, STATUS='failure', WHY='aborted'))
    elif 'finish-with-success' in next_steps:
        msg = (":+1: finish their deploy with success: %s\n"
               ":no_good: abort their deploy and roll back: %s"
               % (_finish_url(current_props, STATUS='success'),
                  _finish_url(current_props, STATUS='rollback', WHY='aborted',
                              ROLLBACK_TO=current_props['ROLLBACK_TO'])))
    else:
        msg = (":white_check_mark: release the lock: %s"
               % _finish_url(current_props, STATUS='unlock'))

    _alert(props,
           "%s has been deploying for over %s minutes. "
           "Perhaps it's a stray lock?  If you are confident that "
           "no deploy is currently running (check the dashboard at %s), "
           "you can:\n"
           "%s\n"
           "Once you done this, you will need to re-start your own deploy."
           % (current_props['DEPLOYER_USERNAME'],
              waited_sec / 60,
              current_props['JENKINS_URL'],
              msg),
           severity=logging.ERROR)
    raise RuntimeError('Timed out waiting on the current lock-holder.')


def _move_lockdir(props, old_lockdir, new_lockdir):
    """Re-acquire the lock in new_lockdir with the values in old_lockdir.

    new_lockdir must not exist (meaning that nobody else is holding the
    lock there).  If new_lockdir does exist, raise an OSError.

    Raises:
        OSError if we failed to move the lockdir.
    """
    if os.path.exists(new_lockdir):
        raise OSError('Lock already held in "%s"' % new_lockdir)

    logging.info('Renaming %s -> %s' % (old_lockdir, new_lockdir))
    os.rename(old_lockdir, new_lockdir)

    # Update the LOCKDIR property to point to the new location.
    _update_properties(props, {'LOCKDIR': os.path.abspath(new_lockdir)})


def release_deploy_lock(props, backup_lockfile=True):
    """Raise RuntimeError if the release failed."""
    # We move the lockdir to a 'backup' lockdir in case it turns out
    # we want to re-acquire this lock with the same parameters.
    # (This might happen if we released the lockdir in error.)
    lockdir = props['LOCKDIR']
    old_lockdir = lockdir + '.last'

    try:
        shutil.rmtree(old_lockdir)
    except OSError:        # probably 'dir does not exist'
        pass

    try:
        if backup_lockfile:
            _move_lockdir(props, lockdir, old_lockdir)
        else:
            shutil.rmtree(lockdir)
    except (IOError, OSError) as why:
        _alert(props,
               "Could not release the deploy-lock (%s); it's not being held? "
               "(%s)" % (lockdir, why),
               severity=logging.ERROR)
        raise RuntimeError('Could not release the deploy-lock (%s)' % why)

    logging.info('Released the deploy lock: %s' % lockdir)


def merge_from_master(props):
    """Merge master into the current branch if necessary.

    Given an argument that contains either the name of a branch or a
    different kind of commit-ish (sha1, tag, etc) in GIT_REVISION,
    does two things:

    1) Ensures that HEAD matches that argument -- that is, that you're
       checked out where you expect to be -- and then does a
       git checkout <branch> so we are no longer in a detached-head
       state.

    2) Check if the input sha1 is a superset of master (that is,
       everything in master is part of this sha1's history too).
       If not:
    2a) If the argument is a branch-name, merge master into the branch.
    2b) If the argument is another commit-ish, fail.

    Raises:
       ValueError, RuntimeError, or subprocess.CalledProcessError if
       the merge from master failed for any reason.  This means we
       should abort the build and release the lock.
    """
    git_revision = props['GIT_REVISION']
    if git_revision == 'master':
        raise ValueError("You must deploy from a branch, you can't deploy "
                         "from master")

    # Make sure our local 'master' matches the remote.
    _run_command(['git', 'fetch', 'origin',
                  '+refs/heads/master:refs/remotes/origin/master'])
    _run_command(['git', 'checkout', 'master'])
    _run_command(['git', 'reset', '--hard', 'origin/master'])

    # Set our local branch to be the same as the origin branch.  This
    # is needed in cases when a previous deploy set the local (jenkins)
    # branch to commit X, but subsequent commits have moved the remote
    # (github) version of the branch to commit Y.  This also moves us
    # from a (potentially) detached-head state to a head-at-branch state.
    # Finally, it makes sure the ref exists locally, so we can do
    # 'git rev-parse branch' rather than 'git rev-parse origin/branch'
    # (though only if we're given a branch rather than a commit).
    if _run_command(['git', 'ls-remote', '--exit-code',
                     '.', 'origin/%s' % git_revision],
                    failure_ok=True):
        _run_command(['git', 'fetch', 'origin',
                      '+refs/heads/%s:refs/remotes/origin/%s'
                      % (git_revision, git_revision)])
        # The '--' is needed if git_revision is both a branch and
        # directory, e.g. 'sat'.  '--' says 'treat it as a branch'.
        _run_command(['git', 'checkout', git_revision, '--'])
        _run_command(['git', 'reset', '--hard', 'origin/%s' % git_revision])
    else:
        _run_command(['git', 'checkout', git_revision, '--'])

    head_commit = _pipe_command(['git', 'rev-parse', 'HEAD'])
    master_commit = _pipe_command(['git', 'rev-parse', 'master'])

    # Sanity check: HEAD should be at the revision we want to deploy from.
    if head_commit != _pipe_command(['git', 'rev-parse', git_revision]):
        raise RuntimeError('HEAD unexpectedly at %s, not %s'
                           % (head_commit, git_revision))

    # If the current commit is a super-set of master, we're done, yay!
    base = _pipe_command(['git', 'merge-base', git_revision, master_commit])
    if base == master_commit:
        logging.info('%s is a superset of master, no need to merge'
                     % git_revision)
        return

    # Now we need to merge master into our branch.  First, make sure
    # we *are* a branch.  git show-ref returns line like 'd41eba92 refs/...'
    all_branches = _pipe_command(['git', 'show-ref']).splitlines()
    all_branch_names = [l.split()[1] for l in all_branches]
    if ('refs/remotes/origin/%s' % git_revision) not in all_branch_names:
        raise ValueError('%s is not a branch name on the remote, like these:'
                         '\n  %s' % (git_revision,
                                     '\n  '.join(sorted(all_branch_names))))

    # The merge exits with rc > 0 if there were conflicts
    logging.info("Merging master into %s" % git_revision)
    try:
        _run_command(['git', 'merge', 'master'])
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


def _tag_release(props):
    """Tag the github commit that was deployed with the deploy-name."""
    tag_name = 'gae-%s' % props['VERSION_NAME']
    # Don't try to re-create the tag if it already exists.
    if not _pipe_command(['git', 'tag', '-l', tag_name]):
        _run_command(
            ['git', 'tag',
             '-m',
             'Deployed to appengine from branch %s' % props['GIT_REVISION'],
             tag_name,
             props['GIT_SHA1']])


def _tag_as_bad_version(props):
    """Tag the currently deployed github commit as having problems."""
    tag_name = 'gae-%s-bad' % props['VERSION_NAME']
    # Don't try to re-create the tag if it already exists.
    if not _pipe_command(['git', 'tag', '-l', tag_name]):
        _run_command(
            ['git', 'tag',
             '-m', 'Bad version (%s): rolled back' % props['VERSION_NAME'],
             tag_name,
             props['GIT_SHA1']])


def merge_to_master(props):
    """Merge from the current branch into master.

    This is called after a successful deploy, right before releasing
    the lock.  It maintains the invariant that master holds the code
    for the latest successful deploy.

    Given an argument that holds the deployed-sha1 in GIT_SHA1, merges
    that into master and pushes.  In a perfect world -- that is, one
    in which people don't commit to master manually, it only happens
    via this function -- this will be a fast-forward merge, since we
    already required that our branch be a superset of master in
    merge_from_master().

    Raises:
       RuntimeError or subprocess.CalledProcessError if the merge
       failed, meaning we should abort the build and release the lock.
    """
    if _DRY_RUN:
        return

    branch_name = '%s (%s)' % (props['GIT_SHA1'], props['GIT_REVISION'])

    # Set our local version of master to be the same as the origin
    # master.  This is needed in cases when a previous deploy set the
    # local (jenkins) master to commit X, but subsequent commits have
    # moved the remote (github) version of master to commit Y.  It
    # also makes sure the ref exists locally, so we can do the merge.
    _run_command(['git', 'fetch', 'origin',
                  '+refs/heads/master:refs/remotes/origin/master'])
    _run_command(['git', 'checkout', 'master'])
    _run_command(['git', 'reset', '--hard', 'origin/master'])
    head_commit = _pipe_command(['git', 'rev-parse', 'HEAD'])

    # The merge exits with rc > 0 if there were conflicts
    logging.info("Merging %s into master" % branch_name)
    try:
        _run_command(['git', 'merge', props['GIT_SHA1']])
    except subprocess.CalledProcessError:
        _run_command(['git', 'merge', '--abort'], failure_ok=True)
        raise

    # There's a race condition if someone commits to master while this
    # script is running, so check for that.
    try:
        _run_command(['git', 'push', '--tags', 'origin', 'master'])
    except subprocess.CalledProcessError:
        _run_command(['git', 'reset', '--hard', head_commit],
                     failure_ok=True)
        raise

    logging.info("Done merging %s into master" % branch_name)


def _rollback_deploy(props):
    """Roll back to ROLLBACK_TO and tag the current deploy as bad.

    Returns True if rollback succeeded -- even if we failed to tag the
    version as bad after rolling back from it -- False else.
    """

    try:
        current_gae_version = _current_gae_version()
    except Exception as why:
        logging.info("Failed to get live site version.", exc_info=why)
        logging.info("Proceeding with the assumption that the live site "
                     "version is %s.", props['VERSION_NAME'])
    else:
        if current_gae_version != props['VERSION_NAME']:
            logging.info("Skipping rollback: looks like our deploy never "
                         "succeeded. (Us: %s, current: %s, rollback-to: %s)"
                         % (props['VERSION_NAME'],
                            current_gae_version,
                            props['ROLLBACK_TO']))
            return True

    _alert(props,
           "Automatically rolling the default back to %s "
           "and tagging %s as bad (in git)"
           % (props['ROLLBACK_TO'], props['VERSION_NAME']))
    try:
        logging.info('Calling set_default to %s' % props['ROLLBACK_TO'])
        with _password_on_stdin(props['DEPLOY_PW_FILE']):
            deploy.rollback.main(bad_version=props['VERSION_NAME'],
                                 good_version=props['ROLLBACK_TO'],
                                 email=props['DEPLOY_EMAIL'],
                                 passin=True,
                                 dry_run=_DRY_RUN)

        # If the version we rolled back *to* is marked bad, warn about that.
        if _pipe_command(['git', 'tag', '-l',
                          '%s-bad' % props['ROLLBACK_TO']]):
            _alert(props,
                   ':poop: WARNING: Rolled back to %s, but that version '
                   'has itself been marked as bad.  You may need to manually '
                   'run set_default.py to roll back to a safe version.  (Run '
                   '"git tag" to see all versions, good and bad.)'
                   % props['ROLLBACK_TO'])
    except Exception:
        logging.exception('Auto-rollback failed')
        _alert(props,
               ':worried: :worried: Auto-rollback failed! '
               'Roll back to %(good)s manually by running: '
               'deploy/rollback.py --bad "%(bad)s" --good "%(good)s"'
               % {'bad': props['VERSION'], 'good': props['ROLLBACK_TO']},
               severity=logging.CRITICAL)
        return False

    return True


def manual_test(props):
    """Send a message to hipchat saying to do pre-set-default manual tests."""
    hostname = '%s-dot-khan-academy.appspot.com' % props['VERSION_NAME']

    deploymsg_plaintext = (
        'Hey %s, https://%s/ (branch %s) is uploaded to appengine! '
        'Do some manual testing on it, then either:'
        '\n- set it as default: type "sun, set default" or visit %s\n'
        '\n- abort the deploy: type "sun, abort" or visit %s'
        % (props['DEPLOYER_USERNAME'], hostname, props['GIT_REVISION'],
           _set_default_url(props, AUTO_DEPLOY=props['AUTO_DEPLOY']),
           _finish_url(props, STATUS='failure', WHY='aborted'))
    )

    deploymsg_attachment = {
        'fallback': deploymsg_plaintext,
        'pretext': 'Hey %(user)s, `<%(url)s|%(appengine_id)s>` (branch '
                   '`%(branch)s`) is uploaded to AppEngine!' % {
                       'url': hostname,
                       'appengine_id': props['VERSION_NAME'],
                       'user': props['DEPLOYER_USERNAME'],
                       'branch': props['GIT_REVISION'],
                   },
        'fields': [
            {
                'title': 'all looks good :rocket:',
                'value': u':speech_balloon: _“sun, set default”_ '
                         u'(or <%s|click me>)' % (_set_default_url(
                             props, AUTO_DEPLOY=props['AUTO_DEPLOY'])),
                'short': True
            },
            {
                'title': 'abort the deploy :skull:',
                'value': u':speech_balloon: _“sun, abort”_ '
                         u'(or <%s|click me>)' % (_finish_url(
                             props, STATUS='failure', WHY='aborted')),
                'short': True
            }],
        'color': 'good',
        'mrkdwn_in': ['pretext', 'text', 'fields'],
    }

    # Suggest some urls to do for manual testing, as both links and a
    # commandline tool.
    testmsg_plaintext = (
        "Here are some pages to manually test:\n"
        "%(pages)s\n"
        "Or open them all at once (cut-and-paste): "
        "  $ tools/manual_webapp_testing.py %(version)s\n\n"
        "Also run end-to-end testing (cut-and-paste): "
        "  $ tools/end_to_end_webapp_testing.py --version %(version)s</b>"
        % {
            'pages': '\n'.join('%s: %s' % (title, url)
                               for title, url
                               in manual_webapp_testing.pages_to_test(
                               props['VERSION_NAME'])),
            'version': props['VERSION_NAME']
        }
    )

    # TODO(mroth): the HTML mesasge is used only for HipChat and can be removed
    # when hipchat is fully deprecated.
    testmsg_html = (
        "Here are some pages to manually test:<br>%s<br>"
        "Or open them all at once (cut-and-paste): "
        "<b>tools/manual_webapp_testing.py %s\n\n"
        "Also run end-to-end testing (cut-and-paste): "
        "<b>tools/end_to_end_webapp_testing.py --version %s</b>"
        % (manual_webapp_testing.list_with_links(props['VERSION_NAME']),
           props['VERSION_NAME'], props['VERSION_NAME'])
    )

    testmsg_attachment = {
        'fallback': testmsg_plaintext,
        'pretext': 'Before deciding, here are some pages to manually test:',
        'text': ' '.join('`<%s|%s>`' % (url, title)
                         for title, url
                         in manual_webapp_testing.pages_to_test(
            props['VERSION_NAME'])),
        'fields': [
            {
                'title': 'Open them all at once:',
                'value': '`tools/manual_webapp_testing.py %s`' %
                         props['VERSION_NAME'],
                'short': False,
            },
            {
                'title': 'Run end-to-end testing',
                'value': '`tools/end_to_end_webapp_testing.py --version %s`' %
                         props['VERSION_NAME'],
                'short': False,
            }
        ],
        'mrkdwn_in': ['fields', 'text']
    }

    alert = alertlib.Alert(deploymsg_plaintext + "\n\n" + testmsg_plaintext,
                           severity=logging.INFO)
    alert.send_to_logs()
    _alert_to_slack(
        props, alert,
        attachments=[deploymsg_attachment, testmsg_attachment])

    # hipchat requires two messages to be sent seperately, with a delay so they
    # are not out of order.
    # TODO: remove me once HipChat is deprecated
    _alert_to_hipchat(
        props,
        alertlib.Alert(deploymsg_plaintext, severity=logging.INFO, html=False),
        color='green')
    time.sleep(1)
    _alert_to_hipchat(
        props,
        alertlib.Alert(testmsg_html, severity=logging.INFO, html=True))


def set_default(props, monitoring_time=10, jenkins_build_url=None):
    """Call set_default.py to make a specified deployed version live.

    If the user asked for monitoring, also do the monitoring, potentially
    rolling back if there's a problem.

    Raises:
        RuntimeError or deploy.set_default.MonitoringError if we
        encountered an error that should cause the build to abort and
        jenkins to release the build lock.  We do not raise an
        exception if the build should continue, either because we
        emitted a hipchat message telling the user to click on a link
        to take us to the next step, or because set-default failed and
        was successfully auto-rolled back.
    """
    logging.info("Changing default from %s to %s"
                 % (props['ROLLBACK_TO'], props['VERSION_NAME']))
    # I do the deploy steps one at a time so I can intersperse some
    # hipchat mesasges.
    did_priming = False
    try:
        pre_monitoring_data = deploy.set_default.get_predeploy_monitoring_data(
            monitoring_time)

        logging.info("Priming 100 instances")
        deploy.set_default.prime(version=props['VERSION_NAME'],
                                 num_instances_to_prime=100,
                                 dry_run=_DRY_RUN)
        did_priming = True

        logging.info("Setting default")
        with _password_on_stdin(props['DEPLOY_PW_FILE']):
            deploy.set_default.set_default(version=props['VERSION_NAME'],
                                           email=props['DEPLOY_EMAIL'],
                                           passin=True,
                                           dry_run=_DRY_RUN)

        if (monitoring_time and jenkins_build_url and
                props['AUTO_DEPLOY'] != 'true'):
            # TODO(bmp): When HipChat dies, this can be one alert with
            # two attachments
            deploy_attachments = [{
                'pretext': 'Hey %(user)s, `%(appengine_id)s` is now the '
                           "default generation! I'll be monitoring the logs "
                           'for %(minutes)s minutes, and then will post the '
                           'results. If you detect a problem in the meantime,'
                           'you can cancel the deploy.' % {
                               'appengine_id': props['VERSION_NAME'],
                               'user': props['DEPLOYER_USERNAME'],
                               'minutes': monitoring_time,
                    },
                'fields': [{
                    'title': 'abort the deploy :skull:',
                    'value': '<%s/stop|click to abort>' % (
                        jenkins_build_url.rstrip('/')
                    ),
                    'short': True
                }],
                'color': 'good',
                'mrkdwn_in': ['pretext', 'fields'],
            }]
            _alert(props,
                   "I've deployed to %s, and will be monitoring "
                   "logs for %s minutes.  After that, I'll post "
                   "next steps to HipChat.  If you detect a problem in "
                   "the meantime you can cancel the deploy (note: this "
                   "link will only work for the next %s minutes):\n"
                   ":no_good: abort and rollback: %s/stop"
                   % (props['VERSION_NAME'], monitoring_time, monitoring_time,
                      jenkins_build_url.rstrip('/')),
                   attachments=deploy_attachments)
            time.sleep(1)  # to help the two hipchat alerts be ordered properly
            test_attachments = [{
                'pretext': "While that's going on, here are some pages to "
                           'manually test:',
                'text': ' '.join('`<%s|%s>`' % (url, title)
                                 for title, url
                                 in manual_webapp_testing.pages_to_test(
                    props['VERSION_NAME'])),
                'fields': [
                    {
                        'title': 'Open them all at once:',
                        'value': '`tools/manual_webapp_testing.py %s`' %
                                 props['VERSION_NAME'],
                        'short': False,
                    },
                    {
                        'title': 'Run end-to-end testing',
                        'value': '`tools/end_to_end_webapp_testing.py '
                                 '--version %s`' % props['VERSION_NAME'],
                        'short': False,
                    }
                ],
                'mrkdwn_in': ['fields', 'text']
            }]
            _alert(props,
                   ("While that's going on, manual-test on the live site!<br>"
                    "%s<br>\n"
                    "Or open them all at once (cut-and-paste): "
                    "<b>tools/manual_webapp_testing.py %s</b><br>"
                    "Also run end-to-end testing (cut-and-paste): "
                    "<b>tools/end_to_end_webapp_testing.py --version %s</b>"
                    % (manual_webapp_testing.list_with_links('default'),
                       'default', 'default')),
                   html=True, prefix_with_username=False,
                   attachments=test_attachments)

        deploy.set_default.monitor(props['VERSION_NAME'], monitoring_time,
                                   pre_monitoring_data,
                                   hipchat_room=props['HIPCHAT_ROOM'],
                                   slack_channel=props['SLACK_CHANNEL'])

    except deploy.set_default.MonitoringError as why:
        # Wait a little to make sure this hipchat message comes after
        # the "I've deployed to ..." message we emitted above above.
        time.sleep(10)
        if props['AUTO_DEPLOY'] == 'true':
            _alert(props,
                   ":worried: %s." % why,
                   severity=logging.WARNING)
            # By re-raising, we trigger the deploy-set-default jenkins
            # job to clean up by rolling back.  auto-rollback for free!
            raise
        else:
            finish_attachments = [{
                'pretext': u":worried: Jenkins says, “`%s`”. "
                           u"Please double-check manually that everything "
                           u"is okay." % why,
                'fields': [
                    {
                        'title': 'deploy anyway :yolo:',
                        'value': u':speech_balloon: _“sun, finish up”_ '
                                 u'(or <%s|click me>)' %
                                 _finish_url(props, STATUS='success'),
                        'short': True,
                    },
                    {
                        'title': 'abort the deploy :skull:',
                        'value': u':speech_balloon: _“sun, abort”_ '
                                 u'(or <%s|click me>)' %
                                 _finish_url(props, STATUS='rollback',
                                             WHY='aborted',
                                             ROLLBACK_TO=props['ROLLBACK_TO']),
                        'short': True,
                    }
                ],
                'mrkdwn_in': ['fields', 'text']
            }]
            _alert(props,
                   ':worried: %s. '
                   'Make sure everything is ok, then:\n'
                   ':+1: finish up: type "sun, finish up" '
                   'or visit %s\n'
                   ':no_good: abort and roll back: type "sun, abort" '
                   'or visit %s'
                   % (why,
                      _finish_url(props, STATUS='success'),
                      _finish_url(props, STATUS='rollback', WHY='aborted',
                                  ROLLBACK_TO=props['ROLLBACK_TO'])
                      ),
                   severity=logging.WARNING,
                   attachments=finish_attachments)
    except Exception:
        logging.exception('set-default failed')
        if props['AUTO_DEPLOY'] == 'true':
            _alert(props, ":worried: :worried: set-default failed!",
                   severity=logging.ERROR)
            raise

        if did_priming:
            priming_flag = '--no-priming '
        else:
            priming_flag = ''

        _alert(props,
               ":worried: :worried: set-default failed!  Either:\n"
               ":white_check_mark: Set the default to %s manually (by running "
               "deploy/set_default.py %s%s), then release the deploy lock "
               "via %s\n"
               ":no_good: abort and roll back %s"
               % (props['VERSION_NAME'], priming_flag, props['VERSION_NAME'],
                  _finish_url(props, STATUS='success'),
                  _finish_url(props, STATUS='rollback', WHY='aborted',
                              ROLLBACK_TO=props['ROLLBACK_TO'])),
               severity=logging.CRITICAL)
    else:
        # No need for a hipchat message if the next step is automatic.
        if props['AUTO_DEPLOY'] != 'true':
            _alert(props,
                   'Monitoring passed for the new default (%s)! '
                   'But you should double-check everything '
                   'is ok at https://www.khanacademy.org. '
                   'Then:\n'
                   ':+1: finish up: type "sun, finish up" '
                   'or visit %s\n'
                   ':no_good: abort and roll back: type "sun, abort" '
                   'or visit %s'
                   % (props['VERSION_NAME'],
                      _finish_url(props, STATUS='success'),
                      _finish_url(props, STATUS='rollback', WHY='aborted',
                                  ROLLBACK_TO=props['ROLLBACK_TO'])),
                   color='green')


def finish_with_unlock(props, caller=None):
    """Manually release the deploy lock.  Caller is the 'manual' person.

    This is called when something is messed up and the lock is being
    held even though no deploy is going on.

    Raises RuntimeError if we failed to release the lock.
    """
    if caller == props['DEPLOYER_USERNAME']:
        # You are releasing your own lock
        _alert(props, "has manually released the deploy lock.")
    else:
        _alert(props,
               ": %s has manually released the deploy lock." %
               (caller or props['DEPLOYER_USERNAME']))
    release_deploy_lock(props)


def finish_with_success(props):
    """Release the deploy lock because the deploy succeeded.

    We also merge the deployed commit to master, to maintain the
    invariant that 'master' holds the last successful deploy.

    Raises RuntimeError or subprocess.CalledProcessError if we failed
    to release the lock or if we failed to finish the deploy
    (e.g. failed to merge back into master.)
    """
    # We don't want to tag the release if the user ran with DEPLOY=no.
    # We tell by checking if the current gae version is VERSION_NAME.
    if _current_gae_version() == props['VERSION_NAME']:
        _tag_release(props)
    try:
        merge_to_master(props)
    except Exception:
        _alert(props,
               ":worried: Deploy of %s (branch %s) succeeded, "
               "but we did not successfully merge %s into master. "
               "Merge and push manually, then release the lock: %s"
               % (props['VERSION_NAME'], props['GIT_REVISION'],
                  props['GIT_REVISION'], _finish_url(props, STATUS='unlock')),
               severity=logging.ERROR)
        raise

    _alert(props,
           ":smile_cat: Deploy of %s (branch %s) succeeded! "
           "Time for a happy dance!"
           % (props['VERSION_NAME'], props['GIT_REVISION']),
           color='green')
    release_deploy_lock(props, backup_lockfile=False)


def finish_with_failure(props):
    """Release the deploy lock after a failed deploy, or raise if we can't."""
    if props['LAST_ERROR']:
        why = ": %s" % props['LAST_ERROR']
    else:
        why = ". I'm sorry."
    _alert(props,
           ":flushed: Deploy of %s (branch %s) failed%s"
           % (props['VERSION_NAME'], props['GIT_REVISION'], why),
           severity=logging.ERROR)
    release_deploy_lock(props)


def finish_with_rollback(props):
    """Do a rollback and releases the lock if it succeeds."""
    if props['LAST_ERROR']:
        _alert(props,
               "Rolling back %s due to problems with the deploy: %s"
               % (props['VERSION_NAME'], props['LAST_ERROR']),
               severity=logging.ERROR)
    if not _rollback_deploy(props):
        _alert(props,
               "Once you have manually rolled back, release the deploy "
               "lock: %s" % _finish_url(props, STATUS='unlock'),
               severity=logging.ERROR)
        raise RuntimeError('Failed to roll back to the previous deploy.')
    finish_with_failure(props)


def relock(props):
    """Re-acquire the lockdir from a backup lockdir directory.

    You call relock with --lockdir=<somedir>.last.  It then renames
    <somedir>.last to <somedir>, thus re-acquiring the lock in
    <somedir>.

    Raises OSError or ValueError if we can't relock, probably because
    someone else has acquired the lock themselves before we could
    re-acquire it.
    """
    old_lockdir = props['LOCKDIR']
    new_lockdir = old_lockdir[:-len('.last')]

    if not old_lockdir.endswith('.last'):
        logging.error('Unexpected value for --lockdir: "%s" does not end '
                      'with ".last"' % props['LOCKDIR'])
        raise ValueError('lockdir "%s" does not end with ".last"'
                         % props['LOCKDIR'])

    if os.path.exists(new_lockdir):
        logging.error("Cannot relock %s -- someone else has already "
                      "acquired the lock since you released it." % new_lockdir)
        raise OSError('%s already exists' % new_lockdir)

    _move_lockdir(props, old_lockdir, new_lockdir)


def _create_or_read_properties(action, invocation_details):
    """Initialize the lock structure and returns the lock properties.

    :param str action: an action, which must be one of DEPLOY_ACTIONs
    :param InvocationDetails invocation_details: the arguments to be used for
        acquiring the lock, or augmenting an existing lock
    :return: a dict of the props
    :rtype: dict
    """

    # Some properties are by definition invocation-specific, but are still
    # very useful to keep in the main props dict (and, for debugging
    # reasons, even handy to have on disk in the lock file). This function
    # injects invocation-specific values into the props dict.
    def enrich_props_with_invocation(lock_props):
        lock_props['JENKINS_JOB_URL'] = invocation_details.jenkins_job_url
        lock_props['MONITORING_TIME'] = invocation_details.monitoring_time
        return lock_props

    if action == 'acquire-lock':
        return enrich_props_with_invocation(
            _create_properties(invocation_details))
    else:
        try:
            return enrich_props_with_invocation(
                _read_properties(invocation_details.lockdir))
        except IOError:
            if action == 'relock':
                logging.exception('There is no backup lock at %s to recover '
                                  'from, sorry.' % invocation_details.lockdir)
            else:
                # We can't load the real props, so do the best we can.
                minimally_viable_props = {
                    'DEPLOYER_USERNAME':
                        invocation_details.deployer_username,
                    'HIPCHAT_ROOM': '1s/0s: deploys',
                    'CHAT_SENDER': 'Mr Gorilla',
                    'SLACK_CHANNEL': '#1s-and-0s-deploys',
                    'ICON_EMOJI': ':monkey_face:',
                    'JENKINS_URL': 'https://jenkins.khanacademy.org/',
                    'TOKEN': '',
                }
                _alert(minimally_viable_props,
                       ':worried: Trying to run without the lock. '
                       'If you think you *should* have the lock, '
                       'try to re-acquire it: %s. Then run your command again.'
                       % _finish_url(minimally_viable_props, STATUS='relock'),
                       severity=logging.ERROR)
            return None


def _action_acquire_lock(props):
    """Acquire the deploy lock with the specified properties."""
    acquire_deploy_lock(props, props['JENKINS_JOB_URL'])
    _write_properties(props)
    _update_properties(props,
                       {'POSSIBLE_NEXT_STEPS': 'merge-from-master'})


def _action_merge_from_master(props):
    """Merge master into the current branch if necessary."""
    merge_from_master(props)
    # Now we need to update the props file to indicate the new
    # GIT_SHA1 after merging.  (This also updates VERSION_NAME.)
    sha1 = _pipe_command(['git', 'rev-parse', props['GIT_REVISION']])
    # We can go straight to set-default if the user ran with
    # AUTO_DEPLOY, and straight to finish if they ran with DEPLOY=no.
    if props['AUTO_DEPLOY'] == 'true':
        next_steps = 'set-default'
    else:
        next_steps = 'manual-test'
    # We don't know if the user ran with DEPLOY=no, so always allow it.
    next_steps += ',finish-with-success'
    _update_properties(props,
                       {'GIT_SHA1': sha1,
                        'POSSIBLE_NEXT_STEPS': next_steps})


def _action_manual_test(props):
    """Send a chat message telling users to do pre-set-default testing."""
    manual_test(props)
    _update_properties(props,
                       {'POSSIBLE_NEXT_STEPS': 'set-default'})


def _action_set_default(props):
    """Set the default GAE generation after it's been uploaded."""
    set_default(props, monitoring_time=props['MONITORING_TIME'],
                jenkins_build_url=props['JENKINS_JOB_URL'])
    # If set_default didn't raise an exception, all is happy.
    if props['AUTO_DEPLOY'] == 'true':
        finish_with_success(props)
    else:
        _update_properties(props,
                           {'POSSIBLE_NEXT_STEPS': 'finish-with-success'})


def _action_finish_with_unlock(props):
    """Manually release the deploy lock."""
    finish_with_unlock(props, props['DEPLOYER_USERNAME'])


def _action_finish_with_success(props):
    """Release the deploy lock after a successful deploy."""
    finish_with_success(props)


def _action_finish_with_failure(props):
    """Release the deploy lock and aborts, pre-set-default."""
    finish_with_failure(props)


def _action_finish_with_rollback(props):
    """Release the deploy lock and rolls back, post-set-default."""
    finish_with_rollback(props)


def _action_relock(props):
    """Re-acquire the lock from lockdir.last, if possible."""

    relock(props)
    # You relock when something went wrong, so any step could
    # legitimately go next.
    _update_properties(props,
                       {'POSSIBLE_NEXT_STEPS': '<all>'})


KNOWN_ACTIONS = {
    # acquire the deploy lock
    'acquire-lock': _action_acquire_lock,
    # merge master into the current branch if necessary
    'merge-from-master': _action_merge_from_master,
    # send a chat message saying to do pre-set-default testing
    'manual-test': _action_manual_test,
    # set this version to GAE default after it's been uploaded.
    'set-default': _action_set_default,
    # manually release the deploy lock
    'finish-with-unlock': _action_finish_with_unlock,
    # manually release the deploy lock because the deploy succeeded
    'finish-with-success': _action_finish_with_success,
    # manually release the deploy lock b/c the deploy failed (pre-set-default)
    'finish-with-failure': _action_finish_with_failure,
    # manually release the deploy lock b/c the deploy failed (post-set-default)
    'finish-with-rollback': _action_finish_with_rollback,
    # re-acquire the lock from lockdir.last, if possible
    'relock': _action_relock,
}


def main(action, invocation_details):
    """Handle a given deploy sequence command.

    The commands raise an exception if we should stop the pipeline
    there, due to failure.  In those cases, we return False, which
    will cause the calling Jenkins job to abort and try to release the
    lock.  (Exception: the deploy-finish Jenkins job does not try to
    release the lock when we return False, though it does abort,
    because the usual reason a finish_* step fails is because it tried
    to release the lock and couldn't, so why try again?)  On the other
    hand, if the command does not raise an exception, we return True,
    which will cause the Jenkins job to say that this step succeeded.

    :param str action: one of DEPLOY_ACTIONS; see documentation there
    :param InvocationDetails invocation_details: all the details of this
        deploy; see the InvocationDetails and KNOWN_ACTIONS structure for
        more information
    :return: Unix-style command result code (nonzero means failure)
    """

    assert action in KNOWN_ACTIONS, 'Unknown action: %s' % action

    props = _create_or_read_properties(action, invocation_details)
    if props is None:
        return False

    # If the passed-in token doesn't match the token in props, then
    # we are not the owners of this lock, so fail.  This is an
    # optional check.
    if (invocation_details.token and props.get('TOKEN') and
            invocation_details.token != props['TOKEN']):
        _alert(props,
               'You do not own the deploy lock (its token is %s, '
               'yours is %s); aborting' % (props['TOKEN'],
                                           invocation_details.token),
               severity=logging.ERROR,
               # The username in props probably isn't right -- if we
               # don't match prop's token, why would we match its
               # username? -- so don't prepend it.
               # TODO(csilvers): pass in the *right* username instead.
               prefix_with_username=False)
        # We definitely don't want jenkins to release the lock, since
        # we don't own it.  So we have to return True.
        return True

    # If the step we're taking doesn't match a legal next-step in the
    # pipeline, fail.
    allowed_steps = props['POSSIBLE_NEXT_STEPS'].split(',')
    if action not in allowed_steps and '<all>' not in allowed_steps:
        _alert(props,
               'Expecting you to run %s, but you are running %s. '
               'Perhaps you double-clicked on a link?  Ignoring.'
               % (" or ".join(props['POSSIBLE_NEXT_STEPS'].split(",")),
                  action),
               severity=logging.ERROR)
        # We just ignore this action, so we don't release the lock.
        return True

    try:
        KNOWN_ACTIONS[action](props)
        if os.path.exists(os.path.join(props['LOCKDIR'], 'deploy.prop')):
            _update_properties(props, {'LAST_ERROR': ''})
        return True
    except Exception as why:
        logging.exception(action)
        if action != 'acquire-lock':
            # Don't write the properties file if we failed in trying
            # to acquire the lock! -- writing the properties file
            # would then acquire the lock for us by accident.
            _update_properties(props, {'LAST_ERROR': str(why)})
        return False


def parse_args_and_invoke_main():
    parser = argparse.ArgumentParser()
    parser.add_argument('action',
                        choices=KNOWN_ACTIONS,
                        help='Action to perform')
    parser.add_argument('--lockdir',
                        default='tmp/deploy.lockdir',
                        help=("The lock-directory, ideally an absolute path. "
                              "The existence of this directory indicates "
                              "ownership of the deploy lock."))
    # These flags are only needed for acquire-lock.
    parser.add_argument('--deployer_email',
                        help=("Obsolete; use --deployer-username instead"))
    parser.add_argument('--deployer-username',
                        default='AnonymousCoward',
                        help='The chat handle of the user initiating this '
                             'deploy request')
    parser.add_argument('--git_revision',
                        help=("The branch-name (it can also just be a "
                              "commit id) being deployed."))
    parser.add_argument('--auto_deploy',
                        default='false',
                        help=("If 'true', don't ask whether to set the new "
                              "version as the default, do so automatically."))
    parser.add_argument('--jenkins_url',
                        default='https://jenkins.khanacademy.org/',
                        help="The url of the jenkins server.")
    parser.add_argument('--hipchat_room',
                        default='HipChat Tests',
                        help=("The room to send all hipchat notifications "
                              "to."))
    parser.add_argument('--hipchat_sender',
                        help=("Obsolete; use --chat-sender instead"))
    parser.add_argument('--chat-sender',
                        default='Testybot',
                        help='The user name to show for messages posted to'
                             ' chat')
    parser.add_argument('--slack_channel',
                        default='#bot-testing',
                        help='The Slack channel to receive all alerts')
    parser.add_argument('--icon_emoji',
                        default=':crocodile:',
                        help=('The emoji to use as a bot avatar for messages'
                              ' posted to Slack'))
    parser.add_argument('--deploy_email',
                        default='prod-deploy@khanacademy.org',
                        help="The AppEngine user to deploy as.")
    parser.add_argument('--deploy_pw_file',
                        default='%s/prod-deploy.pw' % os.environ['HOME'],
                        help=("The file holding deploy_email's "
                              "appengine password."))
    # This is only needed for acquire-lock, but if passed into any other
    # action, the action will ensure the token matches what's in the
    # lockfile before doing anything.
    parser.add_argument('--token',
                        default='',
                        help=("A random string to serve as a unique "
                              "identifier for this deploy."))

    # These flags are only used by set-default.
    parser.add_argument('--monitoring_time', type=int,
                        default=10,
                        help=("How long to monitor in set-default, in "
                              "minutes (0 to disable monitoring)."))
    # This is used to cancel running jobs
    parser.add_argument('--jenkins-build-url',
                        help=("The url of the job that is calling this: "
                              "https://jenkins.khanacademy.org/job/testjob/723/"
                              " or the like"))

    parser.add_argument('-n', '--dry-run',
                        action='store_true',
                        default=False,
                        help=('Output information, but do not actually run '
                              'the deploy; should only be used for testing'))

    args = parser.parse_args()

    # Make sure the _alert() logging shows up, and have the log-prefix
    # be prettier.
    logging.basicConfig(format="[%(levelname)s] %(message)s")
    logging.getLogger().setLevel(logging.INFO)

    # Handle _DRY_RUN being set
    global _DRY_RUN
    _DRY_RUN = args.dry_run

    # Handle deprecated options --hipchat_sender and --deployer-email
    chat_sender = args.chat_sender
    if args.hipchat_sender:
        logging.warn("The --hipchat_sender argument is deprecated; please use "
                     "--chat-sender instead")
        chat_sender = args.hipchat_sender

    # TODO(bmp): To avoid a lockstep Jenkins/tools upgrade, manually inject
    # an "@" in front of the username
    deployer_username = '@' + args.deployer_username
    if args.deployer_email:
        logging.warn('The --deployer_email argument is deprecated; please use '
                     '--deployer-username instead')
        deployer_username = '@' + args.deployer_email.split('@')[0]

    success = main(args.action,
                   invocation_details=InvocationDetails(
                       lockdir=os.path.abspath(args.lockdir),
                       deployer_username=deployer_username,
                       git_revision=args.git_revision,
                       auto_deploy=args.auto_deploy == 'true',
                       gae_version=_current_gae_version(),
                       jenkins_url=args.jenkins_url,
                       jenkins_job_url=args.jenkins_build_url,
                       monitoring_time=args.monitoring_time,
                       hipchat_room=args.hipchat_room,
                       chat_sender=chat_sender,
                       icon_emoji=args.icon_emoji,
                       slack_channel=args.slack_channel,
                       deploy_email=args.deploy_email,
                       deploy_pw_file=args.deploy_pw_file,
                       token=args.token))
    sys.exit(0 if success else 1)


if __name__ == '__main__':
    parse_args_and_invoke_main()
