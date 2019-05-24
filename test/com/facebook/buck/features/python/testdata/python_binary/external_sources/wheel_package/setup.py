from setuptools import find_packages, setup

setup(
    name="wheel_package",
    version="0.0.1",
    description="A sample Python project",
    long_description="A sample Python project",
    url="https://buck.build",
    author="Buck",
    license="Apache License 2.0",
    packages=["wheel_package"],
    # See https://pypi.python.org/pypi?%3Aaction=list_classifiers
    classifiers=[
        "Development Status :: 5 - Production/Stable",
        "Intended Audience :: Developers",
        "Topic :: Software Development :: Build Tools",
        "License :: OSI Approved :: Apache Software License"
        "Programming Language :: Python :: 2",
        "Programming Language :: Python :: 3",
    ],
    data_files=[
        ("lib", ["lib/__init__.py"]),
        ("lib/foo", ["lib/foo/bar.py", "lib/foo/__init__.py"]),
        ("lib/foobar", ["lib/foobar/baz.py", "lib/foobar/__init__.py"]),
    ],
)
