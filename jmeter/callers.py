"""Callers of a method in an async-profiler collapsed file (request threads): the caller frame, and the innermost
frame matching --context (e.g. the validator or matchbox frame that led there).

    python3 callers.py profile-fhir-core-7.collapsed 'JsonParserBase\\.compose$' [--context 'InstanceValidator|ch\\.ahdis'] [--depth 3]
"""
import argparse
import collections
import re

ap = argparse.ArgumentParser()
ap.add_argument('file')
ap.add_argument('method')
ap.add_argument('--context', default=r'org\.hl7\.fhir\.(validation|services|standalone)|ch\.ahdis')
ap.add_argument('--depth', type=int, default=3, help='caller frames to show')
ap.add_argument('--interval', type=float, default=5.0)
ap.add_argument('--requests', type=int, default=8000)
args = ap.parse_args()
rx, ctx = re.compile(args.method), re.compile(args.context)
callers, contexts = collections.Counter(), collections.Counter()
total = 0
for line in open(args.file):
    if not line.startswith('[http-nio'):
        continue
    stack, _, n = line.rstrip('\n').rpartition(' ')
    frames = [re.sub(r'_\[.\]$', '', f).replace('/', '.') for f in stack.split(';')[1:]]
    # outermost occurrence of the method
    idx = next((i for i, f in enumerate(frames) if rx.search(f)), None)
    if idx is None:
        continue
    n = int(n)
    total += n
    callers[' < '.join(reversed(frames[max(0, idx - args.depth):idx]))] += n
    c = next((frames[i] for i in range(idx - 1, -1, -1) if ctx.search(frames[i]) and not rx.search(frames[i])), '?')
    contexts[c] += n
ms = args.interval / args.requests
print(f'{args.method}: {total * ms:.2f} ms/req')
print('\ncallers:')
for k, v in callers.most_common(15):
    print(f'  {v * ms:6.2f}  {k}')
print(f'\ninnermost context frame ({args.context}):')
for k, v in contexts.most_common(15):
    print(f'  {v * ms:6.2f}  {k}')
