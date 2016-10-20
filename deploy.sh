#!/bin/bash -xe

# This script runs a deployment of the website.  It is intended to be
# run by the continuous integration server from the root of a workspace
# where the website code is checked out into a subdirectory.
#
# It depends on having access to the credentials required to access
# secrets.py and to deploy to App Engine, see the configuration options
# below.

# Configuration options for deployment.

# The AppEngine version name for this deployment. The special string "default"
# indicates a default deploy and an auto-generated version is used.
# If DEPLOY_STATIC is true and DEPLOY_DYNAMIC is false, then DEPLOY_VERSION
# indicates the static version to deploy; in that case, the related dynamic
# version is the currently live version.
: ${DEPLOY_VERSION:=staging}
if [ "${DEPLOY_VERSION}" = "default" ]; then
    # We unset here to retain the deploy_to_gae.py semantics that an
    # empty version indicates a default deploy.
    DEPLOY_VERSION=
fi

# These control whether we call deploy_to_gae, deploy_to_gcs, or both.
: ${DEPLOY_DYNAMIC:=true}
: ${DEPLOY_STATIC:=true}

# These set various flags when calling deploy_to_gae.py and
# deploy_to_gcs.py.  See also:
#    DEPLOY_VERSION: which sets --version
: ${MODULES:=}              # --modules: if set, a comma-separated list to deploy
: ${SKIP_I18N:=false}       # --no-i18n: set to "true" to append --no-i18n
: ${FORCE:=false}           # --force: deploy unconditionally
: ${SKIP_PRIMING:=false}    # --no-priming: set to "true" to append
: ${SUBMODULE_REVERTS:=false}  # --allow-submodule-reverts: "true" to append
: ${SLACK_CHANNEL:=#1s-and-0s-deploys}  # --slack-channel: "" to disable slack
: ${DEPLOYER_USERNAME:=}    # --deployer-username: @user who is deploying

# If set, we look for this directory, and if it exists use it as our
# genfiles directory before deploying (we do this by mv-ing it).  This
# is used when we want to split up the work of the deploy process,
# having someone else do the building of genfiles (or at least most of
# it) for us.  Make sure the owner of this dir is ok with us mv-ing it!
: ${GENFILES_DIR:=}

# This controls how git cleans the working directory before running the build.
# Valid values are:
#   all  - Full clean that results in a pristine working copy.
#   some - Clean the workspaces (including .pyc files) but not genfiles.
#   most - Clean the workspaces and genfiles, excluding js/ruby/python deps.
#   none - Don't clean (this is the default so that devs don't lose work).
: ${CLEAN:=none}

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd -P )"
source "${SCRIPT_DIR}/build.lib"
ensure_virtualenv


cd "$WEBSITE_ROOT"

# Set up the flags we pass to deploy_to_gae.py.
if [ "$DEPLOY_DYNAMIC" = "true" ]; then
    GAE_DEPLOY_FLAGS="--version='$DEPLOY_VERSION'"
    GAE_DEPLOY_FLAGS="$GAE_DEPLOY_FLAGS --no-browser --no-up --clean-versions"
    [ -z "$MODULES" ] || GAE_DEPLOY_FLAGS="$GAE_DEPLOY_FLAGS --modules='$MODULES'"
    [ "$SKIP_I18N" = "false" ] || GAE_DEPLOY_FLAGS="$GAE_DEPLOY_FLAGS --no-i18n"
    [ "$FORCE" = "false" ] || GAE_DEPLOY_FLAGS="$GAE_DEPLOY_FLAGS --force-deploy"
    [ "$SKIP_PRIMING" = "false" ] || GAE_DEPLOY_FLAGS="$GAE_DEPLOY_FLAGS --no-priming"
    [ "$SUBMODULE_REVERTS" = "false" ] || GAE_DEPLOY_FLAGS="$GAE_DEPLOY_FLAGS --allow-submodule-reverts"
    GAE_DEPLOY_FLAGS="$GAE_DEPLOY_FLAGS --slack-channel='$SLACK_CHANNEL'"
    GAE_DEPLOY_FLAGS="$GAE_DEPLOY_FLAGS --deployer-username='$DEPLOYER_USERNAME'"
