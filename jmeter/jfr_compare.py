"""Compare two JFR aggregates written by JfrAgg.java (see profile-analysis-plan.md, step 3).

    python3 jfr_compare.py profile-pr636.agg.tsv profile-fhir-core-7.agg.tsv [--requests 8000] [--period 10] [--top 50]

The first file is the baseline (org.hl7.fhir.core 6.x, r5 packages); its method names are mapped onto the
org.hl7.fhir.core 7 (R6) packages so the same method has the same key in both files (appendix of r5-r6.md).
The sample counts are converted to ms of CPU per request: samples * period / requests. JFR samples only threads that
run Java code, so this is CPU time (on the 4 request threads), not wall-clock time.
"""
import argparse
import collections
import re

# r5 (core 6) -> R6 (core 7) package and class renames, most specific first
RENAMES = [
    (r'^org\.hl7\.fhir\.r5\.model\.Base\.ValidationInfo\b', 'org.hl7.fhir.model.ValidationInformation'),
    (r'^org\.hl7\.fhir\.r5\.model\.(Base|Property|Factory|LogicalBase)\.', r'org.hl7.fhir.model.\1.'),
    (r'^org\.hl7\.fhir\.r5\.model\.', 'org.hl7.fhir.model.core.'),
    (r'^org\.hl7\.fhir\.r5\.formats\.(IParser|FormatUtilities)\b', r'org.hl7.fhir.model.utilities.formats.\1'),
    (r'^org\.hl7\.fhir\.r5\.formats\.', 'org.hl7.fhir.model.core.formats.'),
    (r'^org\.hl7\.fhir\.r5\.extensions\.', 'org.hl7.fhir.model.extensions.'),
    (r'^org\.hl7\.fhir\.r5\.utils\.(ResourceUtilities|EOperationOutcome)\b', r'org.hl7.fhir.model.utilities.\1'),
    (r'^org\.hl7\.fhir\.r5\.terminologies\.(CodeSystemUtilities|ValueSetUtilities|TerminologyUtilities)\b',
     r'org.hl7.fhir.model.utilities.\1'),
    (r'^org\.hl7\.fhir\.r5\.utils\.structuremap\.StructureMapUtilities\b', 'org.hl7.fhir.services.fml.StructureMapTools'),
    (r'^org\.hl7\.fhir\.r5\.tools\.', 'org.hl7.fhir.model.tools.'),
    (r'^org\.hl7\.fhir\.r5\.utils\.(validation|client|xver|sql)\.', r'org.hl7.fhir.services.\1.'),
    (r'^org\.hl7\.fhir\.r5\.utils\.', 'org.hl7.fhir.services.utilities.'),
    (r'^org\.hl7\.fhir\.r5\.context\.IWorkerContext\b', 'org.hl7.fhir.services.context.IWorkerContext'),
    (r'^org\.hl7\.fhir\.r5\.context\.ContextUtilities\b', 'org.hl7.fhir.services.context.ContextUtilities'),
    (r'^org\.hl7\.fhir\.r5\.context\.', 'org.hl7.fhir.standalone.context.'),
    (r'^org\.hl7\.fhir\.r5\.terminologies\.(expansion|providers|validation|subsumption|client)\.',
     r'org.hl7.fhir.standalone.terminology.\1.'),
    (r'^org\.hl7\.fhir\.r5\.terminologies\.utilities\.(ValidationResult|CodingValidationRequest)\b',
     r'org.hl7.fhir.services.terminology.\1'),
    (r'^org\.hl7\.fhir\.r5\.terminologies\.utilities\.(VCLParser|SnomedUtilities|ImplicitCodeSystemSupport|VersionAlgorithm|TerminologyServiceErrorClass)\b',
     r'org.hl7.fhir.model.utilities.\1'),
    (r'^org\.hl7\.fhir\.r5\.terminologies\.utilities\.', 'org.hl7.fhir.standalone.terminology.utilities.'),
    (r'^org\.hl7\.fhir\.r5\.terminologies\.', 'org.hl7.fhir.services.terminology.'),
    (r'^org\.hl7\.fhir\.r5\.(fhirpath|elementmodel|conformance|renderers|comparison|profilemodel|openapi|liquid|testfactory)\.',
     r'org.hl7.fhir.services.\1.'),
]
RENAMES = [(re.compile(a), b) for a, b in RENAMES]

