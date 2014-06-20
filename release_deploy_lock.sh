#!/bin/bash -xe

# Release the deploy lock using redis.

# Configuration options for acquire_lock.

: ${JENKINS_USER:="unknown-user"}

# The HipChat room notified on lock operations, used by build.lib's alert().
: ${HIPCHAT_ROOM:=HipChat Tests}
: ${HIPCHAT_SENDER:=Mr Gorilla}


SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd -P )"
source "${SCRIPT_DIR}/build.lib"    # for ensure_virtualenv() and alert()
ensure_virtualenv                   # alert() probably relies on this

lock_holder="`redis-cli GET lock:deploy`"
if [ -z "$lock_holder" ]; then
    alert "error" \
        "<b>$JENKINS_USER</b>: Could not release the deploy-lock; " \
        "it's not being held."
    exit 1
fi

redis-cli DEL lock:deploy
