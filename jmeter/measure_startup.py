#!/usr/bin/env python3
"""Measure the startup time, the first and the second validation of a matchbox-ch-elm image.

For each run a fresh container is started on port 8080; the script measures
  - the time from `docker run` until /actuator/health reports UP,
  - the time to create the ch-elm validation engine (from the container log),
  - the client time and the server's validation time of the first, second and third $validate call
    (the request of memory.jmx),
  - the live heap after a full GC once the validations are done.
The container is removed after each run. See claude-jmeter-check.md.

usage: measure_startup.py <image> <label> [runs] [JDK_JAVA_OPTIONS]
"""
import csv
import datetime
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ET

BASE = 'http://localhost:8080/matchboxv3'
HERE = os.path.dirname(os.path.abspath(__file__))


def sh(*args, check=True):
    return subprocess.run(args, capture_output=True, text=True, check=check).stdout


def validate_request():
    tree = ET.parse(os.path.join(HERE, 'memory.jmx'))
    for sampler in tree.iter('HTTPSamplerProxy'):
        if sampler.get('testname') == '$validate':
            props = {e.get('name'): e.text for e in sampler.iter('stringProp')}
            return props['HTTPSampler.path'].replace('${host}', ''), props['Argument.value']
    raise SystemExit('no $validate sampler in memory.jmx')


def validate(path, body):
    request = urllib.request.Request(BASE + path, data=body.encode(), headers={
        'Content-Type': 'application/fhir+json', 'Accept': 'application/fhir+json'})
    start = time.time()
    with urllib.request.urlopen(request, timeout=600) as response:
        outcome = json.load(response)
    client_ms = (time.time() - start) * 1000
    server_ms = None
    for ext in outcome['issue'][0].get('extension', [{}])[0].get('extension', []):
        if ext['url'] == 'total':
            server_ms = ext['valueDuration']['value']
    errors = sum(1 for i in outcome['issue'] if i['severity'] in ('error', 'fatal'))
    return round(client_ms), server_ms, len(outcome['issue']), errors


def log_time(log, pattern):
    match = re.search(r'^(\S+ \S+)\s.*' + pattern, log, re.M)
    return datetime.datetime.strptime(match.group(1), '%Y-%m-%d %H:%M:%S.%f') if match else None


def live_heap_mb(name):
    for _ in range(2):
        sh('docker', 'exec', name, 'jcmd', '1', 'GC.run')
        time.sleep(10)
    used = re.search(r'used (\d+)K', sh('docker', 'exec', name, 'jcmd', '1', 'GC.heap_info'))
    return round(int(used.group(1)) / 1024)


def run(image, label, index, java_options, path, body):
    name = f'matchbox-measure-{label}-{index}'
    sh('docker', 'rm', '-f', name, check=False)
    args = ['docker', 'run', '-d', '--name', name, '-p', '8080:80']
    if java_options:
        args += ['-e', f'JDK_JAVA_OPTIONS={java_options}']
    start = time.time()
    sh(*args, image)
    while True:
        try:
            with urllib.request.urlopen(BASE + '/actuator/health', timeout=5) as response:
                if b'UP' in response.read():
                    break
        except Exception:
            pass
        if sh('docker', 'inspect', '-f', '{{.State.Status}}', name).strip() != 'running':
            raise SystemExit(f'{name} stopped:\n' + sh('docker', 'logs', '--tail', '30', name))
        time.sleep(0.25)
    health_s = time.time() - start
    log = subprocess.run(['docker', 'logs', name], capture_output=True, text=True).stdout
    created = log_time(log, r'Creating new validate engine for ch\.fhir\.ig\.ch-elm')
    cached = log_time(log, r'Cached validate engine forever for ch\.fhir\.ig\.ch-elm')
    engine_s = (cached - created).total_seconds() if created and cached else None
    started = re.search(r'Started Application in ([\d.]+) seconds', log)
    heap_start = live_heap_mb(name)
    v1, v2, v3 = (validate(path, body) for _ in range(3))
    heap_after = live_heap_mb(name)
    sh('docker', 'rm', '-f', name)
    return {'label': label, 'run': index, 'health_s': round(health_s, 1),
            'spring_started_s': started.group(1) if started else None,
            'engine_ch_elm_s': engine_s,
            'v1_client_ms': v1[0], 'v1_server_ms': v1[1], 'v2_client_ms': v2[0], 'v2_server_ms': v2[1],
            'v3_client_ms': v3[0], 'v3_server_ms': v3[1], 'issues': v1[2], 'errors': v1[3],
            'issues_v3': v3[2], 'live_heap_start_mb': heap_start, 'live_heap_after_mb': heap_after}


def main():
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    image, label = sys.argv[1], sys.argv[2]
    runs = int(sys.argv[3]) if len(sys.argv) > 3 else 3
    java_options = sys.argv[4] if len(sys.argv) > 4 else None
    path, body = validate_request()
    out = os.path.join(HERE, f'startup-{label}.csv')
    rows = []
    for index in range(1, runs + 1):
        row = run(image, label, index, java_options, path, body)
        print(row, flush=True)
        rows.append(row)
    with open(out, 'w', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    print('written', out)


if __name__ == '__main__':
    main()
