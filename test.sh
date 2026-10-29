#!/bin/bash
# Runs the protocol tests on the JVM (no Android SDK needed): Proto.java + tests/ProtoTest.java.
set -e
A=$(cd "$(dirname "$0")" && pwd)
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
javac -Xlint:-options --release 11 -d "$T" "$A/src/local/wavebuds/Proto.java" "$A/tests/ProtoTest.java"
java -cp "$T" local.wavebuds.ProtoTest
