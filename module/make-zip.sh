#!/bin/bash
# Packs out/WaveBuds.apk into out/WaveBuds-magisk.zip (install it from the Magisk app, then reboot).
set -e
M=$(cd "$(dirname "$0")" && pwd)
O=$M/../out
[ -f "$O/WaveBuds.apk" ] || { echo "build the APK first: ./build.sh"; exit 1; }
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
cp -r "$M/META-INF" "$M/system" "$M/module.prop" "$M/service.sh" "$M/customize.sh" "$T/"
mkdir -p "$T/system/priv-app/WaveBuds"
cp "$O/WaveBuds.apk" "$T/system/priv-app/WaveBuds/WaveBuds.apk"
rm -f "$O/WaveBuds-magisk.zip"
python3 -I -c "
import os, sys, zipfile
root, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    for d, _, files in os.walk(root):
        for f in files:
            p = os.path.join(d, f)
            z.write(p, os.path.relpath(p, root))
" "$T" "$O/WaveBuds-magisk.zip"
ls -la "$O/WaveBuds-magisk.zip"
