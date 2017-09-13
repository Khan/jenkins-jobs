#!/bin/sh

# A script that verifies that all the jenkins worker machines are
# online while `make check` (or allcheck) are running.
#
# We use jenkins workers for running tests.  We use a plugin that
# takes all the worker machines offline when they've been idle for a
# while (since we don't run tests all the time).  In that state we
# expect all worker machines to be offline.  But when running tests,
# we expect them to all be online.
#
# However, sometimes it happens that one or two machines becomes
# offline even when they're supposed to be active, due to a kernel
# oops or crash or some other problem.  In that case, we have to
# manually restart the worker's ssh-connection from within jenkins.
#
# This script notices when that situation arises.  The way it works is
# that the `make check` (and allcheck) jobs create a file in /tmp when
# they start, and delete it when they end.  We check if that file
# exists and is at least 5 minutes old (to give workers time to start
# up).  If so, we require there to be 4 ssh connections to workers.
# If not, we complain to slack so that people can handle it properly.
#
# When all worker machines are running normally, we send a cleared
# alert to resolve any existing open alerts from the previous cronjob.
#
# This script is meant to be run as the `ubuntu` user.

# How many minutes we wait for the jenkins workers to start up.
GRACE_PERIOD=7

JENKINS_HOME=`grep ^jenkins: /etc/passwd | cut -d: -f6`


# TODO(csilvers): fix this script to correctly deal with the fact we
# have multiple banks of test-workers now.  And also two different
# types of worker machines.
exit 0


# Don't bother doing anything if make check hasn't been running for
# at least $GRACE_PERIOD minutes.
GT_GRACE_PERIOD=`expr "$GRACE_PERIOD" - 1`
if [ -z "`find /tmp/make_check.run -mmin +"$GT_GRACE_PERIOD" 2>/dev/null`" ]; then
    # File not found, or is younger than GT_GRACE_PERIOD.  We wait.
    exit 0
fi

# Figure out how many workers we have.  We can get that from the
# jenkins configs.  We are looking for this section in config.xml:
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

if [ "$actual_workers" -lt "$expected_workers" ]; then
    cat <<EOF | env PYTHONPATH="$HOME/alertlib_secret" \
                      "$HOME/alertlib/alert.py" \
                      --slack "#infrastructure-alerts" \
                      --aggregator "infrastructure" \
                      --aggregator-resource "jenkins" \
                      --aggregator-event-name "Workers Offline" \
                      --severity "error" \
                      --summary "Need to restart some jenkins workers"
Some of the Jenkins worker machines are in a bad state and must be restarted.

To do this, visit https://jenkins.khanacademy.org/computer.  Look in
the right navigation pane for workers that are marked "(offline)".  If
*all* of them are offline, that's expected and you're done.  If some
are offline and some are online, on the other hand, click on each one
that is offline and then click the big "Launch agent" button.

(If you do not see the "Launch agent" button, you may lack the necessary
permissions; ask for help on the #infrastructure slack room.)
EOF
elif [ "$actual_workers" -eq "$expected_workers" ]; then
    cat <<EOF | env PYTHONPATH="$HOME/alertlib_secret" \
                    "$HOME/alertlib/alert.py" \
                    --aggregator "infrastructure" \
                    --aggregator-resource "jenkins" \
                    --aggregator-event-name "Workers Offline" \
                    --aggregator-resolve \
                    --severity "info"
Jenkins machines functioning as expected.
EOF
fi