PACKAGES = [
    'ch.ahdis', 'ca.uhn', 'org.hl7.fhir.validation', 'org.hl7.fhir.services.fhirpath',
    'org.hl7.fhir.services.elementmodel', 'org.hl7.fhir.services.conformance', 'org.hl7.fhir.services.terminology',
    'org.hl7.fhir.services', 'org.hl7.fhir.standalone.terminology', 'org.hl7.fhir.standalone.context',
    'org.hl7.fhir.standalone', 'org.hl7.fhir.model.core', 'org.hl7.fhir.model', 'org.hl7.fhir.utilities',
    'org.hl7.fhir.r4', 'org.hl7.fhir', 'com.fasterxml', 'org.apache', 'org.springframework', 'java.util.regex',
    'java.util', 'java.lang', 'java', 'jdk', 'sun',
]


def normalize(name, baseline):
    # lambdas and hidden classes: numbering differs between builds
    name = re.sub(r'\$\$(Lambda|StringConcat)[./]0x[0-9a-f]+', r'$$\1', name)
    name = re.sub(r'LambdaForm\$(\w+)[./]0x[0-9a-f]+', r'LambdaForm$\1', name)
    name = re.sub(r'lambda\$(\w+?)\$\d+', r'lambda$\1', name)
    name = re.sub(r'\$\d+\.', '$N.', name)  # anonymous classes
    if baseline:
        for rx, repl in RENAMES:
            new = rx.sub(repl, name)
            if new != name:
                return new
    return name


def package(name):
    for p in PACKAGES:
        if name.startswith(p + '.'):
            return p
    return 'other'


def load(path, baseline):
    d = collections.defaultdict(lambda: collections.defaultdict(lambda: [0, 0]))
    samples = truncated = 0
    for line in open(path):
        f = line.rstrip('\n').split('\t')
        kind = f[0]
        if kind == 'SAMPLES':
            samples, truncated = int(f[1]), int(f[2])
        elif kind == 'CPU':
            k = normalize(f[1], baseline)
            # max: after normalizing, two lambdas of one method may fall together; inclusive isn't additive
            d['CPU'][k][0] = max(d['CPU'][k][0], int(f[2]))
            d['CPU'][k][1] += int(f[3])
        elif kind in ('APPSELF', 'ALLOCCLASS', 'THREADS', 'NATIVE', 'NATIVETOP'):
            k = normalize(f[1], baseline)
            d[kind][k][0] += int(f[2])
        elif kind == 'ALLOC':
            k = normalize(f[1], baseline)
            d[kind][k][0] = max(d[kind][k][0], int(f[2]))
        elif kind == 'EVENT':
            d[kind][f[1]][0] += int(f[2])
            d[kind][f[1]][1] += int(f[3])
        elif kind in ('MONITOR', 'PARK'):
            k = '\t'.join([f[1], normalize(f[2], baseline), normalize(f[3], baseline)])
            d[kind][k][0] += int(f[4])
            d[kind][k][1] += int(f[5])
    return d, samples, truncated


