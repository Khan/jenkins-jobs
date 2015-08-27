#!/usr/bin/env python

from setuptools import find_packages, setup

setup(
    name='jenkins-tools',
    version='1.0',
    author='Khan Academy',
    packages=find_packages(),
    scripts=['deploy_pipeline.py'],
    description='Khan Academy deployment system library',
    classifiers=[
        'License :: OSI Approved :: MIT License',
    ],
)
