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
: ${DEPLOY_PW_FILE:="$HOME"/prod-deploy.pw}

# These set various flags when calling deploy.py.  See also:
#    VERSION: which sets --version
#    CLEAN: which may set --no-clean
#    NOTIFY_HIPCHAT: which may set --no-hipchat
: ${MODULES:=}         # --modules: if set, a comma-separated list to deploy
: ${SKIP_TESTS:=}      # --no-tests: set to "1" to append --no-tests
: ${SKIP_I18N:=}       # --no-i18n: set to "1" to append --no-i18n
: ${PRIME:=}           # --force-priming: set to "1" to append --force-priming

# This controls how git cleans the working directory before running the build.
# Valid values are:
#   all  - Full clean that results in a pristine working copy.
#   some - Clean the workspaces (including .pyc files) but not genfiles.
#   most - Clean the workspaces and genfiles, excluding js/ruby/python deps.
#   none - Don't clean (this is the default so that devs don't lose work).
: ${CLEAN:=none}

# This controls the HipChat notifications that are sent. Valid values include:
#   long  - Send the normal deploy.py messages (this is the default).
#   short - Send custom messages that are shorter than the verbose deploy.py
#           messages. This is used, e.g., when Jenkins deploys to staging.
#   none  - Suppress sending messages to HipChat.
: ${NOTIFY_HIPCHAT:=long}
# The HipChat room notified on success, failure, and refusal to deploy.
# NOTE: deploy.py notifications ignore this and always go to "1s and 0s".
: ${HIPCHAT_ROOM:=HipChat Tests}
: ${HIPCHAT_SENDER:=Mr Monkey}

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd -P )"
source "${SCRIPT_DIR}/build.lib"
ensure_virtualenv


cd "$WEBSITE_ROOT"

# Set up the flags we pass to deploy.py
# We always set --no-up: Jenkins checks out the right revision for us.
# The '|| true's are to work around the fact we are running bash -e.
DEPLOY_FLAGS="--version=$DEPLOY_VERSION"
DEPLOY_FLAGS="$DEPLOY_FLAGS --no-browser --no-up --clean-versions"
[ -n "$MODULES" ] && DEPLOY_FLAGS="$DEPLOY_FLAGS --modules=$MODULES" || true
[ -n "$SKIP_TESTS" ] && DEPLOY_FLAGS="$DEPLOY_FLAGS --no-tests" || true
[ -n "$SKIP_I18N" ] && DEPLOY_FLAGS="$DEPLOY_FLAGS --no-i18n" || true
[ -n "$PRIME" ] && DEPLOY_FLAGS="$DEPLOY_FLAGS --force-priming" || true
[ "$NOTIFY_HIPCHAT" = "long" ] || DEPLOY_FLAGS="$DEPLOY_FLAGS --no-hipchat"

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

# Run the deploy.
"$MAKE" install_deps

echo "Deploying"

# We need to deploy secrets.py to production, so it needs to be in
# webapp/, not just in $SECRETS_DIR.
decrypt_secrets_py_and_add_to_pythonpath
cp -p "$SECRETS_DIR/secrets.py" .

if ! python -u deploy/deploy.py $DEPLOY_FLAGS \
    --email="$DEPLOY_EMAIL" --passin <"$DEPLOY_PW_FILE"
then
    if [ "$NOTIFY_HIPCHAT" = "short" ]; then
        alert error \
            "Oh no, the $JOB_NAME build is broken (sadpanda). Will a kind" \
            "soul help me get back on my feet at ${BUILD_URL}console?" \
    fi
    exit 1
fi

if [ "$NOTIFY_HIPCHAT" = "short" ]; then
    LAST_COMMIT=`git rev-parse HEAD`
    LAST_COMMIT_SHORT=`git rev-parse --short HEAD`
    LAST_COMMIT_AUTHOR=`git log -n1 --format=format:"%ae"`
    alert info \
        "Just deployed <a href=\"https://github.com/Khan/webapp/commit/$LAST_COMMIT\">$LAST_COMMIT_SHORT</a>" \
        "by $LAST_COMMIT_AUTHOR to http://$DEPLOY_VERSION.khan-academy.appspot.com" \
fi