def table(title, a, b, key_a, key_b, scale, unit, top, min_abs=0.0):
    keys = set(key_a) | set(key_b)
    rows = []
    for k in keys:
        va, vb = key_a.get(k, 0) * scale, key_b.get(k, 0) * scale
        if max(va, vb) < min_abs:
            continue
        rows.append((vb - va, va, vb, k))
    rows.sort(key=lambda r: -abs(r[0]))
    print(f'\n## {title}\n')
    print(f'| {a} {unit} | {b} {unit} | diff | |')
    print('|---:|---:|---:|---|')
    for diff, va, vb, k in rows[:top]:
        print(f'| {va:.2f} | {vb:.2f} | {diff:+.2f} | `{k}` |')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('base')
    ap.add_argument('new')
    ap.add_argument('--requests', type=int, default=8000)
    ap.add_argument('--period', type=float, default=10.0, help='jdk.ExecutionSample period in ms')
    ap.add_argument('--top', type=int, default=50)
    ap.add_argument('--grep', help='only methods matching this regex (CPU inclusive)')
    args = ap.parse_args()

    A, sa, ta = load(args.base, True)
    B, sb, tb = load(args.new, False)
    na, nb = args.base.replace('.agg.tsv', ''), args.new.replace('.agg.tsv', '')
    ms = args.period / args.requests  # one sample in ms of CPU per request

    print(f'request thread samples: {na} {sa} (truncated stacks {ta}), {nb} {sb} (truncated stacks {tb})')
    print(f'CPU per request on the request threads: {sa * ms:.2f} vs {sb * ms:.2f} ms ({(sb - sa) * ms:+.2f})')

    print('\n## Samples per thread\n')
    for k in sorted(set(A['THREADS']) | set(B['THREADS']), key=lambda k: -B['THREADS'].get(k, [0])[0]):
        print(f'- `{k}`: {A["THREADS"].get(k, [0])[0]} / {B["THREADS"].get(k, [0])[0]}')

    def col(d, kind, i, keys=None):
        return {k: v[i] for k, v in d[kind].items() if keys is None or re.search(keys, k)}

    if args.grep:
        table(f'CPU inclusive matching {args.grep}', na, nb, col(A, 'CPU', 0, args.grep), col(B, 'CPU', 0, args.grep),
              ms, 'ms/req', args.top)
        return

    # self time by package
    pa, pb = collections.Counter(), collections.Counter()
    for k, v in A['CPU'].items():
        pa[package(k)] += v[1]
    for k, v in B['CPU'].items():
        pb[package(k)] += v[1]
    table('CPU self time by package', na, nb, pa, pb, ms, 'ms/req', 40)

    # self time attributed to the first frame outside of the JDK, by package
    pa, pb = collections.Counter(), collections.Counter()
    for k, v in A['APPSELF'].items():
        pa[package(k)] += v[0]
    for k, v in B['APPSELF'].items():
        pb[package(k)] += v[0]
    table('CPU self time (JDK frames counted to the calling app frame) by package', na, nb, pa, pb, ms, 'ms/req', 40)

    table('CPU inclusive per method, largest differences', na, nb, col(A, 'CPU', 0), col(B, 'CPU', 0), ms, 'ms/req',
          args.top, 0.3)
    table('CPU self per method, largest differences', na, nb, col(A, 'CPU', 1), col(B, 'CPU', 1), ms, 'ms/req',
          args.top)
    table('CPU self per first app frame, largest differences', na, nb, col(A, 'APPSELF', 0), col(B, 'APPSELF', 0), ms,
          'ms/req', args.top)

    kb = 1 / 1024 / args.requests
    table('Allocation per class (request threads)', na, nb, col(A, 'ALLOCCLASS', 0), col(B, 'ALLOCCLASS', 0), kb,
          'KiB/req', 30)
    table('Allocation inclusive per method (request threads)', na, nb, col(A, 'ALLOC', 0), col(B, 'ALLOC', 0), kb,
          'KiB/req', args.top, 50)
    print(f'\nallocated per request (sampled weight): '
          f'{sum(col(A, "ALLOCCLASS", 0).values()) * kb:.0f} vs {sum(col(B, "ALLOCCLASS", 0).values()) * kb:.0f} KiB')

    table('Native method samples of the request threads, first app frame', na, nb, col(A, 'NATIVE', 0),
          col(B, 'NATIVE', 0), 20.0 / args.requests, 'ms/req', 10)

    print('\n## Events (count / total ms)\n')
    for k in sorted(set(A['EVENT']) | set(B['EVENT'])):
        if re.search(r'GC|Garbage|Safepoint|Compil|Deoptim|Allocation|Monitor|Park|Pinned|Tenuring|Promotion', k):
            a, b = A['EVENT'].get(k, [0, 0]), B['EVENT'].get(k, [0, 0])
            print(f'- `{k}`: {a[0]} / {a[1]} ms vs {b[0]} / {b[1]} ms')
    for kind in ('MONITOR', 'PARK'):
        print(f'\n## {kind} (count / total ms; thread, class, first non-JDK frame)\n')
        keys = sorted(set(A[kind]) | set(B[kind]), key=lambda k: -max(A[kind].get(k, [0, 0])[1], B[kind].get(k, [0, 0])[1]))
        for k in keys[:15]:
            a, b = A[kind].get(k, [0, 0]), B[kind].get(k, [0, 0])
            print(f'- `{k}`: {a[0]} / {a[1]} ms vs {b[0]} / {b[1]} ms')


if __name__ == '__main__':
    main()
