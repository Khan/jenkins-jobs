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
# This script notices when that situation arises and sends a message
# to slack so that people can handle it properly.
#
# This script is meant to be run as the Jenkins user.

JENKINS_HOME=`grep ^jenkins: /etc/passwd | cut -d: -f6`

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
actual_workers=`lsof -u jenkins -a -i :22 | grep ssh | wc -l`

if [ "$actual_workers" -gt 0 -a "$actual_workers" -lt "$expected_workers" ]; then
    cat <<EOF | env PYTHONPATH="$HOME/alertlib_secret" \
                      "$HOME/alertlib/alert.py" \
                      --slack "#1s-and-0s-deploys,#infrastructure" \
                      --severity "error" \
                      --summary "Need to restart some jenkins workers"
Some of the Jenkins worker machines are in a bad state and must be restarted.

To do this, visit https://jenkins.khanacademy.org/computer.  Look in the
right navigation pane for workers that are marked "(offline)".  Click on
each one and then click the big "Launch agent" button.
EOF
fi
