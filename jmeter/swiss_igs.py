#!/usr/bin/env python3
"""Prepare the swiss-igs test: the latest Swiss IGs from fhir.ch, one example per IG.

usage: python3 swiss_igs.py [--no-releases]

Reads https://fhir.ch/package-registry.json and takes the latest version of every R4 IG; when the latest version is a
pre-release (ballot), also the latest release of the IG (as a test instance has both), unless --no-releases. Downloads
the packages from packages.fhir.org (into swiss-igs-packages/, git-ignored), and writes
  swiss-igs.yaml   matchbox configuration (with-preload, H2) installing these IGs (the dependencies come with them)
  swiss-igs.csv    per IG an example from package/example with a profile of the IG itself: profile, example file, ig
  swiss-*.json     the examples
IGs without such an example are installed but not validated (listed at the end).
"""
import io
import json
import os
import re
import sys
import tarfile
import time
import urllib.error
import urllib.request

J = os.path.dirname(os.path.abspath(__file__))
PKG_DIR = os.path.join(J, 'swiss-igs-packages')
REGISTRY = 'https://fhir.ch/package-registry.json'


def get(url, attempts=6):
    for attempt in range(attempts):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers={'Accept': 'application/json'}),
                                        timeout=120) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code != 429 or attempt == attempts - 1:
                raise
            time.sleep(5 * 2 ** attempt)  # packages.fhir.org rate limit


def resolve_version(pid, version):
    """A patch wildcard (3.4.x) as the registry resolves it: the highest matching version."""
    if not version.endswith('.x'):
        return version
    prefix = version[:-1]
    versions = [v for v in json.loads(get(f'https://packages.fhir.org/{pid}'))['versions'] if v.startswith(prefix)]
    versions = [v for v in versions if not is_prerelease(v)] or versions
    return max(versions, key=lambda v: [int(p) if p.isdigit() else 0 for p in re.split(r'[.-]', v)]) if versions \
        else version


def is_prerelease(version):
    return '-' in version


def versions_to_test(pkg, with_releases):
    latest = pkg['latest']['version']
    result = [latest]
    if with_releases and is_prerelease(latest):
        package_list = json.loads(get('https://fhir.ch/' + pkg['path']))
        releases = [v['version'] for v in package_list.get('list', [])
                    if v.get('version') not in (None, 'current') and not is_prerelease(v['version'])
                    and v.get('status') != 'ci-build']
        if releases:
            result.append(releases[0])  # package-list.json is newest first
    return result


def load_package(pid, version):
    os.makedirs(PKG_DIR, exist_ok=True)
    path = os.path.join(PKG_DIR, f'{pid}#{version}.tgz')
    if not os.path.exists(path):
        data = get(f'https://packages.fhir.org/{pid}/{version}')
        with open(path, 'wb') as f:
            f.write(data)
    return tarfile.open(path, 'r:gz')


def unresolvable_dependency(pid, version, seen=None):
    """The first dependency (transitively) that can't be installed, e.g. a version 'current', or None."""
    seen = set() if seen is None else seen
    try:
        version = resolve_version(pid, version)
    except Exception:
        return f'{pid}#{version}'
    if f'{pid}#{version}' in seen:
        return None
    seen.add(f'{pid}#{version}')
    if not re.match(r'^\d+\.\d+', version):
        return f'{pid}#{version}'
    try:
        manifest = json.load(load_package(pid, version).extractfile('package/package.json'))
    except Exception:
        return f'{pid}#{version}'
    for dep, dep_version in manifest.get('dependencies', {}).items():
        if dep.startswith('hl7.fhir.r') and dep.endswith('.core'):
            continue
        bad = unresolvable_dependency(dep, dep_version, seen)
        if bad:
            return bad
    return None


def pick_example(tgz, canonical):
    """The smallest example (preferably not a Bundle) with a profile of the IG itself."""
    profiles = set()
    examples = []
    example_of = {}  # "Type/id" -> profile, from the ImplementationGuide (exampleCanonical)
    for m in tgz.getmembers():
        if not m.isfile() or not m.name.endswith('.json'):
            continue
        name = m.name
        if name.startswith('package/example/'):
            examples.append(m)
        elif name.startswith('package/') and name.count('/') == 1 and '/StructureDefinition-' in name:
            sd = json.load(tgz.extractfile(m))
            if sd.get('kind') == 'resource' and sd.get('derivation') == 'constraint':
                profiles.add(sd['url'])
        elif name.startswith('package/ImplementationGuide-'):
            for r in json.load(tgz.extractfile(m)).get('definition', {}).get('resource', []):
                if r.get('exampleCanonical') and r.get('reference', {}).get('reference'):
                    example_of[r['reference']['reference']] = r['exampleCanonical']
    candidates = []
    for m in examples:
        try:
            res = json.load(tgz.extractfile(m))
        except ValueError:
            continue
        declared = res.get('meta', {}).get('profile', [])
        declared += [example_of.get(f'{res.get("resourceType")}/{res.get("id")}', '')]
        for p in declared:
            p = p.split('|')[0]
            if p in profiles and p.startswith(canonical):
                candidates.append((res.get('resourceType') == 'Bundle', m.size, m.name, p, res))
                break
    if not candidates:
        return None
    candidates.sort(key=lambda c: c[:3])
    return candidates[0]


