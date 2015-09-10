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
: ${DEPLOY_VERSION:=staging}
if [ "${DEPLOY_VERSION}" = "default" ]; then
    # We unset here to retain the deploy.py semantics that an empty version
    # indicates a default deploy. We jump through some hoops so if the user
    # doesn't set DEPLOY_VERSION at all, we default to deploying to 'staging',
    # rather than doing the deploy.py default of doing a default deploy.
    DEPLOY_VERSION=
fi
# The AppEngine user to deploy as and the file containing the user's password.
: ${DEPLOY_EMAIL:=prod-deploy@khanacademy.org}

# These set various flags when calling deploy.py.  See also:
#    VERSION: which sets --version
#    CLEAN: which may set --no-clean
: ${MODULES:=}          # --modules: if set, a comma-separated list to deploy
: ${SKIP_TESTS:=false}  # --no-tests: set to "true" to append --no-tests
: ${SKIP_I18N:=false}   # --no-i18n: set to "true" to append --no-i18n
: ${FORCE:=false}       # --force: deploy unconditionally
: ${PRIME:=false}       # --force-priming: set to "true" to append
: ${SUBMODULE_REVERTS:=false}  # --allow-submodule-reverts: "true" to append
: ${HIPCHAT_ROOM:=1s and 0s}   # --hipchat-room: "" to disable hipchat sending

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

# Set up the flags we pass to deploy.py
# We always set --no-up: Jenkins checks out the right revision for us.
DEPLOY_FLAGS="--version='$DEPLOY_VERSION'"
DEPLOY_FLAGS="$DEPLOY_FLAGS --no-browser --no-up --clean-versions"
[ -z "$MODULES" ] || DEPLOY_FLAGS="$DEPLOY_FLAGS --modules='$MODULES'"
[ "$SKIP_TESTS" = "false" ] || DEPLOY_FLAGS="$DEPLOY_FLAGS --no-tests"
[ "$SKIP_I18N" = "false" ] || DEPLOY_FLAGS="$DEPLOY_FLAGS --no-i18n"
[ "$FORCE" = "false" ] || DEPLOY_FLAGS="$DEPLOY_FLAGS --force-deploy"
[ "$PRIME" = "false" ] || DEPLOY_FLAGS="$DEPLOY_FLAGS --force-priming"
[ "$SUBMODULE_REVERTS" = "false" ] || DEPLOY_FLAGS="$DEPLOY_FLAGS --allow-submodule-reverts"
DEPLOY_FLAGS="$DEPLOY_FLAGS --hipchat-room='$HIPCHAT_ROOM'"
DEPLOY_FLAGS="$DEPLOY_FLAGS --deployer-username='$DEPLOYER_USERNAME'"

# Clean out the working tree.
case "$CLEAN" in
    all)
        "$MAKE" allclean
        ;;
    most)
        "$MAKE" clean
        # Be a bit more aggressive: delete un-git-added files, for instance.
        git clean -qffdx --exclude genfiles --exclude node_modules
        git submodule foreach git clean -qffdx --exclude node_modules
        ;;
    some)
        git clean -qffdx --exclude genfiles --exclude node_modules
        git submodule foreach git clean -qffdx --exclude node_modules
        # genfiles is excluded from "git clean" so we need to manually
        # remove artifacts that should not be kept across builds.
        rm -rf genfiles/test-reports genfiles/lint_errors.txt
        ;;
    none)
        ;;
    *)
        echo "Unknown value for CLEAN: '$CLEAN'"
        exit 1
esac

# If we have a genfiles-dir to take from, try do to that.
if [ -n "$GENFILES_DIR" -a -d "$GENFILES_DIR" ]; then
    fast_mv_f "$GENFILES_DIR" genfiles ../tmp/genfiles.to-delete
fi

# Run the deploy.
"$MAKE" install_deps

echo "Deploying"

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

# Use eval to properly handle quotes in $DEPLOY_FLAGS
eval python -u deploy/deploy.py $DEPLOY_FLAGS
