#!/usr/bin/env python3
"""Extract the two pinned JDE 7.1 JARs needed for Linux ECJ/RAPC builds."""
import argparse
import hashlib
from pathlib import Path
import shutil
import struct
import subprocess
import urllib.request

INSTALLER_URL = 'https://archive.lunar-project.org/BlackBerry%20OS/Development/JDE/BlackBerry_JDE_7.1.0.exe'
INSTALLER_SHA = '2bf7117ad26e3e65d8c12032e0a1f90eb7487339169881ac084737e48d30b925'
ECJ_URL = 'https://repo.maven.apache.org/maven2/org/eclipse/jdt/core/compiler/ecj/4.6.1/ecj-4.6.1.jar'
ECJ_SHA = '9cddda75f4a1b4469e73f44e7b61a3e897d0f657df4797f9106ffe88c4eeade0'
JARS = {'bin/rapc.jar': '617107f0ca2b3710544fc12bd63f6da147abfd33d77e87cef1f473bdbf279d0a',
        'lib/net_rim_api.jar': 'b36d23121983e1115bbfa638eaf969c208d673ffb217b5ffc8e3cc90c045bb49'}
CAB_OFFSET = 1724928
CAB_SIZE = 300569439


def check(path, expected):
    with path.open('rb') as stream:
        actual = hashlib.file_digest(stream, 'sha256').hexdigest()
    if actual != expected:
        raise ValueError('Tool checksum mismatch: ' + path.name)


def download(url, path, expected):
    temporary = path.with_suffix(path.suffix + '.partial')
    try:
        request = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0 (compatible; BerryBoyAdvanceBuild/1.0)'})
        with urllib.request.urlopen(request, timeout=60) as response, temporary.open('wb') as output:
            shutil.copyfileobj(response, output)
        check(temporary, expected)
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def bootstrap(output, installer=None, cabextract='cabextract'):
    output.mkdir(parents=True, exist_ok=True)
    sdk = output / 'sdk'
    if not all((sdk / name).exists() for name in JARS):
        if installer is None:
            installer = output / 'BlackBerry_JDE_7.1.0.exe'
            if not installer.exists():
                download(INSTALLER_URL, installer, INSTALLER_SHA)
        check(installer, INSTALLER_SHA)
        cabinet = output / 'jde71-main.cab'
        try:
            with installer.open('rb') as source, cabinet.open('wb') as target:
                source.seek(CAB_OFFSET)
                header = source.read(12)
                if header[:4] != b'MSCF' or struct.unpack('<I', header[8:12])[0] != CAB_SIZE:
                    raise ValueError('Unexpected embedded JDE cabinet')
                source.seek(CAB_OFFSET)
                remaining = CAB_SIZE
                while remaining:
                    chunk = source.read(min(1024 * 1024, remaining))
                    if not chunk:
                        raise ValueError('Truncated JDE cabinet')
                    target.write(chunk); remaining -= len(chunk)
            for name, expected in JARS.items():
                target = sdk / name; target.parent.mkdir(parents=True, exist_ok=True)
                subprocess.run([cabextract, '-q', '-F', target.name, '-d', str(target.parent), str(cabinet)], check=True)
                check(target, expected)
        finally:
            cabinet.unlink(missing_ok=True)
    for name, expected in JARS.items():
        check(sdk / name, expected)
    ecj = output / 'ecj-4.6.1.jar'
    if not ecj.exists():
        download(ECJ_URL, ecj, ECJ_SHA)
    check(ecj, ECJ_SHA)
    return sdk, ecj


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--installer', type=Path)
    parser.add_argument('--cabextract', default='cabextract')
    args = parser.parse_args()
    sdk, ecj = bootstrap(args.output.resolve(), args.installer, args.cabextract)
    print('BB_JDE_HOME=' + str(sdk))
    print('BB_ECJ_JAR=' + str(ecj))
