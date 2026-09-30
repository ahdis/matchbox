"""Validates an R4 core Patient (main engine, which exists from the start) at a fixed interval until it's stopped, to
see whether the requests for an existing engine wait while other engines are created (#616). Writes one line per
validation: the start (epoch ms), the time it took (ms) and the HTTP status.

usage: python3 probe.py <base url> <body file> <output csv> [<interval ms>]
"""
import http.client
import sys
import time
import urllib.parse

base, body_file, output = sys.argv[1:4]
interval = (int(sys.argv[4]) if len(sys.argv) > 4 else 200) / 1000
body = open(body_file, 'rb').read()
url = urllib.parse.urlparse(base)
path = url.path + '/fhir/$validate?profile=http://hl7.org/fhir/StructureDefinition/Patient'
with open(output, 'w') as out:
    out.write('timeStamp,elapsed,status\n')
    while True:
        start = time.time()
        try:
            connection = http.client.HTTPConnection(url.hostname, url.port or 80, timeout=120)
            connection.request('POST', path, body, {'Content-Type': 'application/fhir+json',
                                                    'Accept': 'application/fhir+json'})
            response = connection.getresponse()
            response.read()
            status = response.status
            connection.close()
        except Exception as e:
            status = type(e).__name__
        elapsed = time.time() - start
        out.write(f'{int(start * 1000)},{int(elapsed * 1000)},{status}\n')
        out.flush()
        time.sleep(max(0.0, interval - elapsed))
