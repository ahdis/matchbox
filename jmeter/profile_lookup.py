"""Validation time of a profile that isn't in the main engine, with and without the `ig` parameter, by one client and
by several parallel clients (#616). Without `ig`, matchbox looks up the IG of the profile; with `ig`, it doesn't. The
engine of the IG must exist already (e.g. after multi-ig.jmx), the requests only measure the steady state.

usage: python3 profile_lookup.py <base url> <profile> <body file> <ig> [<validations per case> [<parallel clients>]]
  e.g. python3 profile_lookup.py http://localhost:8080/matchboxv3 \
         http://fhir.ch/ig/ch-core/StructureDefinition/ch-core-practitioner preload-Practitioner-SchreibKraft.json \
         ch.fhir.ig.ch-core#6.0.0 200 4
Prints a table and writes it as JSON to stdout's last line (prefixed with 'json: ') for engine_creation.py.
"""
import concurrent.futures
import http.client
import json
import statistics
import sys
import time
import urllib.parse


def validate(connection, path, body):
    start = time.perf_counter()
    connection.request('POST', path, body, {'Content-Type': 'application/fhir+json', 'Accept': 'application/fhir+json'})
    response = connection.getresponse()
    content = response.read()
    elapsed = (time.perf_counter() - start) * 1000
    if response.status != 200 or b'"resourceType":"OperationOutcome"' not in content.replace(b' ', b''):
        raise RuntimeError(f'{response.status}: {content[:300]}')
    return elapsed


def client(base, path, body, count):
    url = urllib.parse.urlparse(base)
    connection = http.client.HTTPConnection(url.hostname, url.port or 80, timeout=120)
    try:
        return [validate(connection, path, body) for _ in range(count)]
    finally:
        connection.close()


def run(base, path, body, total, clients):
    client(base, path, body, 5)  # warm-up
    start = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(clients) as executor:
        times = [t for f in [executor.submit(client, base, path, body, total // clients) for _ in range(clients)]
                 for t in f.result()]
    seconds = time.perf_counter() - start
    times.sort()
    return dict(median=round(statistics.median(times), 1), p95=round(times[int(.95 * len(times))], 1),
                throughput=round(len(times) / seconds, 1))


def main():
    base, profile, body_file, ig = sys.argv[1:5]
    total = int(sys.argv[5]) if len(sys.argv) > 5 else 200
    parallel = int(sys.argv[6]) if len(sys.argv) > 6 else 4
    body = open(body_file, 'rb').read()
    context = urllib.parse.urlparse(base).path
    cases = {'profile': f'{context}/fhir/$validate?profile={urllib.parse.quote(profile, safe=":/")}'}
    cases['profile and ig'] = cases['profile'] + '&ig=' + urllib.parse.quote(ig)
    results = {}
    for name, path in cases.items():
        for clients in (1, parallel):
            results[f'{name}, {clients} client{"s" if clients > 1 else ""}'] = run(base, path, body, total, clients)
    for case, r in results.items():
        print(f'{case:28} median {r["median"]:6.1f} ms  p95 {r["p95"]:6.1f} ms  {r["throughput"]:6.1f} validations/s')
    print('json: ' + json.dumps(results))


if __name__ == '__main__':
    main()