def main():
    with_releases = '--no-releases' not in sys.argv
    registry = json.loads(get(REGISTRY))
    igs = []
    for pkg in registry['packages']:
        pid = pkg['package-id']
        for version in versions_to_test(pkg, with_releases):
            tgz = load_package(pid, version)
            manifest = json.load(tgz.extractfile('package/package.json'))
            fhir_versions = manifest.get('fhirVersions') or [manifest.get('fhir-version-list', [''])[0]]
            if not any(v.startswith('4.0') for v in fhir_versions):
                print(f'skip {pid}#{version}: FHIR {", ".join(fhir_versions)}')
                continue
            bad = unresolvable_dependency(pid, version)
            if bad:
                print(f'skip {pid}#{version}: depends on {bad}, which cannot be installed')
                continue
            canonical = manifest.get('canonical') or pkg['canonical']
            igs.append((pid, version, canonical, pick_example(tgz, canonical)))

    rows, without = [], []
    for pid, version, canonical, example in igs:
        if example is None:
            without.append(f'{pid}#{version}')
            continue
        _, size, member, profile, res = example
        short = pid.replace('ch.fhir.ig.', '')
        body = f'swiss-{short}-{version}-{res["resourceType"]}-{res.get("id", "example")}.json'
        body = re.sub(r'[^A-Za-z0-9.\-]', '-', body[:-5]) + '.json'
        with open(os.path.join(J, body), 'w') as f:
            json.dump(res, f, indent=2, ensure_ascii=False)
        rows.append((profile, body, f'{pid}%23{version}', f'{pid}#{version}'))
        print(f'{pid}#{version}: {profile} ({member.split("/")[-1]}, {size:,} bytes)')

    with open(os.path.join(J, 'swiss-igs.csv'), 'w') as f:
        f.write('profile,bodyfile,ig,name\n')
        for r in rows:
            f.write(','.join(r) + '\n')

    lines = ['# generated by swiss_igs.py: the latest Swiss IGs from fhir.ch (package-registry.json), H2',
             'server:', '  servlet:', '    context-path: /matchboxv3',
             'spring:', '  datasource:', "    url: 'jdbc:h2:file:./database/h2'", '    username: sa',
             '    password: null', '    driverClassName: org.h2.Driver',
             '  jpa:', '    properties:', '      hibernate.dialect: ca.uhn.fhir.jpa.model.dialect.HapiFhirH2Dialect',
             'hapi:', '  fhir:', '    implementationguides:']
    for i, (pid, version, _, _) in enumerate(igs):
        lines += [f'      ig{i:02d}:', f'        name: {pid}', f'        version: {version}']
    with open(os.path.join(J, 'swiss-igs.yaml'), 'w') as f:
        f.write('\n'.join(lines) + '\n')

    print(f'\n{len(igs)} IGs installed, {len(rows)} with an example (swiss-igs.csv)')
    if without:
        print('installed, but no example with a profile of the IG: ' + ', '.join(without))


def report(out):
    """Per validation: the IG, HTTP status, time, errors in the OperationOutcome, live heap and container memory."""
    import csv
    rows = list(csv.DictReader(open(os.path.join(out, 'test.jtl'))))
    print(f'{"#":>3} {"IG":45} {"HTTP":>4} {"time s":>7} {"errors":>6} {"live heap MB":>12} {"committed MB":>12} '
          f'{"container":>10}')
    step, first_fail, n_ok = 0, None, 0
    validate = None
    for r in rows:
        if r['label'] == '$validate':
            validate = r
            continue
        if r['label'] != 'live heap' or validate is None:
            continue
        step += 1
        ok = validate['success'] == 'true'
        n_ok += ok
        if not ok and first_fail is None:
            first_fail = (step, validate['name'])
        heap = r['liveheapkb']
        heap = f'{int(heap) / 1024:,.0f}' if heap.isdigit() else '-'
        committed = r.get('heapcommittedkb', '')
        committed = f'{int(committed) / 1024:,.0f}' if committed.isdigit() else '-'
        errors = validate['errors_matchNr'] if validate['errors_matchNr'] not in ('', 'null') else '-'
        print(f'{step:>3} {validate["name"]:45} {validate["responseCode"][:4]:>4} '
              f'{int(validate["elapsed"]) / 1000:7.1f} {errors:>6} {heap:>12} {committed:>12} '
              f'{r["containermem"]:>10}')
        validate = None
    print(f'{n_ok} of {step} validations passed' +
          (f'; first failure at #{first_fail[0]} ({first_fail[1]})' if first_fail else ''))


if __name__ == '__main__':
    if len(sys.argv) > 2 and sys.argv[1] == 'report':
        report(sys.argv[2])
    else:
        main()
