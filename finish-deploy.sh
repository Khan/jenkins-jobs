#!/bin/bash -xe

# Handles the end-case of the deploy pipeline.  Its main job is to
# release the deploy lock, but it also handles the rollback that
# may happen if the build failed.

# Configuration options for finish_deploy.

# If status is 'rollback', this is the gae version-name to roll back to.
: ${ROLLBACK_TO:=}

# The root of jenkins, used for hipchat messaging.
: ${JENKINS_URL:=http://jenkins.khanacademy.org}
: ${JENKINS_USER:="unknown-user"}
# Can just be passed along down the pipeline, doesn't really matter
# at this point, since we're done with the building steps.
: ${GIT_REVISION:=master}

# The AppEngine user to deploy as and the file containing the user's password.
: ${DEPLOY_EMAIL:=prod-deploy@khanacademy.org}
: ${DEPLOY_PW_FILE:="$HOME"/prod-deploy.pw}

# The HipChat room notified on success and failure.
: ${HIPCHAT_ROOM:=HipChat Tests}
: ${HIPCHAT_SENDER:=Mr Gorilla}


[ -n "$1" ] || {
    echo "USAGE: $0 <status of the deploy>"
    echo "Status is one of:"
    echo "   * unlock: Something went wrong somewhere and the lock is still"
    echo "             held even though no deploy is currently in progress."
    echo "   * success: The deploy succeeded and is stable."
    echo "   * failure: The deploy failed before deploying to appengine."
    echo "   * rollback: The deploy failed after deploying to appengine"
    echo "               (e.g. during monitoring)."
    exit 1
}
STATUS="$1"

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd -P )"
source "${SCRIPT_DIR}/build.lib"    # for ensure_virtualenv() and alert()
ensure_virtualenv

# The curl gives back '{"version_id": "..."}'
VERSION_NAME=`curl http://www.khanacademy.org/api/v1/dev/version | cut -d'"' -f4`
[ -n "$VERSION_NAME" ]     # sanity check we have a real version

finish_base="${JENKINS_URL}/job/deploy-finish/parambuild?GIT_REVISION=$GIT_REVISION"

cd "$WEBSITE_ROOT"

if [ status = "rollback" -a "$VERSION_NAME" != "$ROLLBACK_TO" ]; then
    # We don't need to roll back; we never actually rolled forward.
    status="failure"
fi

if [ status = "unlock" ]; then
    alert info "Manually unlocking deploy lock.  Triggered by $JENKINS_USER."

elif [ status = "success" ]; then
    alert info "(gangnamstyle) Deploy of $VERSION_NAME succeeded!" \
               "Time for a happy dance, $JENKINS_USER."

elif [ status = "failure" ]; then
    alert error "(pokerface) Deploy of $VERSION_NAME failed." \
                "I'm sorry, $JENKINS_USER."

elif [ status = "rollback" ]; then
    alert warning "Automatically rolling the default back to $ROLLBACK_TO" \
                  "and deleting $VERSION_NAME from appengine" \
                  "($JENKINS_USER)"
    deploy/set_default.py "$ROLLBACK_TO" --no-priming \
            --email="$DEPLOY_EMAIL" --passin <"$DEPLOY_PW_FILE" || {
        alert critical \
            "(sadpanda) (sadpanda) Auto-rollback failed!" \
            "<b>$(JENKINS_USER)</b>: Roll back to $ROLLBACK_TO manually, then" \
            "<a href='$finish_base&STATUS=failure'>release the deploy lock</a>."
        exit 1
    }
    tools/delete_gae_version.py "$VERSION_NAME" \
            --email="$DEPLOY_EMAIL" --passin <"$DEPLOY_PW_FILE" || {
        alert warning "(sadpanda) (sadpanda) Appengine-delete failed!" \
                      "<b>$(JENKINS_USER)</b>:" \
                      "Delete $VERSION_NAME manually at your convenience."
    }

else
    echo "FATAL ERROR: unknown status $STATUS"
    exit 1

fi

${SCRIPT_DIR}/release_deploy_lock.sh
