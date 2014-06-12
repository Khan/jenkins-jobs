#!/bin/bash -xe

# This script runs a make command after setting up the build
# environment. It is intended to be run by the continuous integration
# server from the root of a workspace where the website code is
# checked out into a subdirectory.
#
# Command-line arguments are passed through to the make command.
#
# Environment variables:
#   WITH_SECRETS - set to 1 to make secrets.py available to Python
#   NO_DEPS - set to 1 to disable running 'make deps'

: ${WITH_SECRETS:=}

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd -P )"

source "${SCRIPT_DIR}/build.lib"

# Run commit build verifications.

ensure_virtualenv
[ -n "$NO_DEPS" ] || ( cd "$WEBSITE_ROOT" && "$MAKE" install_deps )

if [ -n "$WITH_SECRETS" ]; then
    decrypt_secrets_py_and_add_to_pythonpath
fi

# Why not have the caller simply run make themselves? Because we may
# modify the environment, e.g., by adding secrets.py to PYTHONPATH.
( cd "$WEBSITE_ROOT" && "$MAKE" "$@" )
