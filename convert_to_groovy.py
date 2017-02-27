#!/usr/bin/env python

"""A script to extract the 'params' section of a freestyle jenkins job.

Given a config.xml file for a freestyle (old-style) jenkins job,
extract out the user-specified params from the config, and emit them
in a style that is suitable for a groovy script.

We also handle other setup stuff too.
"""

import sys
import textwrap
from xml.etree import ElementTree


def _escape(s):
    """Return an s that looks like a groovy double-quoted string.

    We also do some line wrapping to fit inside 80 chars.  The results
    won't always be pretty.
    """
    if s is None:
        s = ''
    s = s.replace('"', '\\"')
    # Textwrap doesn't do well when you want to keep existing linebreaks.
    # We replace existing linebreaks most of the time, but not if the
    # string has meaningful html formatting (<ul>, <ol>, <p>, <br> etc).
    keep_linebreaks_tags = {'ul', 'ol', 'p', 'br'}
    if any('<%s' % tag in s for tag in keep_linebreaks_tags):
        lines = s.split('\n')
        wrapped_lines = [textwrap.fill(l, break_long_words=False,
                                       replace_whitespace=False,
                                       drop_whitespace=False)
                         for l in lines]
        s = '\n'.join(wrapped_lines)
    else:
        s = textwrap.fill(s, break_long_words=False)

    # Groovy lets strings have embedded newlines if you use triple-quotes.
    if '\n' in s:
        return '"""%s"""' % s
    else:
        return '"%s"' % s


def _escape_list(lst):
    """Return a list that looks like a groovy list."""
    return '[%s]' % ', '.join(_escape(c) for c in lst)


def process_throttle(root):
    throttle_node = root.find(
        './/hudson.plugins.throttleconcurrents.ThrottleJobProperty')
    if throttle_node is None:
        return

    categories_parent = throttle_node.find('categories')
    categories_nodes = categories_parent.findall('string')
    categories = [e.text for e in categories_nodes]
    print ').blockBuilds(%s' % _escape_list(categories)
    print


def process_cron(root):
    cron_node = root.find('.//hudson.triggers.TimerTrigger')
    if cron_node is None:
        return

    cron = cron_node.find('spec').text
    print ').addCronSchedule(%s' % _escape(cron)
    print


def _process_param(param_node):
    if 'StringParameterDefinition' in param_node.tag:
        print ').addStringParam('
    elif 'BooleanParameterDefinition' in param_node.tag:
        print ').addBooleanParam('
    elif 'ChoiceParameterDefinition' in param_node.tag:
        print ').addChoiceParam('
    else:
        raise NotImplementedError(param_node.tag)

    name = param_node.find('name')
    description = param_node.find('description')
    default_value = param_node.find('defaultValue')
    choices = param_node.find('choices')
    if choices is not None:
        # choices look like this:
        # <choices class="java.util.Arrays$ArrayList">
        #    <a class="string-array">
        #      <string>default</string>
        #      <string>yes</string>
        #      <string>no</string>
        #    </a>
        # </choices>
        choices = choices.find('a')
        choices = choices.findall('string')

    name = name.text   # This field is required!
    description = description.text if description is not None else ''
    default_value = default_value.text if default_value is not None else ''
    choices = [e.text for e in choices] if choices is not None else []

    print '    %s,' % _escape(name)
    print '    %s,' % _escape(description)
    if choices:
        print '    %s' % _escape_list(choices)
    else:
        print '    %s' % _escape(default_value)
    print


def process_params(root):
    params = root.find('.//parameterDefinitions')
    if params is None:
        return

    for param in params:
        _process_param(param)


def main(config_filename):
    tree = ElementTree.parse(config_filename)
    root = tree.getroot()

    print 'new Setup(steps'
    print
    process_throttle(root)
    process_cron(root)
    process_params(root)
    print ').apply();'


if __name__ == '__main__':
    main(sys.argv[1])

