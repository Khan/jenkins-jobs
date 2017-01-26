#!/bin/bash -xe

# This script runs end-to-end tests for webapp.  It is intended to be
# run by the continuous integration server from the root of a
# workspace where the website code is checked out into a subdirectory.

# The URL to run the e2e tests against.
: ${URL:=https://www.khanacademy.org}
# What selenium driver to use.  `sauce` is the 3rd-party platform we use.
# BACKUP_DRIVER is used as per rune2etests.py; see there for details.
: ${SELENIUM_DRIVER:=sauce}
: ${BACKUP_DRIVER:=}
# How many parallel jobs to run.  10 is how many sauce instances we have.
: ${JOBS:=10}
# "" to disable slack notifications.
: ${SLACK_CHANNEL:=#1s-and-0s-deploys}
# The jenkins build-url, used for debugging messages
: ${BUILD_URL:=""}
: ${WORKSPACE_ROOT:="`pwd`"}
: ${WEBSITE_ROOT:="$WORKSPACE_ROOT/webapp"}

[ -z "$BACKUP_DRIVER" ] || BACKUP_DRIVER_FLAG="--backup-driver $BACKUP_DRIVER"

# This is apparently needed to avoid hanging with the chrome driver.
# See https://github.com/SeleniumHQ/docker-selenium/issues/87
export DBUS_SESSION_BUS_ADDRESS=/dev/null


# Send a message to slack.
# $1: the message to send
# $2: the severity level to use (warning, error, etc.).
#     See alert.py for more details.
# $3: slack channel to send the message to. Defaults to $SLACK_CHANNEL.
alert_slack() {
    echo "$1" \
        | "$WORKSPACE_ROOT"/env/bin/python \
          "$WORKSPACE_ROOT"/jenkins-tools/alertlib/alert.py \
          --slack "${3:-$SLACK_CHANNEL}" --severity "$2" \
          --chat-sender "Testing Turtle" --icon-emoji ":turtle:"
}

cd "$WEBSITE_ROOT"

if tools/rune2etests.py \
     --quiet --xml --url "$URL" --jobs "$JOBS" --retries 3 \
     --driver "$SELENIUM_DRIVER" $BACKUP_DRIVER_FLAG
then
    rc=0
else
    echo "selenium tests exited with failure!"
    alert_slack "selenium tests failed: ${BUILD_URL}consoleFull" \
                "error" "better-end-to-end"
    rc=1
fi

# analyze make output will alert slack if there's an error.  It will
# also return a non-zero exit code *if* the error was one that ended
# up being reported in the xml files.  For other types of errors
# -- such as timeouts -- we depend on the setting of `rc=1` above.
"$WORKSPACE_ROOT"/jenkins-tools/analyze_make_output.py \
    --test_reports_dir="$WEBSITE_ROOT"/genfiles/selenium_test_reports/ \
    --jenkins_build_url="$BUILD_URL" \
    --slack-channel="$SLACK_CHANNEL"

exit $rc
