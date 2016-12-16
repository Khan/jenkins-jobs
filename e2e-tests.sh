#!/bin/bash -xe

# This script runs end-to-end tests. It is intended to be
# run by the continuous integration server from the root of a workspace
# where the website code is checked out into a subdirectory.

# There are 3 types of tests currently being run:
# - webpage-loading tests, using phantomjs (DEPRECATED)
# - webpage-loading tests, using selenium (replacing phantomjs tests).
# - mobile integration tests against the topic tree endpoint (https://github.com/Khan/android/blob/master/db-generator/src/main/java/org/khanacademy/dbgenerator/Main.java).

# Settings:

# The URL to run the e2e tests against.
: ${URL:=https://www.khanacademy.org}
# Whether to run a11y tests.
: ${RUN_A11Y:=true}
# Send an extra message to alert.py in the case of an error.
: ${EXTRA_TEXT_ON_FAILURE:=""}
# "" to disable slack notifications.
: ${SLACK_CHANNEL:=#1s-and-0s-deploys}
# The jenkins build-url, used for debugging messages
: ${BUILD_URL:=""}
: ${WORKSPACE_ROOT:="`pwd`"}
: ${WEBSITE_ROOT:="$WORKSPACE_ROOT/webapp"}

# Send a message to slack.
# $1: the message to send
# $2: the severity level to use (warning, error, etc.). See alert.py for more details.
# $3: slack channel to send the message to. Defaults to $SLACK_CHANNEL.
alert_slack() {
    echo "$1" \
        | env/bin/python jenkins-tools/alertlib/alert.py \
          --slack "${3:-$SLACK_CHANNEL}" --severity "$2" \
          --chat-sender "Testing Turtle" --icon-emoji ":turtle:"
}

# Always exit with success, but log a message if there were monitoring
# failures; our postbuild actions will handle marking the build as
# unstable, which will cause the user or upstream job to know it's not all
# smiles, but not fail it and cause a rollback. If we exited with
# failure here, it would be difficult to downgrade that.

run_android_e2e_tests() {
    if env URL="$URL" "$WORKSPACE_ROOT"/jenkins-tools/run_android_db_generator.sh; then
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
}

run_website_e2e_tests() {
    (
        cd "$WEBSITE_ROOT"
        find end-to-end -name '*_e2etest.js'  \
          | if [ "$RUN_A11Y" = "false" ]; then grep -v a11y; else cat; fi \
          | xargs tools/end_to_end_webapp_testing.py --url "$URL" --no-colors --jobs=4 --engine=phantomjs \
                || echo "end_to_end_webapp_testing.py exited with failure"

        if ! "$WORKSPACE_ROOT"/jenkins-tools/analyze_make_output.py \
            --e2e_test_reports_file="$WEBSITE_ROOT"/genfiles/end_to_end_test_output.xml \
            --jenkins_build_url="$BUILD_URL" \
            --slack-channel="$SLACK_CHANNEL"
        then
            echo "analyze_make_output.py exited with failure"
            if [ ! -z "$EXTRA_TEXT_ON_FAILURE" ]; then
                alert_slack "$EXTRA_TEXT_ON_FAILURE" "warning"
            fi
        fi
    )
}
run_selenium_e2e_tests() {
    (
        # pwd needs to be set to webapp for selenium_webapp_testing to be set
        if ! (cd $WEBSITE_ROOT;
              "$WEBSITE_ROOT/tools/rune2etests.py" \
              --quiet --xml \
              --url "$URL" --driver sauce --jobs 10 --retries 3 )
        then
            echo "selenium tests exited with failure!"
            alert_slack "selenium tests failed: ${BUILD_URL}consoleFull" \
                        "error" "better-end-to-end"
        fi

        # analyze make output will alert slack if there's an error
        "$WORKSPACE_ROOT"/jenkins-tools/analyze_make_output.py \
            --test_reports_dir="$WEBSITE_ROOT"/genfiles/selenium_test_reports/\
            --jenkins_build_url="$BUILD_URL" \
            --slack-channel="$SLACK_CHANNEL"
    )
}


# We can run all these in parallel!  We move all output to stderr so
# we don't have to worry about the output lines getting intermingled.
run_website_e2e_tests 1>&2 &
run_android_e2e_tests 1>&2 &
run_selenium_e2e_tests 1>&2 &

wait
