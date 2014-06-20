#!/bin/bash -xe

# This script calls set_default.py to make a specified deployed
# version live.

[ -n "$1" ] || {
    echo "USAGE: $0 <appengine version name to set as default>"
    exit 1
}
VERSION_NAME="$1"

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd -P )"

# Configuration options for set_default.

# How long to do monitoring for after the deploy, in minutes.  0 suppresses.
: ${MONITORING_TIME:=10}
# Do we auto-roll-back if the monitoring detects a problem?  'true' or 'false'
: ${AUTO_ROLLBACK:=false}

# The root of jenkins, used for hipchat messaging.
: ${JENKINS_URL:=http://jenkins.khanacademy.org}
: ${JENKINS_USER:="unknown-user"}
# Can just be passed along down the pipeline, doesn't really matter
# at this point, since we're done with the building steps.
: ${GIT_REVISION:=master}

# The AppEngine user to deploy as and the file containing the user's password.
: ${DEPLOY_EMAIL:=prod-deploy@khanacademy.org}
: ${DEPLOY_PW_FILE:="$HOME"/prod-deploy.pw}

# Used by build.lib's alert().
: ${HIPCHAT_ROOM:=HipChat Tests}
: ${HIPCHAT_SENDER:=Mr Gorilla}


source "${SCRIPT_DIR}/build.lib"    # for ensure_virtualenv() and alert()
ensure_virtualenv

# The curl gives back '{"version_id": "..."}'
old_default=`curl http://www.khanacademy.org/api/v1/dev/version | cut -d'"' -f4`
[ -n "$old_default" ]     # sanity check we have a real version

echo "Changing default from $old_default to $VERSION_NAME"

cd "$WEBSITE_ROOT"

set +e
deploy/set_default.py "$VERSION_NAME" \
    -m "$MONITORING_TIME" --monitor-error-is-fatal \
    --email="$DEPLOY_EMAIL" --passin <"$DEPLOY_PW_FILE"
rc=$?
set -e

# The links to click on, for the next manual step, either indicating
# the deploy is ok or the deploy is not ok.
finish_base="${JENKINS_URL}/job/deploy-finish/parambuild?GIT_REVISION=$GIT_REVISION"

# set_default returns 2 if monitoring detected a problem.
if [ rc -eq 0 ]; then
    alert warning \
        "Default set: $old_default -> $VERSION_NAME ($JENKINS_USER)." \
        "<b>$(JENKINS_USER)</b>: Monitoring passed, but you should" \
        "<a href='https://www.khanacademy.org'>double-check</a>" \
        "everything is ok, then click one of these:" \
        "<a href='$finish_base&STATUS=success'>OK! Release the deploy lock.</a> ~ " \
        "<a href='$finish_base&STATUS=rollback&ROLLBACK_TO=$old_default'>TROUBLE! Roll back.</a>"
    exit 0
fi

if [ rc -eq 2 ]; then
    if [ "$AUTO_ROLLBACK" = "true" ]; then
        alert warning "(sadpanda) set_default monitoring detected problems!"
        env ROLLBACK_TO="$old_default" "${SCRIPT_DIR}/finish_deploy.py" rollback
        exit 2
    else
        alert warning \
            "(sadpanda) set_default monitoring detected problems!" \
            "<b>$(JENKINS_USER)</b>: make sure everything is ok, then click one of these:" \
            "<a href='$finish_base&STATUS=success'>OK! Release the deploy lock.</a> ~ " \
            "<a href='$finish_base&STATUS=rollback&ROLLBACK_TO=$old_default'>TROUBLE! Roll back.</a>"
        exit 0
    fi
fi

alert critical \
    "(sadpanda) (sadpanda) set-default failed!" \
    "<b>$(JENKINS_USER)</b>: Either" \
    "1) set the default to $VERSION_NAME manually, then" \
    "<a href='$finish_base&STATUS=success'>release the deploy lock</a>;" \
    "or 2) <a href='$finish_base&STATUS=failure'>just abort the deploy</a>."
exit $rc
