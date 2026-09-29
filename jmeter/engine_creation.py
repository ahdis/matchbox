"""Compares the runs of preload_engines.sh: engine creation per IG (from the server log), the first validation per IG
(including the engine creation and the waiting for other engines), the validation time once the engines exist, and
whether the validation issues are the same in all runs.

usage: python3 engine_creation.py <run folder> [<run folder> ...]
"""
import collections
import csv
import datetime
import json
import os
import re
import statistics as s
import sys
import urllib.parse


def load_jtl(path):
    rows = [r for r in csv.DictReader(open(path)) if r['label'] == '$validate']
    rows.sort(key=lambda r: int(r['timeStamp']))
    by = collections.defaultdict(list)
    for r in rows:
        by[urllib.parse.parse_qs(urllib.parse.urlparse(r['URL']).query)['profile'][0]].append(r)
    # the first validation per IG profile (R4 core profiles are validated by the main engine)
    first = sorted(int(v[0]['elapsed']) for k, v in by.items() if not k.startswith('http://hl7.org/fhir/StructureDefinition/'))
    steady = sorted(int(r['elapsed']) for v in by.values() for r in v[3:])
    server = [float(r['validationms']) for v in by.values() for r in v[3:] if r['validationms'] not in ('', 'null')]
    return dict(n=len(rows), fails=sum(r['success'] != 'true' for r in rows), first=first, steady=steady,
                server=server)


def engines(log):
    """Engine creation per IG: from 'Creating new cached validate engine' to 'Terminology server' (the end of
    MatchboxEngineSupport.createMatchboxEngine()) in the same thread."""
    lines = [l for l in open(log) if 'box.util.MatchboxEngineSupport' in l]
    t = lambda l: datetime.datetime.strptime(l[:23], '%Y-%m-%d %H:%M:%S.%f')
    thread = lambda l: l[l.index('['):l.index(']')]
    out = {}
    for i, l in enumerate(lines):
        m = re.search(r'Creating new cached validate engine for (\S+)', l)
        if m:
            end = next(x for x in lines[i + 1:] if 'Terminology server' in x and thread(x) == thread(l))
            out[m.group(1)] = (t(end) - t(l)).total_seconds()
    return out


def issues(folder):
    """The issues per profile, without the first issue (the parameters and duration of the validation)."""
    out = {}
    for f in sorted(os.listdir(folder)):
        if f.startswith('oo-') and f.endswith('.json'):
            oo = json.load(open(os.path.join(folder, f)))
            out[f] = [(i.get('severity'), i.get('code'), tuple(i.get('expression', [])), i.get('details', {}).get('text',
                       i.get('diagnostics'))) for i in oo.get('issue', [])[1:]]
    return out


def p95(values):
    return values[int(.95 * len(values))]


runs = [os.path.normpath(f) for f in sys.argv[1:]]
all_engines = {}
for run in runs:
    name = os.path.basename(run)
    d = load_jtl(os.path.join(run, 'test.jtl'))
    e = engines(os.path.join(run, 'server.log'))
    all_engines[name] = e
    v = sorted(e.values())
    print(f"{name:20} validations={d['n']} fails={d['fails']} | engine creation median {s.median(v):.1f} max "
          f"{v[-1]:.1f} total {sum(v):.0f} s ({len(v)} engines) | first validation per IG median "
          f"{s.median(d['first']) / 1000:.1f} max {d['first'][-1] / 1000:.1f} s | then median {s.median(d['steady'])} "
          f"p95 {p95(d['steady'])} ms, server {s.median(d['server']):.0f} ms")

print('\nengine creation per IG (s)\n' + ' ' * 34 + ' '.join(f'{os.path.basename(r)[-10:]:>10}' for r in runs))
for ig in sorted({k for e in all_engines.values() for k in e}):
    print(f'{ig:34}' + ' '.join(f"{all_engines[os.path.basename(r)].get(ig, 0):10.1f}" for r in runs))

reference = issues(runs[0])
for run in runs[1:]:
    other = issues(run)
    diff = [f for f in reference if reference[f] != other.get(f)]
    print(f"\nissues of {os.path.basename(run)} vs {os.path.basename(runs[0])}: "
          + ('same' if not diff else 'different in ' + ', '.join(diff)))
