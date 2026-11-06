#!/usr/bin/env python3
"""Record SDK build inputs and package installable BBOS release artifacts."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED, is_zipfile

ROOT = Path(__file__).resolve().parents[1]
MAGIC = bytes.fromhex('dec0ffff')


def digest(data, algorithm='sha256'):
    return hashlib.new(algorithm, data).hexdigest()


def inputs(root):
    paths = [root / 'pom.xml', root / 'tools/blackberry.sh', root / 'tools/blackberry_release.py']
    paths += sorted((root / 'src/main/java').rglob('*.java'))
    paths += sorted(p for p in (root / 'platform/midp').rglob('*') if p.is_file())
    return {p.relative_to(root).as_posix(): digest(p.read_bytes()) for p in paths}


def git(root, *args):
    return subprocess.check_output(['git', '-C', str(root), *args], text=True).strip()


def record_build(root, output, sdk, compiler, ecj):
    artifacts = {p.name: digest(p.read_bytes()) for p in sorted(output.glob('BerryBoyAdvance*.cod'))}
    artifacts.update({name: digest((output / name).read_bytes()) for name in ['BerryBoyAdvance.jar', 'BerryBoyAdvance.jad']})
    tools = {'net_rim_api.jar': digest((sdk / 'lib/net_rim_api.jar').read_bytes()),
             'rapc.jar': digest((sdk / 'bin/rapc.jar').read_bytes())}
    if compiler == 'ecj':
        tools['ecj.jar'] = digest(ecj.read_bytes())
    data = dict(schema=1, commit=git(root, 'rev-parse', 'HEAD'),
                source_dirty=bool(git(root, 'status', '--porcelain', '--untracked-files=normal')),
                inputs=inputs(root), artifacts=artifacts, compiler=compiler, tool_sha256=tools,
                signing='No signing step performed by the SDK build script')
    (output / 'build-record.json').write_text(json.dumps(data, indent=2) + '\n')


def descriptor(data):
    result = {}
    for line in data.decode('utf-8-sig').splitlines():
        if not line:
            continue
        if ':' not in line:
            raise ValueError('Malformed JAD line')
        key, value = line.split(':', 1)
        if key in result:
            raise ValueError('Duplicate JAD property: ' + key)
        result[key] = value.strip()
    return result


def modules(output):
    source = output / 'BerryBoyAdvance.cod'
    if is_zipfile(source):
        with ZipFile(source) as archive:
            if len(archive.namelist()) != len(set(archive.namelist())):
                raise ValueError('Duplicate COD archive member')
            if any(i.file_size > 262144 for i in archive.infolist()) or len(archive.infolist()) > 32:
                raise ValueError('Oversized COD container')
            files = {name: archive.read(name) for name in archive.namelist()}
        if list(output.glob('BerryBoyAdvance-*.cod')):
            raise ValueError('Mixed container and loose COD modules')
    else:
        files = {p.name: p.read_bytes() for p in output.glob('BerryBoyAdvance*.cod')}
    for name, data in files.items():
        if not re.fullmatch(r'BerryBoyAdvance(?:-[1-9][0-9]*)?\.cod', name) or not data.startswith(MAGIC):
            raise ValueError('Unexpected COD module: ' + name)
    ordered = sorted(files, key=lambda n: 0 if n == 'BerryBoyAdvance.cod' else int(n.split('-')[1][:-4]))
    expected = ['BerryBoyAdvance.cod'] + [f'BerryBoyAdvance-{i}.cod' for i in range(1, len(files))]
    if not ordered or ordered != expected:
        raise ValueError('Missing or noncontiguous COD modules')
    return {name: files[name] for name in ordered}


def verify_jar(data):
    from io import BytesIO
    with ZipFile(BytesIO(data)) as archive:
        if 'berryboy/BlackBerryMidlet.class' not in archive.namelist():
            raise ValueError('Native MIDlet JAR required')
        for name in archive.namelist():
            if name.endswith('.class'):
                header = archive.read(name)[:8]
                if len(header) != 8 or header[:4] != bytes.fromhex('cafebabe') or int.from_bytes(header[6:8], 'big') > 46:
                    raise ValueError('Non-CLDC class in phone JAR: ' + name)


def package(root, output, destination, version, require_clean=False):
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', version):
        raise ValueError('Expected a numeric x.y.z release version')
    project = ET.parse(root / 'pom.xml').getroot()
    project_version = project.find('{http://maven.apache.org/POM/4.0.0}version').text
    if project_version.removesuffix('-SNAPSHOT') != version:
        raise ValueError('Release version differs from Maven')
    if require_clean and project_version != version:
        raise ValueError('Publication requires a frozen Maven version without SNAPSHOT')
    record = json.loads((output / 'build-record.json').read_text())
    if record['inputs'] != inputs(root):
        raise ValueError('SDK build inputs changed; rebuild before packaging')
    if record['commit'] != git(root, 'rev-parse', 'HEAD'):
        raise ValueError('SDK build belongs to another commit; rebuild')
    if require_clean and (record['source_dirty'] or git(root, 'status', '--porcelain')):
        raise ValueError('Publication requires a clean source build')
    for name, checksum in record['artifacts'].items():
        if digest((output / name).read_bytes()) != checksum:
            raise ValueError('SDK artifact changed: ' + name)
    jad = descriptor((output / 'BerryBoyAdvance.jad').read_bytes())
    jar = (output / 'BerryBoyAdvance.jar').read_bytes()
    verify_jar(jar)
    if jad.get('MIDlet-Version') != version or jad.get('MIDlet-Jar-Size') != str(len(jar)):
        raise ValueError('JAD version/JAR size mismatch')
    if jad.get('MIDlet-Jar-URL') != 'BerryBoyAdvance.jar':
        raise ValueError('Unexpected JAD JAR URL')
    files = modules(output)
    # RIM loaders expect raw sibling CODs rather than the SDK's ZIP-shaped .cod container.
    jad = {k: v for k, v in jad.items() if not re.fullmatch(r'RIM-COD-(?:URL|Size|SHA1)(?:-[0-9]+)?', k)}
    jad['RIM-COD-Module-Name'] = 'BerryBoyAdvance'
    jad.setdefault('RIM-COD-Module-Dependencies', 'net_rim_cldc')
    for index, (name, data) in enumerate(files.items()):
        suffix = '' if index == 0 else '-' + str(index)
        jad['RIM-COD-URL' + suffix] = name
        jad['RIM-COD-Size' + suffix] = str(len(data))
        jad['RIM-COD-SHA1' + suffix] = bytes.fromhex(digest(data, 'sha1')).hex(' ')
    files['BerryBoyAdvance.jad'] = ''.join(k + ': ' + v + '\r\n' for k, v in jad.items()).encode()
    files['BerryBoyAdvance.jar'] = jar
    loader = ET.Element('loader', version='1.0')
    app = ET.SubElement(loader, 'application', id='berryboy.advance')
    for key, value in [('name', 'BerryBoy Advance'), ('description', 'Game Boy Advance emulator for BBOS 7.1'),
                       ('version', version), ('vendor', jad.get('MIDlet-Vendor', 'BerryBoy'))]:
        ET.SubElement(app, key).text = value
    fileset = ET.SubElement(app, 'fileset', Java='1.0')
    ET.SubElement(fileset, 'files').text = '\n' + '\n'.join(n for n in files if n.endswith('.cod')) + '\n'
    files['BerryBoyAdvance.alx'] = ET.tostring(loader, encoding='utf-8', xml_declaration=True)
    files['INSTALL.txt'] = (root / 'tools/blackberry-install.txt').read_bytes()
    manifest = dict(schema=1, version=version, target='BlackBerry OS 7.1 / Bold 9900',
                    commit=record['commit'], source_dirty=record['source_dirty'] or bool(git(root, 'status', '--porcelain')), signing=record['signing'],
                    compiler=record['compiler'], tool_sha256=record['tool_sha256'],
                    build_inputs_sha256=digest(json.dumps(record['inputs'], sort_keys=True).encode()),
                    files={n: dict(bytes=len(b), sha256=digest(b)) for n, b in files.items()})
    files['manifest.json'] = (json.dumps(manifest, indent=2) + '\n').encode()
    files['SHA256SUMS'] = ''.join(digest(data) + '  ' + name + '\n' for name, data in sorted(files.items())).encode()
    destination.mkdir(parents=True, exist_ok=True)
    archive_path = destination / f'BerryBoy-Advance-{version}-BBOS7.1.zip'
    if archive_path.exists():
        raise ValueError('Release archive already exists; use a fresh output directory')
    with ZipFile(archive_path, 'w', compression=ZIP_DEFLATED) as archive:
        for name, data in sorted(files.items()):
            info = ZipInfo(name, (2020, 1, 1, 0, 0, 0))
            info.compress_type = ZIP_DEFLATED; info.external_attr = 0o100644 << 16
            archive.writestr(info, data)
    archive_path.with_suffix('.zip.sha256').write_text(digest(archive_path.read_bytes()) + '  ' + archive_path.name + '\n')
    return archive_path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    record = sub.add_parser('record')
    record.add_argument('--sdk', type=Path, required=True)
    record.add_argument('--compiler', choices=['ecj', 'jdk6'], required=True)
    record.add_argument('--ecj', type=Path)
    pack = sub.add_parser('package')
    pack.add_argument('--version', required=True)
    pack.add_argument('--destination', type=Path, default=ROOT / 'target/releases')
    pack.add_argument('--require-clean', action='store_true')
    for command in [record, pack]:
        command.add_argument('--output', type=Path, default=ROOT / 'target/blackberry/output')
    args = parser.parse_args()
    if args.command == 'record':
        record_build(ROOT, args.output, args.sdk, args.compiler, args.ecj)
    else:
        print(package(ROOT, args.output, args.destination, args.version, args.require_clean))


if __name__ == '__main__':
    main()
