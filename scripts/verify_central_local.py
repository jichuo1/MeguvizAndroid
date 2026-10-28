"""Check local publication artifacts without reading credentials or uploading."""
from pathlib import Path
import argparse
import hashlib
import html
import io
import json
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile


GROUP = 'io.github.jichuo1.meguviz'
MODULES = ('android-runtime', 'android-window', 'android-lifecycle', 'android-resources-loader')
EXPECTED_INTERNAL = {
    'android-runtime': set(),
    'android-window': {'meguviz-android-runtime'},
    'android-lifecycle': {'meguviz-android-window'},
    'android-resources-loader': {'meguviz-android-runtime'},
}
FORBIDDEN_GROUPS = ('com.highcapable.betterandroid', 'com.highcapable.hikage',
                    'com.highcapable.kavaref', 'io.github.libxposed',
                    'com.github.jichuo1.LumenCoacervationEngine')


def check(condition, message):
    if not condition:
        raise ValueError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def verify_module(repo, version, module, javap):
    artifact = 'meguviz-' + module
    stem = repo.joinpath(*GROUP.split('.'), artifact, version, artifact + '-' + version)
    paths = {name: Path(str(stem) + suffix) for name, suffix in (
        ('aar', '.aar'), ('pom', '.pom'), ('module', '.module'),
        ('sources', '-sources.jar'), ('javadoc', '-javadoc.jar'))}
    for name, path in paths.items():
        check(path.is_file() and path.stat().st_size > 0, 'Missing local artifact: ' + module + '/' + name)
    raw_aar = paths['aar'].read_bytes()
    with zipfile.ZipFile(io.BytesIO(raw_aar)) as archive:
        check(archive.testzip() is None and 'classes.jar' in archive.namelist(), 'Invalid AAR: ' + module)
        classes_jar = archive.read('classes.jar')
    with zipfile.ZipFile(io.BytesIO(classes_jar)) as archive:
        check(archive.testzip() is None, 'Invalid classes.jar: ' + module)
        class_names = set(archive.namelist())
    with zipfile.ZipFile(paths['sources']) as archive:
        check(archive.testzip() is None and any(n.endswith(('.kt', '.java')) for n in archive.namelist()),
              'Sources JAR has no source: ' + module)
    ns = {'p': 'http://maven.apache.org/POM/4.0.0'}
    pom = ET.parse(paths['pom']).getroot()
    check(pom.findtext('p:groupId', namespaces=ns) == GROUP and
          pom.findtext('p:artifactId', namespaces=ns) == artifact and
          pom.findtext('p:version', namespaces=ns) == version, 'POM identity mismatch: ' + module)
    check(bool(pom.findall('p:licenses/p:license/p:name', ns)) and
          bool(pom.findtext('p:url', namespaces=ns)) and
          bool(pom.findall('p:developers/p:developer', ns)) and
          bool(pom.findtext('p:scm/p:url', namespaces=ns)), 'POM metadata incomplete: ' + module)
    pom_text = ET.tostring(pom, encoding='unicode')
    check('SNAPSHOT' not in pom_text.upper() and not any(group in pom_text for group in FORBIDDEN_GROUPS),
          'POM mentions forbidden or SNAPSHOT dependencies: ' + module)
    pom_internal = {}
    for dependency in pom.findall('p:dependencies/p:dependency', ns):
        group = dependency.findtext('p:groupId', namespaces=ns) or ''
        name = dependency.findtext('p:artifactId', namespaces=ns) or ''
        value = dependency.findtext('p:version', namespaces=ns) or ''
        check(group and name and value and '${' not in value and 'SNAPSHOT' not in value.upper(),
              'POM has missing, unresolved or SNAPSHOT dependency: ' + module)
        check(not any(group == x or group.startswith(x + '.') for x in FORBIDDEN_GROUPS),
              'POM has forbidden dependency: ' + module + '/' + group)
        if group == GROUP:
            check(name not in pom_internal, 'POM repeats internal dependency: ' + module + '/' + name)
            pom_internal[name] = value
    check(set(pom_internal) == EXPECTED_INTERNAL[module] and
          all(value == version for value in pom_internal.values()),
          'POM internal dependency set/version mismatch: ' + module)
    module_metadata = json.loads(paths['module'].read_text(encoding='utf-8-sig'))
    metadata_text = json.dumps(module_metadata)
    check('SNAPSHOT' not in metadata_text.upper() and
          not any(group in metadata_text for group in FORBIDDEN_GROUPS),
          'Gradle metadata mentions forbidden or SNAPSHOT dependencies: ' + module)
    component = module_metadata.get('component', {})
    check(component.get('group') == GROUP and component.get('module') == artifact and
          component.get('version') == version, 'Gradle metadata identity mismatch: ' + module)
    variants = module_metadata.get('variants', [])
    check(isinstance(variants, list) and bool(variants), 'Missing Gradle variants: ' + module)
    usages = set()
    for variant in variants:
        check(isinstance(variant, dict) and 'available-at' not in variant,
              'Invalid or redirected Gradle variant: ' + module)
        check(not variant.get('dependencyConstraints'), 'Unexpected Gradle dependency constraints: ' + module)
        attrs = variant.get('attributes', {})
        check(isinstance(attrs, dict), 'Invalid Gradle variant attributes: ' + module)
        usage = attrs.get('org.gradle.usage')
        dependencies = variant.get('dependencies', [])
        check(isinstance(dependencies, list), 'Invalid Gradle variant dependencies: ' + module)
        internal = {}
        for dependency in dependencies:
            group, name = dependency.get('group', ''), dependency.get('module', '')
            constraint = dependency.get('version', {})
            check(group and name and isinstance(constraint, dict) and constraint and
                  'SNAPSHOT' not in json.dumps(constraint).upper(),
                  'Gradle metadata has missing or SNAPSHOT dependency: ' + module)
            check(not any(group == x or group.startswith(x + '.') for x in FORBIDDEN_GROUPS),
                  'Gradle metadata has forbidden dependency: ' + module + '/' + group)
            if group == GROUP:
                check(name not in internal, 'Gradle variant repeats internal dependency: ' + module + '/' + name)
                internal[name] = constraint
        if attrs.get('org.gradle.category') == 'library' and usage in ('java-api', 'java-runtime'):
            check(usage not in usages, 'Duplicate Gradle API/runtime variant: ' + module + '/' + usage)
            usages.add(usage)
            check(set(internal) == EXPECTED_INTERNAL[module] and
                  all(value == {'requires': version} for value in internal.values()),
                  'Gradle variant internal dependency set/version mismatch: ' + module + '/' + usage)
        else:
            check(not internal, 'Unexpected internal dependency in non-API/runtime variant: ' + module)
    check(usages == {'java-api', 'java-runtime'}, 'Missing Gradle API/runtime variant: ' + module)
    with zipfile.ZipFile(paths['javadoc']) as archive:
        entries = archive.namelist()
        allowed_entries = {'META-INF/', 'META-INF/MANIFEST.MF', 'index.html', 'api-manifest.json'}
        check(archive.testzip() is None and len(entries) == len(set(entries)) and
              {'index.html', 'api-manifest.json'}.issubset(entries) and set(entries) <= allowed_entries,
              'API documentation JAR missing index/manifest: ' + module)
        document = archive.read('index.html').decode('utf-8')
        manifest = json.loads(archive.read('api-manifest.json').decode('utf-8'))
    classes = manifest.get('public_classes', [])
    check(manifest.get('module') == module and manifest.get('version') == version and
          manifest.get('aar_sha256') == digest(raw_aar) and
          manifest.get('classes_jar_sha256') == digest(classes_jar), 'API document bound to wrong AAR: ' + module)
    check(isinstance(classes, list) and len(classes) > 0 and len(classes) == manifest.get('public_class_count') and
          len(classes) == len(set(classes)) and '<pre>' in document and digest(raw_aar) in document,
          'API document has no valid declarations: ' + module)
    check(all(name.replace('.', '/') + '.class' in class_names and html.escape(name) in document for name in classes),
          'API document mentions classes absent from this AAR: ' + module)
    # Independent spot check: execute javap against one class from this exact AAR.
    with tempfile.TemporaryDirectory(prefix='central-doc-check-') as temp:
        jar = Path(temp) / 'classes.jar'
        jar.write_bytes(classes_jar)
        result = subprocess.run([str(javap), '-public', '-classpath', str(jar), classes[0]],
                                capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=180)
        check(result.returncode == 0, 'javap spot check failed: ' + module)
        match = re.search(r'(?ms)^public\b[^\r\n]*\{.*?^\}', result.stdout)
        check(bool(match) and html.escape(match.group(0)) in document,
              'Document does not match actual public JVM signature: ' + module)
    signatures = {name: Path(str(path) + '.asc').is_file() and Path(str(path) + '.asc').stat().st_size > 0
                  for name, path in paths.items()}
    checksums = {}
    for name, path in paths.items():
        data = path.read_bytes()
        for algorithm in ('md5', 'sha1'):
            checksum = Path(str(path) + '.' + algorithm)
            valid = checksum.is_file() and checksum.read_text(encoding='ascii').strip().lower() == getattr(hashlib, algorithm)(data).hexdigest()
            checksums[name + '.' + algorithm] = valid
    return dict(module=module, aar_sha256=digest(raw_aar), public_classes=len(classes),
                signatures_present=signatures, checksums_valid=checksums)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--repo', type=Path, required=True)
    parser.add_argument('--version', required=True)
    parser.add_argument('--javap', type=Path, required=True)
    args = parser.parse_args()
    check(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,80}', args.version) is not None and
          '..' not in args.version and not args.version.upper().endswith('-SNAPSHOT'),
          'Central preparation requires an immutable non-SNAPSHOT version')
    check(args.repo.is_dir() and args.javap.is_file(), 'Missing local repository or javap')
    modules = [verify_module(args.repo, args.version, module, args.javap) for module in MODULES]
    missing = any(not all(x['signatures_present'].values()) for x in modules)
    checksum_failure = any(not all(x['checksums_valid'].values()) for x in modules)
    result = dict(status='BLOCKED_MISSING_SIGNATURES' if missing else 'BLOCKED_SIGNATURES_UNVERIFIED',
                  docs_status='PASS_SCOPED', checksum_status='BLOCKED' if checksum_failure else 'PASS',
                  version=args.version, modules=modules,
                  scope='Local AAR, sources, bytecode documentation, metadata and signature presence only; does not read credentials, verify OpenPGP trust or upload.')
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 2


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (OSError, ValueError, KeyError, json.JSONDecodeError, zipfile.BadZipFile, ET.ParseError, subprocess.SubprocessError) as error:
        print(json.dumps({'status': 'FAIL_DOCUMENTS', 'reason': str(error)}, ensure_ascii=False))
        raise SystemExit(1)
