#!/usr/bin/env python3

"""A script to notify, on slack, when "manual" fastly deploys happen.

All of our fastly services are intended to be deployed via the
fastly-deploy jenkins job.  However, sometimes people deploy a fastly
service manually, by going to the fastly UI, hitting "clone", making
some changes, and hitting "save".  We do not want such changes to
happen, since there is no record of them in source control, and indeed
the change will be overwritten the next time a "proper" deploy
happens, via fastly-deploy.

This script notices when such deploys happen.  It works in concert
with the fastly-deploy job to do its work: fastly-deploy says what
versions it has deployed, and we talk to the fastly API to find out
what versions are live that were *not* deployed by fastly-deploy.
When we see one, we alert in an alerting channel, and also in
#whats-happening, which is our record of all changes that affect our
production system.

This is meant to be run every minute or so, via cron.
"""
import collections
import json
import http.client
import logging
import os
import subprocess
import sys

# jenkins-server has alertlib installed in /usr as part of setup.sh
import alertlib

_FASTLY_HOST = 'api.fastly.com'

ServiceInfo = collections.namedtuple(
    "ServiceInfo",
    ("service_name", "service_id",
     "version", "is_active", "updated_at", "description"),
)

DeploysFileInfo = collections.namedtuple(
    "DeploysFileInfo",
    ("service_id", "version"),
)

# sync-start:fastly-deploys-file jobs/deploy-fastly.groovy
_DATADIR = os.path.expanduser(
    "~jenkins/jobs/deploy/jobs/deploy-fastly/workspace")
_GOOD_DEPLOYS_FILE = os.path.join(_DATADIR, "deployed_versions.txt")
_BAD_DEPLOYS_FILE = os.path.join(_DATADIR, "manually_deployed_versions.txt")


def _parse_deploys_file(f):
    """Return a map from fastly service-id to a set of versions.

    Each input line looks like `luUUdGK4AEAIz1vqRyQ180:123`.
    This would give a return value like `{"luUUdGK4AEAIz1vqRyQ180": {123}}`.
    """
    retval = {}
    for line in f.read().splitlines():
        try:
            parts = line.split(':')
            (service_id, version) = (parts[0], int(parts[1]))
        except Exception:
            logging.warning("Skipping malformed deploys-file line: '%s'", line)
            continue
        retval.setdefault(service_id, set()).add(version)
    return retval


def _create_deploys_file_line(service_info):
    return '%s:%s\n' % (service_info.service_id, service_info.version)
# sync-end:fastly-deploys-file


def get_service_info(api_key):
    """Return a dict from service-name to ServiceInfos of locked versions."""
    conn = http.client.HTTPSConnection(_FASTLY_HOST)
    conn.request("GET", "/service", headers={'Fastly-Key': api_key})
    resp = conn.getresponse()
    body = resp.read()
    if resp.status != 200:
        raise http.client.HTTPException("Error talking to %s: response %s (%s)"
                                        % (_FASTLY_HOST, resp.status, body))
    data = json.loads(body)

    return {
        service['id']: [
            ServiceInfo(service_name=service['name'],
                        service_id=service['id'],
                        version=v['number'],
                        is_active=v['active'],
                        updated_at=v['updated_at'],
                        description=v['comment'])
            for v in service['versions']
            if v['locked']
        ]
        for service in data
    }


def get_deploys_to_warn(service_info,
                        good_deploys_by_service_id, bad_deploys_by_service_id):
    for (service_id, versions) in service_info.items():
        # We want to warn about any deploy that is a) not good, and
        # b) that we haven't already warned about.  The first condition
        # means not in `good_deploys`, the second means not in `bad_deploys`.
        to_ignore = (good_deploys_by_service_id.get(service_id, set()) |
                     bad_deploys_by_service_id.get(service_id, set()))

        # We only start warning for a service the first time we see a
        # "good" deploy (via fastly-deploy.groovy) for that service.
        # That way, when introducing a new service, we don't log
        # for test-versions that were made before it went live.
        first_good_deploy = min(
            good_deploys_by_service_id.get(service_id, {sys.maxsize}))

        retval = []
        for v in versions:
            if v.version not in to_ignore and v.version >= first_good_deploy:
                retval.append(v)
        return retval


def send_to_slack(slack_channel, service_infos_to_warn):
    message = ('*These fastly services were deployed via the fastly UI, '
               'not the deploy-fastly jenkins job.*  Make sure the fastly '
               'yaml files are up to date with these changes!')
    for service_info in service_infos_to_warn:
        message += '\n* `%s`: version %s' % (service_info.service_name,
                                             service_info.version)
    alertlib.Alert(message, severity=logging.INFO).send_to_slack(
        slack_channel,
        sender='fastly',
        icon_emoji=':fastly:',
    )


if __name__ == '__main__':
    import argparse
    dflt = ' (default: %(default)s)'
    parser = argparse.ArgumentParser()
    parser.add_argument('--good-deploys-file', default=_GOOD_DEPLOYS_FILE,
                        help=('File holding deploys made by deploy-fastly'
                              + dflt))
    parser.add_argument('--bad-deploys-file', default=_BAD_DEPLOYS_FILE,
                        help=('File holding deploys we have already warned '
                              'about' + dflt))
    parser.add_argument('--slack-channel', default='#infrastructure-platform',
                        help='Slack channel to notify at' + dflt)
    args = parser.parse_args()

    api_key = subprocess.run(
        ["gcloud", "--project", "khan-academy",
         "secrets", "versions", "access", "latest",
         "--secret", "Fastly_read_only_config_API_token"],
        capture_output=True,
        check=True,
        encoding='utf-8',
    ).stdout

    if os.path.exists(args.good_deploys_file):
        with open(args.good_deploys_file) as f:
            good_deploys_by_service_id = _parse_deploys_file(f)
    else:
        logging.warning("No good-deploys file found at %s",
                        args.good_deploys_file)
        good_deploys_by_service_id = {}

    if os.path.exists(args.bad_deploys_file):
        with open(args.bad_deploys_file) as f:
            bad_deploys_by_service_id = _parse_deploys_file(f)
    else:
        logging.warning("No history file found at %s", args.bad_deploys_file)
        bad_deploys_by_service_id = {}

    service_info = get_service_info(api_key)
    service_infos_to_warn = get_deploys_to_warn(
        service_info, good_deploys_by_service_id, bad_deploys_by_service_id)

    if service_infos_to_warn:
        send_to_slack(args.slack_channel, service_infos_to_warn)
        with open(args.bad_deploys_file, 'a') as f:
            f.writelines(
                _create_deploys_file_line(si) for si in service_infos_to_warn
            )
