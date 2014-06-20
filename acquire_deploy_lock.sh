#!/bin/bash -xe

# Acquires the deploy lock using redis.  If the lock is currently
# held, log (to hipchat) that we're in the queue, and wait for it to
# be released, logging a reminder that we're still waiting every so
# often.
#
# This deploy lock works *in conjunction with* -- and relies on --
# the locking provided by jenkins, that only one deploy-via-multijob
# job can run at a time.  Since this lock is acquired only within
# that job, it means that only one person can be trying to acquire
# the lock at a time.
#
# The reason we need this lock at all, given that jenkins provides
# locking, is that the deploy pipeline is spread over several jobs,
# and jenkins locks only work for a single job.  So after we've
# moved from deploy-via-multijob onto set-default, we still want to
# hold a lock.  In other words, we have these constraints:
#   * Only 1 job can be in deploy-via-multijob at a time
#   * Only 1 job can be in set-default at a time, and likewise with
#     later pipeline steps
#   * When a job is in set-default and finish-deploy, it has this
#     redis lock, so while someone else can start their own
#     deploy-via-multijob job, they'll be stopped by this
#     acquire_lock call until we release the redis lock.
#   * While a job is waiting in deploy-via-multijob (because we're
#     in set-default, or somewhere later), no other job can enter
#     deploy-via-multijob; they're queued up by jenkins.

# Configuration options for acquire_lock.

: ${JENKINS_URL:=http://jenkins.khanacademy.org}

# The HipChat room notified on lock operations, used by build.lib's alert().
: ${HIPCHAT_ROOM:=HipChat Tests}
: ${HIPCHAT_SENDER:=Mr Gorilla}


# Exits this script if we succeed in acquiring the lock.
exit_if_we_can_acquire_lock() {
    lock_info="$username (branch $git_revision)"
    # SETNX returns 1 if the key does not already exists in redis.
    # That means we've acquired the lock.  We set the lock value to
    # our documentation data: git_revision and username.
    if redis-cli SETNX lock:deploy "$lock_info"; then
        alert "info" \
            "<b>$username</b>: Starting deploy! (branch $git_revision)"
        exit 0
    fi
}

[ -n "$2" ] || {
    echo "USAGE: $0 <git_revision> <username>"
    echo "       (The arguments are used to document who holds the lock)"
    exit 1
}
git_revision="$1"
username="$2"

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd -P )"
source "${SCRIPT_DIR}/build.lib"    # for ensure_virtualenv() and alert()
ensure_virtualenv                   # alert() probably relies on this

lock_holder="`redis-cli GET lock:deploy`"
exit_if_we_can_acquire_lock

# OK, we'll have to busy-wait for the lock.  Let the user know what's
# going on.
alert "info" \
    "<b>$username</b>: You're next in line to deploy! (branch $git_revision)" \
    "Currently deploying: $lock_holder"

wait_time=0
while [ $wait_time -lt 60 ]; do        # busy-wait for an hour
    # Busy-wait for 10 minutes
    for i in `seq 60`; do
        sleep 10
        exit_if_we_can_acquire_lock
    done
    wait_time=`expr $wait_time + 10`
    alert "info" \
        "<b>$username</b>: You're still next in line to deploy," \
        "after $lock_holder.  (Waited $wait_time minutes so far.)"
done

unlock_url="${JENKINS_URL}/job/deploy-finish/parambuild?GIT_REVISION=$GIT_REVISION&STATUS=unlock"

alert "error" \
    "<b>$username</b>: $lock_holder has been deploying for over an hour." \
    "Perhaps it's a stray lock?  If you are confident no deploy is currently" \
    "running (check the <a href='$JENKINS_URL'>Jenkins dashboard</a>)," \
    "you can <a href='$unlock_url>manually unlock</a>.  Then re-deploy."
exit 1
