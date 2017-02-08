#!/usr/bin/env python

"""Run a task, but die if it goes too long without giving output.

The idea is that if you have a task that is supposed to emit output
regularly, and it doesn't, then it's probably hung and you should kill
it.

Arguments are similar to timeout(1).
"""

import errno
import os
import select
import signal
import subprocess
import sys
import time


def _parse_duration(duration):
    """Convert a string duration to a time in seconds."""
    if duration.endswith('s'):
        return float(duration[:-1])
    elif duration.endswith('m'):
        return float(duration[:-1]) * 60
    elif duration.endswith('h'):
        return float(duration[:-1]) * 60 * 60
    elif duration.endswith('d'):
        return float(duration[:-1]) * 60 * 60 * 24
    else:
        return float(duration)


def _parse_signal(signame):
    """Convert a possibly-named signal to a signal number."""
    try:
        return int(signame)
    except ValueError:
        symbol = 'SIG' + signame
        return getattr(signal, symbol)


def do_timeout(p, signum, kill_after):
    p.send_signal(signum)
    if kill_after:
        sleep_left = kill_after
        while sleep_left > 0:
            time.sleep(0.1)
            if p.poll() is not None:
                break
            sleep_left -= 0.1
        else:
            p.send_signal(signal.SIGKILL)
    return 124        # what `signal(1)` returns on timeout


def main(cmd_list, duration, signum, kill_after):
    input = ''

    p = subprocess.Popen(cmd_list, stdin=subprocess.PIPE,
                         stdout=subprocess.PIPE, stderr=subprocess.PIPE)

    read_set = [p.stdout, p.stderr, sys.stdin]
    write_set = []

    input_offset = 0
    last_stdout_time = time.time()
    while p.stdout in read_set or p.stderr in read_set or p.stdin in write_set:
        timeout = duration - (time.time() - last_stdout_time)
        if timeout <= 0:
            # This means there's been no output in at least `duration`
            # seconds so we have timed out!
            return do_timeout(p, signum, kill_after)

        try:
            rlist, wlist, xlist = select.select(read_set, write_set, [],
                                                timeout)
        except select.error, e:
            if e.args[0] == errno.EINTR:
                continue
            raise

        if sys.stdin in rlist:
            data = os.read(sys.stdin.fileno(), 1024)
            if data == "":
                read_set.remove(sys.stdin)
            else:
                if not p.stdin.closed and p.stdin not in write_set:
                    input += data
                    write_set.append(p.stdin)
                continue

        if p.stdin in wlist:
            chunk = input[input_offset:(input_offset + select.PIPE_BUF)]
            try:
                bytes_written = os.write(p.stdin.fileno(), chunk)
            except OSError as e:
                if e.errno == errno.EPIPE:
                    p.stdin.close()
                    write_set.remove(p.stdin)
                else:
                    raise
            else:
                input_offset += bytes_written
                if input_offset >= len(input) and sys.stdin not in read_set:
                    p.stdin.close()
                    write_set.remove(p.stdin)

        if p.stdout in rlist:
            data = os.read(p.stdout.fileno(), 1024)
            if data == "":
                p.stdout.close()
                read_set.remove(p.stdout)
            sys.stdout.write(data)
            sys.stdout.flush()
            last_stdout_time = time.time()

        if p.stderr in rlist:
            data = os.read(p.stderr.fileno(), 1024)
            if data == "":
                p.stderr.close()
                read_set.remove(p.stderr)
            sys.stderr.write(data)
            sys.stderr.flush()
            last_stdout_time = time.time()

    return p.wait()


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument('-k', '--kill-after', metavar='DURATION',
                        help=('Also send a KILL signal if COMMAND is still '
                              'running this long after the initial signal '
                              'was sent.'))
    parser.add_argument('-s', '--signal', default='TERM',
                        help=('Specify the signal to be sent on timeout. '
                              'SIGNAL may be a name like "HUP" or a number. '
                              'See `kill -l` for a list of signals.'))
    parser.add_argument('duration',
                        help=('A floating point number with an optional '
                              'suffix: "s" for seconds (the default), '
                              '"m" for minutes, "h" for hours, or "d" '
                              'for days'))
    parser.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args()

    try:
        duration = _parse_duration(args.duration)
    except ValueError:
        parser.error('Invalid duration "%s"' % args.duration)

    if args.kill_after:
        try:
            kill_after = _parse_duration(args.kill_after)
        except ValueError:
            parser.error('Invalid kill-after duration "%s"' % args.kill_after)
    else:
        kill_after = None

    try:
        signum = _parse_signal(args.signal)
    except AttributeError:
        parser.error('Unknown signal "%s" (do not include leading "SIG"!)'
                     % args.signal)

    rc = main(args.command, duration, signum, kill_after)
    sys.exit(rc)
