#!/bin/bash
# Checks that a released WaveBuds.apk was built from this source: rebuilds out/WaveBuds.apk and compares
# every file inside the two APKs except the signature (META-INF/). Use the JDK major version and
# build-tools named in the release notes (bytecode can differ between javac versions).
set -e
A=$(cd "$(dirname "$0")" && pwd)
[ -f "$1" ] || { echo "usage: ./verify.sh path/to/released/WaveBuds.apk"; exit 2; }
REL=$(realpath "$1")
WAVEBUDS_KEYSTORE=${WAVEBUDS_KEYSTORE:-$(mktemp -u --suffix=.jks)} "$A/build.sh" >/dev/null
python3 -I - "$REL" "$A/out/WaveBuds.apk" <<'PY'
import sys, zipfile
def entries(p):
    with zipfile.ZipFile(p) as z:
        return {i.filename: z.read(i) for i in z.infolist() if not i.filename.startswith('META-INF/')}
rel, built = entries(sys.argv[1]), entries(sys.argv[2])
bad = sorted(n for n in rel.keys() | built.keys() if rel.get(n) != built.get(n))
for n in bad: print('differs:', n)
print('OK: identical apart from the signature' if not bad else f'MISMATCH in {len(bad)} file(s)')
sys.exit(1 if bad else 0)
PY
