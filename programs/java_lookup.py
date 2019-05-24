# Copyright 2019-present Facebook, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may
# not use this file except in compliance with the License. You may obtain
# a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations
# under the License.

import logging
import os
import re
import sys
import textwrap

from buck_tool import BuckToolException
from subprocutils import which

JDK_8_AND_UNDER_PATH_VERSION_REGEX_STRING = r"jdk(1\.(\d+)(\.\d+(_\d+)?)?)(\.jdk)?"
JDK_9_AND_OVER_PATH_VERSION_REGEX_STRING = r"jdk-((\d+)(\.\d+(\.\d+(_\d+)?)?)?)(\.jdk)?"

JDK_8_AND_UNDER_PATH_VERSION_REGEX = re.compile(
    "^" + JDK_8_AND_UNDER_PATH_VERSION_REGEX_STRING + "$"
)
JDK_9_AND_OVER_PATH_VERSION_REGEX = re.compile(
    "^" + JDK_9_AND_OVER_PATH_VERSION_REGEX_STRING + "$"
)

# Note: Group 1 of these regexes contains the entire version string, group 2 contains major version.
JDK_PATH_REGEXES = [
    re.compile(
        r"^/Library/Java/JavaVirtualMachines/"
        + JDK_8_AND_UNDER_PATH_VERSION_REGEX_STRING
        + "/Contents/Home$"
    ),
    re.compile(
        r"^/Library/Java/JavaVirtualMachines/"
        + JDK_9_AND_OVER_PATH_VERSION_REGEX_STRING
        + "/Contents/Home$"
    ),
    re.compile(
        "^C:\\\\Program Files\\\\Java\\\\"
        + JDK_8_AND_UNDER_PATH_VERSION_REGEX_STRING
        + "$"
    ),
    re.compile(
        "^C:\\\\Program Files\\\\Java\\\\"
        + JDK_9_AND_OVER_PATH_VERSION_REGEX_STRING
        + "$"
    ),
]


def _get_suspected_java_version_from_java_path(java_path):
    for regex in JDK_PATH_REGEXES:
        match = regex.match(java_path)
        if match:
            return int(match.group(2))
    return None


def _get_java_path_for_highest_minor_version(base_path, desired_major_version):
    max_version = None
    max_dir = None
    for _, dirs, _ in os.walk(base_path):
        for dir in dirs:
            regex = (
                JDK_8_AND_UNDER_PATH_VERSION_REGEX
                if desired_major_version <= 8
                else JDK_9_AND_OVER_PATH_VERSION_REGEX
            )
            match = regex.match(dir)
            if match:
                major_version = int(match.group(2))
                if major_version == desired_major_version:
                    version_string = match.group(1)
                    version = tuple(
                        map(int, version_string.replace("_", ".").split("."))
                    )
                    if not max_version or version > max_version:
                        max_version = version
                        max_dir = dir

    return os.path.join(base_path, max_dir) if max_dir else None


def _get_known_java_path_for_version(java_major_version):
    java_path = None
    if sys.platform == "darwin":
        java_path = _get_java_path_for_highest_minor_version(
            "/Library/Java/JavaVirtualMachines", java_major_version
        )
        if java_path:
            java_path += "/Contents/Home"
    elif sys.platform == "win32":
        java_path = _get_java_path_for_highest_minor_version(
            r"C:\Program Files\Java", java_major_version
        )

    return java_path if java_path and os.path.isdir(java_path) else None


def _get_java_exec(java_base_path):
    java_exec = "java.exe" if os.name == "nt" else "java"
    return os.path.join(java_base_path, "bin", java_exec)


def get_java_path(required_java_version):
    java_home_path = os.getenv("JAVA_HOME")
    if java_home_path:
        # Though we try to respect JAVA_HOME, if the path looks like the wrong version of Java, try
        # to use a known location of the JDK for the right version instead.
        suspected_java_version = _get_suspected_java_version_from_java_path(
            java_home_path
        )
        if suspected_java_version and suspected_java_version != required_java_version:
            message = (
                'Warning: JAVA_HOME is set to "{}", which looks like a Java {} path, '
                + "but Buck requires Java {}."
            ).format(java_home_path, suspected_java_version, required_java_version)
            if os.getenv("BUCK_RESPECT_JAVA_HOME") != "1":
                message += " Ignoring JAVA_HOME. Set BUCK_RESPECT_JAVA_HOME to 1 to disable this behavior."
                java_home_path = None
            logging.warning(message)
    if java_home_path is None:
        # Default to a known location of the JDK for the right version of Java, regardless of what
        # version of Java is on the PATH.
        java_base_path = _get_known_java_path_for_version(required_java_version)
        java_path = None
        if java_base_path:
            java_path = _get_java_exec(java_base_path)
            if not os.path.isfile(java_path):
                java_path = None
        if not java_path:
            java_path = which("java")
        if java_path is None:
            raise BuckToolException(
                "Could not find Java executable. \
Make sure it is on PATH or JAVA_HOME is set."
            )
    else:
        java_path = _get_java_exec(java_home_path)
        if not os.path.isfile(java_path):
            message = textwrap.dedent(
                """
            Could not find Java executable under JAVA_HOME at: '{}'.
            Please make sure your JAVA_HOME environment variable is set correctly.
            Then restart buck (buck kill) and try again.
            """
            ).format(java_path)
            raise BuckToolException(message)
    return java_path
