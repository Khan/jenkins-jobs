#!/bin/sh

# A script that verifies that either all the jenkins worker machines are
# online, or all are offline.
#
# We use jenkins workers for running tests.  We use a plugin that takes
# all the worker machines offline when they've been idle for a while
# (since we don't run tests all the time).  In that state we expect
# all worker machines to be offline.  Otherwise, we expect them to all
# be online.
#
# However, sometimes it happens that one or two machines becomes
# offline even when they're supposed to be active, due to a kernel
# oops or crash or some other problem.  In that case, we have to
# manually restart the worker's ssh-connection from within jenkins.
#
# This script notices when that situation arises and is true for more
# than 5 minutes, and sends a message to slack so that people can
# handle it properly.
#
# This script is meant to be run as the `ubuntu` user.

JENKINS_HOME=`grep ^jenkins: /etc/passwd | cut -d: -f6`
LIVE_WORKERS_FILE="/tmp/live_workers.is_bad"

# We send a warning if failures have been going on since at least this time.
A_WHILE_AGO=`date +%s -d "5 minutes ago"`


warn() {
    cat <<EOF | env PYTHONPATH="$HOME/alertlib_secret" \
                      "$HOME/alertlib/alert.py" \
                      --slack "#1s-and-0s-deploys,#infrastructure" \
                      --severity "error" \
                      --summary "Need to restart some jenkins workers"
Some of the Jenkins worker machines are in a bad state and must be restarted.

To do this, visit https://jenkins.khanacademy.org/computer.  Look in
the right navigation pane for workers that are marked "(offline)".  If
*all* of them are offline, that's expected and you're done.  If some
are offline and some are online, on the other hand, click on each one
that is offline and then click the big "Launch agent" button.
EOF
}


# First, figure out how many workers we have.  We can get that from
# the jenkins configs.  We are looking for this section in config.xml:
# ```
#           <instanceCap>4</instanceCap>
#           <stopOnTerminate>true</stopOnTerminate>
#           <tags>
#             <hudson.plugins.ec2.EC2Tag>
#               <name>Name</name>
#               <value>jenkins ka-test worker</value>
#             </hudson.plugins.ec2.EC2Tag>
#           </tags>
# ```
expected_workers=`\
    grep -C10 "jenkins ka-test worker" "$JENKINS_HOME/config.xml" \
        | grep instanceCap | tr -cd 0123456789`

# Figure out how many ssh processes the jenkins user has open.
# We assume that jenkins doesn't need to ssh to anyone except the workers.
# (The 'grep' is just an easy way to get rid of the header line.)
# TODO(csilvers): figure out how to get permissions to see these processes
#     without requiring sudo.  Maybe run this as the jenkins user?
actual_workers=`sudo lsof -u jenkins -a -i :22 | grep ssh | wc -l`

if [ "$actual_workers" -gt 0 -a "$actual_workers" -lt "$expected_workers" ]; then
    # OK, let's see if this problem has been going on for a while.
    # The idea is we create LIVE_WORKERS_FILE every time we see a
    # problem and the file doesn't already exist, and delete it every
    # time we don't see a problem.  So if LIVE_WORKERS_FILE is at least
    # X minutes old, that means we've been failing for >=X minutes.
    if [ -e "$LIVE_WORKERS_FILE" ]; then
        if [ `stat -c %Y "$LIVE_WORKERS_FILE"` -le "$A_WHILE_AGO" ]; then
            warn
            # Make it so that we don't warn again for a little while.
            rm -f "$LIVE_WORKERS_FILE"
        else
            # Failures have been going on, but not long enough for us to
            # warn.  Let's just chill until next time.
            :
        fi
    else
        # We're failing, but LIVE_WORKERS_FILE doesn't exist.  That
        # means we're the first failure, so let's make a note of it!
        # We won't warn yet though; it's too early.
        touch "$LIVE_WORKERS_FILE"
    fi
else
    # Everything is ok, let's delete the "it's bad" file.  Happy times!
    rm -f "$LIVE_WORKERS_FILE"
fi
