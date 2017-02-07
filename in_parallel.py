#!/usr/bin/python

"""Run a given command multiple times in parallel.

The input to in_parallel is a command to run, and a list of arguments
to give to the command (each command should take at most one
argument).  We then run the command several times in parallel, passing
a different argument to each parallel invocation.  The way we pass the
argument is to just append it to the command to run.

The return value of in_parallel.py is 0 if all commands succeeded, or
1 otherwise.
"""

import subprocess
import sys
import time


_USAGE = ("USAGE: %s <arg1> [arg2] ... -- command [cmd_arg1] [cmd_arg2] ..."
          % sys.argv[0])


def _invalid_usage(reason):
    print >>sys.stderr, "ERROR: %s" % reason
    print >>sys.stderr
    print >>sys.stderr, _USAGE
    sys.exit(2)


def results_in_time_order(processes, wait_interval=1):
    """Given a list of subprocesses, yield their rc's in completion order.

    If you just did:
        jobs = [subprocess.Popen(x, ...) for x in  ....]
        for job in jobs:
           result = job.wait()
           ...
    then if the first job in the jobs-list takes the longest, you'll
    have to wait until it's done to get any results from *any* of the
    jobs.  This function gives you the results as they finish, which
    can be much faster.  Note it requires busy-waiting, though.

    Arguments:
        processes: a list of Popen() objects.
        wait_interval: how long, in seconds, to wait between polls
                       of the jobs (to see if they're done)

    Yields:
        Each time through the loop, returns a popen-object.
    """
    processes = list(processes)   # make a copy since we destroy this in place
    while processes:
        # We slice up the wait_interval between all active processes
        wait = wait_interval * 1.0 / len(processes)
        for i in xrange(len(processes)):
            result = processes[i].poll()
            if result is not None:
                yield processes[i]
                del processes[i]
                # Start the loop again now that processes has changed.
                break
            time.sleep(wait)


def run_in_parallel(command_list, args, failfast):
    rc = 0
    processes = [subprocess.Popen(command_list + [arg]) for arg in args]
    for result in results_in_time_order(processes):
        if result.returncode != 0:
            rc = 1
            if failfast:
                break

    # It's possible some processes are still running, if failfast is true.
    # If so, terminate them.
    for process in processes:
        if process.poll() is None:
            process.terminate()

    return rc


def main(args):
    # We do our own argument parsing due to the weird semantics we give `--`.
    if args and args[0] == '--failfast':
        failfast = True
        args = args[1:]
    else:
        failfast = False

    if not args:
        _invalid_usage("No arguments specified")

    try:
        dashdash = args.index('--')
    except ValueError:
        _invalid_usage("No `--` found in arguments")

    cmd_args = args[:dashdash]
    cmd = args[(dashdash + 1):]

    return run_in_parallel(cmd, cmd_args, failfast)


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
