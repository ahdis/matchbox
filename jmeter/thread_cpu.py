"""CPU time per thread group during a load test, from two `jcmd 1 Thread.print` dumps (before and after the run).

    python3 thread_cpu.py --requests 8000 cpu-<run>   [cpu-<run> ...]

Reads cpu-<run>.before.txt, cpu-<run>.after.txt and cpu-<run>.time (start and end epoch seconds) and prints the CPU
ms per request of each thread group (thread names with the digits removed), plus the wall-clock time of the run.
"""
import argparse
import collections
import re

LINE = re.compile(r'^"(?P<name>[^"]+)".*?cpu=(?P<cpu>[\d.]+)ms')


def dump(path):
    threads = {}
    for line in open(path):
        m = LINE.match(line)
        if m:
            # name and the thread address keep threads with the same name apart
            tid = re.search(r'(tid|nid)=(\S+)', line)
            threads[(m['name'], tid[2] if tid else '')] = float(m['cpu'])
    return threads


def group(name):
    # match the prefixes before the digits are removed, 'G1 ' would be 'GN ' afterwards
    if name.startswith('GC Thread') or name.startswith('G1 '):
        return 'GC (G1 workers, conc, refine)'
    if name.startswith('C2 CompilerThread') or name.startswith('C1 CompilerThread'):
        return 'JIT compiler'
    return re.sub(r'\d+', 'N', name)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('runs', nargs='+')
    ap.add_argument('--requests', type=int, default=8000)
    args = ap.parse_args()
    results = {}
    for run in args.runs:
        before, after = dump(run + '.before.txt'), dump(run + '.after.txt')
        g = collections.Counter()
        for k, cpu in after.items():
            g[group(k[0])] += cpu - before.get(k, 0.0)
        start, end = [int(x) for x in open(run + '.time').read().split()]
        g['(wall-clock of the run, s)'] = (end - start) * 1000 * args.requests  # printed as s below
        results[run] = g
    groups = sorted({k for g in results.values() for k in g}, key=lambda k: -max(r[k] for r in results.values()))
    print('| CPU ms per request | ' + ' | '.join(args.runs) + ' |')
    print('|---|' + '---:|' * len(args.runs))
    total = collections.Counter()
    for k in groups:
        vals = [results[r][k] / args.requests for r in args.runs]
        if k.startswith('(wall'):
            continue
        if max(vals) < 0.05:
            for r in args.runs:
                total[r] += results[r][k] / args.requests
            continue
        print(f'| `{k}` | ' + ' | '.join(f'{v:.2f}' for v in vals) + ' |')
        for r in args.runs:
            total[r] += results[r][k] / args.requests
    print('| all threads | ' + ' | '.join(f'{total[r]:.2f}' for r in args.runs) + ' |')
    print('| wall-clock of the run (s) | ' + ' | '.join(
        f'{results[r]["(wall-clock of the run, s)"] / 1000 / args.requests:.0f}' for r in args.runs) + ' |')


if __name__ == '__main__':
    main()
