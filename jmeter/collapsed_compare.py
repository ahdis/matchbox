"""Compare two async-profiler collapsed stack files (asprof ... --threads -o collapsed), request threads only.

    python3 collapsed_compare.py profile-pr636.collapsed profile-fhir-core-7.collapsed [--interval 5] [--requests 8000]

The first file is the baseline (core 6, r5 names, mapped onto the R6 names as in jfr_compare.py). Unlike JFR's
ExecutionSample, async-profiler also samples the time a thread spends in the JVM runtime (C++ frames such as
ObjectMonitor::enter, OptoRuntime::new_instance_C), in native code and in the kernel.
"""
import argparse
import collections
import functools
import re

from jfr_compare import normalize as _normalize, package, table


@functools.lru_cache(maxsize=None)
def normalize(frame, baseline):
    return _normalize(re.sub(r'_\[.\]$', '', frame).replace('/', '.'), baseline)


def kind(frame):
    if '::' in frame or frame.startswith('JVM_') or frame.startswith('Unsafe_') or frame.startswith('JNI_'):
        return 'jvm'
    if '.' in frame or '/' in frame:
        return 'java'
    return 'native'  # C library, kernel, stubs without a name


def load(path, baseline, thread_rx):
    incl, self_, vm_by_java, vm_top, kinds = (collections.Counter() for _ in range(5))
    total = 0
    for line in open(path):
        stack, _, n = line.rstrip('\n').rpartition(' ')
        n = int(n)
        frames = stack.split(';')
        if not frames or not re.match(thread_rx, frames[0].strip('[')):
            continue
        frames = [normalize(f, baseline) for f in frames[1:]]
        if not frames:
            continue
        total += n
        for f in set(frames):
            incl[f] += n
        top = frames[-1]
        self_[top] += n
        k = kind(top)
        kinds[k] += n
        if k != 'java':
            vm_top[top] += n
            # the innermost Java frame that called into the JVM / native code
            for f in reversed(frames):
                if kind(f) == 'java':
                    vm_by_java[f + '  →  ' + k] += n
                    break
    return dict(incl=incl, self=self_, vm_by_java=vm_by_java, vm_top=vm_top, kinds=kinds, total=total)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('base')
    ap.add_argument('new')
    ap.add_argument('--interval', type=float, default=5.0, help='sampling interval in ms')
    ap.add_argument('--requests', type=int, default=8000)
    ap.add_argument('--threads', default=r'http-nio-\d+-exec', help='regex for the thread names')
    ap.add_argument('--top', type=int, default=40)
    ap.add_argument('--grep')
    args = ap.parse_args()
    A, B = load(args.base, True, args.threads), load(args.new, False, args.threads)
    na, nb = args.base.replace('.collapsed', ''), args.new.replace('.collapsed', '')
    ms = args.interval / args.requests
    print(f'CPU per request on the matching threads: {A["total"] * ms:.2f} vs {B["total"] * ms:.2f} ms '
          f'({(B["total"] - A["total"]) * ms:+.2f})')
    if args.grep:
        rx = re.compile(args.grep)
        table(f'inclusive matching {args.grep}', na, nb, {k: v for k, v in A['incl'].items() if rx.search(k)},
              {k: v for k, v in B['incl'].items() if rx.search(k)}, ms, 'ms/req', args.top)
        return
    table('CPU by kind of the top frame', na, nb, A['kinds'], B['kinds'], ms, 'ms/req', 5)
    pa, pb = collections.Counter(), collections.Counter()
    for k, v in A['self'].items():
        pa[package(k) if kind(k) == 'java' else kind(k)] += v
    for k, v in B['self'].items():
        pb[package(k) if kind(k) == 'java' else kind(k)] += v
    table('CPU self by package', na, nb, pa, pb, ms, 'ms/req', 30)
    table('JVM / native top frames', na, nb, A['vm_top'], B['vm_top'], ms, 'ms/req', args.top)
    table('JVM / native time by the calling Java frame', na, nb, A['vm_by_java'], B['vm_by_java'], ms, 'ms/req',
          args.top)
    table('CPU inclusive per method', na, nb, A['incl'], B['incl'], ms, 'ms/req', args.top, 0.3)
    table('CPU self per method', na, nb, A['self'], B['self'], ms, 'ms/req', args.top)


if __name__ == '__main__':
    main()
