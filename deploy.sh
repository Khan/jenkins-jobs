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
# The file containing the password to decrypt secrets.py.
: ${SECRETS_PW_FILE:="$HOME"/secrets_py/secrets.py.cast5.password}

# Set this to a non-empty value (e.g., 1) to prevent deploy.py from running
# unit tests. It is the same as passing the "--no-tests" flag to deploy.py.
: ${SKIP_TESTS:=}

# This controls how git cleans the working directory before running the build.
# Valid values are:
#   all  - Full clean that results in a pristine working copy.
#   most - Reserve some generated files that deploy.py uses as a cache.
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

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd -P )"
source "${SCRIPT_DIR}/build.lib"
ensure_virtualenv

# Clean out the working tree.

pushd "$WEBSITE_ROOT"
if [ "$CLEAN" = "all" ]; then
    # We need to specify -f twice so deleted subrepos (things that
    # used to be subrepos but aren't anymore) are deleted.  From the
    # 'git clean' man page:
    #    If an untracked directory is managed by a different git
    #    repository, it is not removed by default...  Specify -f
    #    twice to remove it.
    git clean -qffdx
    git submodule foreach git clean -qffdx
elif [ "$CLEAN" = "most" ]; then
    git clean -qffdx --exclude genfiles --exclude node_modules
    # genfiles is excluded from "git clean" so we need to manually remove
    # artifacts that should not be kept across builds.
    rm -rf genfiles/test-reports genfiles/lint_errors.txt
    git submodule foreach git clean -qfdx --exclude node_modules
fi
popd

# Run the deploy.

( cd "$WEBSITE_ROOT" && "$MAKE" install_deps )

echo "Deploying"
# We always set --no-up: Jenkins checks out the right revision for us.
DEPLOY_FLAGS="--no-browser --no-up --clean-versions"
if test -n "${SKIP_TESTS}"; then
    DEPLOY_FLAGS+=" --no-tests"
fi
if [ "${NOTIFY_HIPCHAT}" != "long" ]; then
    DEPLOY_FLAGS+=" --no-hipchat"
fi
pushd "$WEBSITE_ROOT"
openssl cast5-cbc -d -in secrets.py.cast5 -out secrets.py -kfile "$SECRETS_PW_FILE"
chmod 600 secrets.py
if ! python -u deploy/deploy.py $DEPLOY_FLAGS \
    --version="$DEPLOY_VERSION" --email="$DEPLOY_EMAIL" --passin <"$DEPLOY_PW_FILE"
then
    if [ "${NOTIFY_HIPCHAT}" = "short" ]; then
        echo "Oh no, the ${JOB_NAME} build is broken (sadpanda). Will a kind" \
             "soul help me get back on my feet at ${BUILD_URL}console?" \
             | tools/hipchat-cli/hipchat_room_message -x -r "$HIPCHAT_ROOM" -c red
    fi
    exit 1
fi

LAST_COMMIT_SHORT=`git rev-parse --short HEAD`
LAST_COMMIT_AUTHOR=`git log -n1 --format=format:"%ae"`
if [ "${NOTIFY_HIPCHAT}" = "short" ]; then
    echo "Just deployed <a href=\"https://khanacademy.kilnhg.com/Code/Website/Group/webapp/History/${LAST_COMMIT_SHORT}\">${LAST_COMMIT_SHORT}</a>" \
         "by ${LAST_COMMIT_AUTHOR} to http://${DEPLOY_VERSION}.khan-academy.appspot.com" \
         | tools/hipchat-cli/hipchat_room_message -r "$HIPCHAT_ROOM" -c gray
fi
popd
