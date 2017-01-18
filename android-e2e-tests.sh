#!/bin/bash -xe

# This script runs end-to-end tests for android integration.  It is
# intended to be run by the continuous integration server from the
# root of a workspace where the website code is checked out into a
# subdirectory.

# The URL to run the android integration tests against: that is,
# the url where the android app sends its API requests).
: ${URL:=https://www.khanacademy.org}
# "" to disable slack notifications.
: ${SLACK_CHANNEL:=#1s-and-0s-deploys}
# The jenkins build-url, used for debugging messages
: ${BUILD_URL:=""}
: ${WORKSPACE_ROOT:="`pwd`"}
: ${WEBSITE_ROOT:="$WORKSPACE_ROOT/webapp"}


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

# Always exit with success, but log a message if there were monitoring
# failures; our postbuild actions will handle marking the build as
# unstable, which will cause the user or upstream job to know it's not all
# smiles, but not fail it and cause a rollback. If we exited with
# failure here, it would be difficult to downgrade that.
# TODO(csilvers): is this still true?  Seems kinda unlikely anymore.

# run_android_db_generator.sh assumes it's running from workspace-root.
cd "$WORKSPACE_ROOT"

if env URL="$URL" jenkins-tools/run_android_db_generator.sh; then
    alert_slack "Mobile integration tests succeeded" "info"
else
    echo "run_android_db_generator.sh exited with failure"
    msg="Mobile integration tests failed"
    if [ -n "$BUILD_URL" ]; then
        msg="$msg (search for 'ANDROID' in ${BUILD_URL}consoleFull)"
    fi
    alert_slack "$msg" "error"
    alert_slack "$msg" "error" "mobile-1s-and-0s"
fi
