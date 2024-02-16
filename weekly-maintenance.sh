#!/bin/bash

# A Jenkins job that periodically runs some cleanup tasks on
# our webapp.
#
# NOTE: The cleanup tasks are defined in weekly-maintenance-jobs.sh.
# This is merely the job-runner.

JOBS_FILE=$(dirname "$0")/weekly-maintenance-jobs.sh

# Introspection, shell-script style!
ALL_JOBS=`grep -o '^[a-zA-Z0-9_]*()' "$JOBS_FILE" | tr -d '()'`

# Let's make sure we didn't define two jobs with the same name.
duplicate_jobs=`echo "$ALL_JOBS" | sort | uniq -d`
if [ -n "$duplicate_jobs" ]; then
    echo "Defined multiple jobs with the same name:"
    echo "$duplicate_jobs"
    exit 1
fi


if [ "$1" = "-l" -o "$1" = "--list" ]; then
    echo "$ALL_JOBS"
    exit 0
elif [ -n "$1" ]; then          # they specified which jobs to run
    jobs_to_run="$@"
else
    jobs_to_run="$ALL_JOBS"
fi


set -x

# Sync the repos we're going to be pushing changes to.
# We change webapp in the 'automated-commits' branch.
jenkins-jobs/safe_git.sh sync_to_origin "git@github.com:Khan/webapp" "automated-commits"
jenkins-jobs/safe_git.sh sync_to_origin "git@github.com:Khan/network-config" "master"

failed_jobs=""
for job in $jobs_to_run; do
    echo "--- Starting $job: `date`"
    bash -e "$JOBS_FILE" $job || failed_jobs="$failed_jobs $job"
    echo "--- Finished $job: `date`"
done


if [ -n "$failed_jobs" ]; then
    echo "THE FOLLOWING JOBS FAILED: $failed_jobs"
    exit 1
else
    echo "All done!"
    exit 0
fi
