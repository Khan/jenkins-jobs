#!/bin/sh

# A script that verifies that all jenkins jobs notify slack on failure.
#
# With a few exceptions which we hard-code below, we require all
# jenkins jobs to notify a slack room on failure.  Otherwise, we have
# no way of knowing when our jobs, which we think are working,
# actuallly are not.
#
# This is intended to be run as the ubuntu user on the jenkins machine.

JENKINS_HOME=`grep ^jenkins: /etc/passwd | cut -d: -f6`

# These are the jobs that are allowed not to send to slack for
# whatever reason.  The format of this list is
#    -e <jobname> -e <jobname> ...
# 1) workers: their parents send to slack so they don't have to.
# 2) jobs that 'symlink' to another job and do no useful work themselves.
EXCEPTIONS="-e make-check-worker -e e2e-test-worker -e content-publish-e2e-test -e e2e-test -e i18n-gcs-upload -e webapp-test"

errors=""

error() {
    errors="$errors\n$*"
}

for config in "$JENKINS_HOME"/jobs/*/config.xml; do
    job=`dirname $config | xargs -n1 basename`

    # Ignore whitelisted configs.
    echo "$job" | grep -q -x $EXCEPTIONS && continue

    # Ignore disabled configs
    grep -q "<disabled>true</disabled>" "$config" && continue

    # Ignore folders (which have config.xml's but aren't jobs)
    grep -q "<com.cloudbees.hudson.plugins.folder.Folder" "$config" && continue

    # Ignore pipeline jobs (which do slack notification from within groovy)
    grep -q "<flow-definition " "$config" && continue

    # Make sure we send to slack on all the failure modes.
    grep -q "<notifyAborted>true</notifyAborted>" "$config" || error "$job: should send to slack on 'Aborted'"
    grep -q "<notifyNotBuilt>true</notifyNotBuilt>" "$config" || error "$job: should send to slack on 'NotBuilt'"
    grep -q "<notifyUnstable>true</notifyUnstable>" "$config" || error "$job: should send to slack on 'Unstable'"
    grep -q "<notifyFailure>true</notifyFailure>" "$config" || error "$job: should send to slack on 'Failure'"
    grep -q "<room>#" "$config" || error "$job: must pick a slack channel to send to"
    grep -q "</jenkins.plugins.slack.SlackNotifier>" "$config" || error "$job: you must enable slack in the *post-build actions* too"
done

if [ -n "$errors" ]; then
    # We use /bin/echo to get well-defined behavior for treatment of '\n'
    /bin/echo -e "Some of the Jenkins configs are not notifying slack on error: $errors" | \
        env PYTHONPATH="$HOME/alertlib_secret" "$HOME/alertlib/alert.py" \
            --slack "#infrastructure" \
            --severity "error" \
            --summary "Need to fix jenkins slack-notifications"
fi
