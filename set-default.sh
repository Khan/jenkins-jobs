#!/bin/bash -xe

# This script calls set_default.py to make a specified deployed
# version live.

[ -n "$1" ] || {
    echo "USAGE: $0 <appengine version name to set as default>"
    exit 1
}
VERSION_NAME="$1"

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd -P )"

source "${SCRIPT_DIR}/build.lib"

# Configuration options for set_default.

# How long to do monitoring for after the deploy, in minutes.  0 suppresses.
: ${MONITORING_TIME:=10}
# Do we auto-roll-back if the monitoring detects a problem?  'true' or 'false'
: ${AUTO_ROLLBACK:=false}

# The AppEngine user to deploy as and the file containing the user's password.
: ${DEPLOY_EMAIL:=prod-deploy@khanacademy.org}
: ${DEPLOY_PW_FILE:="$HOME"/prod-deploy.pw}

# The HipChat room notified on success and failure.
: ${HIPCHAT_ROOM:=HipChat Tests}
: ${HIPCHAT_SENDER:=Mr Gorilla}


# $1: severity level; $2+: message
alert() {
    severity="$1"
    shift
    echo "$@" \
        | third_party/alertlib-khansrc/alert.py --severity="$severity" \
              --hipchat "$HIPCHAT_ROOM" --hipchat-sender "$HIPCHAT_SENDER" \
              --logs
}

ensure_virtualenv
decrypt_secrets_py_and_add_to_pythonpath    # for sending to hipchat

# The curl gives back '{"version_id": "..."}'
old_default=`curl http://www.khanacademy.org/api/v1/dev/version | cut -d'"' -f4`
[ -n "$old_default" ]     # sanity check we have a real version

echo "Changing default from $old_default to $VERSION_NAME"

cd "$WEBSITE_ROOT"
# We need secrets.py to be on pythonpath for alert.py to work.
export PYTHONPATH=.:${PYTHONPATH}

set +e
deploy/set_default.py "$VERSION_NAME" \
    -m "$MONITORING_TIME" --monitor-error-is-fatal \
    --email="$DEPLOY_EMAIL" --passin <"$DEPLOY_PW_FILE"
rc=$?
set -e

# set_default returns 2 if monitoring detected a problem.
if [ rc -eq 0 ]; then
    alert warning "Set default: $old_version -> $VERSION_NAME"
elif [ rc -eq 2 ]; then
    if [ "$AUTO_ROLLBACK" = "true" ]; then
        alert warning "(sadpanda) set_default monitoring detected problems!" \
                      "Automatically rolling the default back to $old_default."
        deploy/set_default.py "$old_version" --no-priming \
                --email="$DEPLOY_EMAIL" --passin <"$DEPLOY_PW_FILE" || {
            alert critical "Auto-rollback failed!  Roll back manually."
            exit 1
        }
    else
        alert warning "(sadpanda) set_default monitoring detected problems!" \
                      "Make sure everything is ok!"
    fi
else
    echo "FATAL ERROR: set_default failed."
    exit $rc
fi
