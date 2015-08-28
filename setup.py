#!/usr/bin/env python

from setuptools import find_packages, setup

setup(
    name='jenkins-tools',
    version='1.0',
    author='Khan Academy',
    packages=find_packages(),
    scripts=['deploy_pipeline.py'],
    description='Khan Academy deployment system library',
    install_requires=[
        'lxml>=3.4,<3.5',
    ],
    classifiers=[
        'License :: OSI Approved :: MIT License',
    ],
)