fi

# Also set up the flags we pass to deploy_to_gcs.py.  If
# DEPLOY_DYNAMIC is true and DEPLOY_STATIC is false, we still call
# deploy_to_gcs, but with an arg saying "just copy the static-manifest
# to our new dynamic gae version."
GCS_DEPLOY_FLAGS=""
if [ "$DEPLOY_STATIC" = "true" ]; then
    [ "$SKIP_I18N" = "false" ] || GCS_DEPLOY_FLAGS="$GCS_DEPLOY_FLAGS --no-i18n"
    # We only tell deploy_to_gcs to message slack if deploy_to_gae won't be.
    if [ "$DEPLOY_DYNAMIC" = "false" ]; then
        GCS_DEPLOY_FLAGS="$GCS_DEPLOY_FLAGS --slack-channel='$SLACK_CHANNEL'"
        GCS_DEPLOY_FLAGS="$GCS_DEPLOY_FLAGS --deployer-username='$DEPLOYER_USERNAME'"
    fi
else
    GCS_DEPLOY_FLAGS="$GCS_DEPLOY_FLAGS --copy-from=default"
fi
# Here we can't use an empty-string version name, so for default
# deploys we need to ask `make` what the version name will be.
GCS_DEPLOY_FLAGS="$GCS_DEPLOY_FLAGS ${DEPLOY_VERSION-`make gae_version_name`}"

# Clean out the working tree.
clean "$CLEAN"         # in build.lib

# If we have a genfiles-dir to take from, try do to that.
if [ -n "$GENFILES_DIR" -a -d "$GENFILES_DIR" ]; then
    fast_mv_f "$GENFILES_DIR" genfiles ../tmp/genfiles.to-delete
fi

# Run the deploy.
"$MAKE" install_deps

echo "Deploying to: `[ "$DEPLOY_DYNAMIC" = "true" ] && echo "gae "``[ "$DEPLOY_STATIC" = "true" ] && echo "gcs "`"

# We need to deploy secrets.py to production, so it needs to be in
# webapp/, not just in $SECRETS_DIR.
decrypt_secrets_py_and_add_to_pythonpath
cp -p "$SECRETS_DIR/secrets.py" .

# Increase the the maximum number of open file descriptors.
# This avoids failures that look like this:
#
#   [Errno 24] Too many open files:
#
# This is necessary because kake keeps a lockfile open for every file it's
# compiling, and that can easily be thousands of files.
#
# 4096 appears to be the maximum value linux allows.
ulimit -S -n 4096

# TODO(csilvers): remove this compatibility-mode `if` after D31478 is landed.
if [ -s deploy/deploy.py ]; then
    eval python -u deploy/deploy.py $GAE_DEPLOY_FLAGS
    exit 0
fi

# We can deploy both at the same time.  We use eval to properly handle
# quotes in $DEPLOY_FLAGS.
if [ -n "$GAE_DEPLOY_FLAGS" ]; then
    eval python -u deploy/deploy_to_gae.py $GAE_DEPLOY_FLAGS &
    gae_pid=$!
fi
if [ -n "$GCS_DEPLOY_FLAGS" ]; then
    eval python -u deploy/deploy_to_gcs.py $GCS_DEPLOY_FLAGS &
    gcs_pid=$!
fi

# We can't just call the no-arg version of `wait` because it always
# returns rc 0.  By doing it this way, we return the rc of each
# deploy_to_*.py script, and if it's an error this script will fail
# (due to the `-e` on the shebang line).  We wait for gcs first
# because gae is the one that writes to slack, and we don't want to
# do that until everything is done.
if [ -n "$gcs_pid" ]; then
    if ! wait "$gcs_pid"; then
        echo "DEPLOY TO GCS FAILED"
        # NO need to wait for GAE-deploy once the GCS-deploy has failed.
        [ -z "$gae_pid" ] || kill "$gae_pid"
        exit 1
    fi
fi
if [ -n "$gae_pid" ]; then
    if ! wait "$gae_pid"; then
        echo "DEPLOY TO APPENGINE (GAE) FAILED"
        exit 1
    fi
fi
