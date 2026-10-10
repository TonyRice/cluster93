#!/usr/bin/env python3
"""Turns run.sh's results.jsonl into the comparison's tables (D-126 §8)."""
import json
import os
import statistics
import sys
from collections import defaultdict

rows = [json.loads(line) for line in open(sys.argv[1]) if line.strip().startswith('{')]
engines = []
for r in rows:
    if r.get('engine') and r['engine'] not in engines:
        engines.append(r['engine'])

def table(header, body):
    out = ['| ' + ' | '.join(header) + ' |', '|' + '---|' * len(header)]
    out += ['| ' + ' | '.join(str(c) for c in line) + ' |' for line in body]
    return '\n'.join(out)

# The machine's state during the run: throttling would make the timings unreliable.
machine_path = os.path.join(os.path.dirname(sys.argv[1]), 'machine.json')
if os.path.exists(machine_path):
    try:
        m = json.load(open(machine_path))
        edition = m.get('graalEdition', 'unknown')
        version = m.get('graalVersion', '')
        print(f"**GraalVM Environment:** {edition} ({version})\n")
    except Exception:
        pass

thermal = os.path.join(os.path.dirname(sys.argv[1]), 'thermal.csv')
if os.path.exists(thermal):
    samples = [line.split(',') for line in open(thermal).read().split()[1:]]
    temps = [float(t[1]) for t in samples if len(t) == 4 and t[1]]
    mhz = [float(t[2]) for t in samples if len(t) == 4 and t[2]]
    if temps and mhz:
        print(f'CPU during the run: package {statistics.median(temps):.0f}°C median, {max(temps):.0f}°C max; '
              f'mean clock {statistics.median(mhz):.0f} MHz median, {min(mhz):.0f} MHz lowest ({len(samples)} samples, every 5 s).\n')

print('## Start-up (native-image worker, first script answered)\n')
start = defaultdict(list)
for r in rows:
    if 'msToFirstScript' in r:
        start[r['engine']].append(r)
print(table(['engine', 'ms to first script (median of 3)', 'private MB', 'RSS MB'],
            [[e, statistics.median(x['msToFirstScript'] for x in start[e]), statistics.median(x['privateMb'] for x in start[e]),
              statistics.median(x['rssMb'] for x in start[e])] for e in engines if start[e]]))

print('\n## Memory with idle services\n')
idle = defaultdict(dict)
for r in rows:
    if 'idle' in r:
        idle[r['engine']][r['idle']] = r
body = []
for e in engines:
    if not idle[e]:
        continue
    # The marginal cost: from 20 to 50 idle services, so the engine's own set-up on the first run isn't in it.
    twenty, fifty = idle[e].get(20), idle[e].get(50)
    per = round((fifty['privateMb'] - twenty['privateMb']) / 30, 1) if twenty and fifty else '?'
    body.append([e] + [f"{idle[e][n]['privateMb']} / {idle[e][n]['rssMb']}" if n in idle[e] else '-' for n in (0, 20, 50)] + [per])
print(table(['engine', '0 idle: private / RSS MB', '20 idle', '50 idle', 'private MB per idle service'], body))

print('\n## Warmed-up iteration time (median ms; lower is better)\n')
bench = defaultdict(dict)
for r in rows:
    if 'medianMs' in r or (r.get('workload') and r.get('status')):
        bench[r['workload'].split('/')[-1].replace('.js', '')][r['engine']] = r
body = []
for w in sorted(bench):
    line = [w]
    base = bench[w].get('javet', {}).get('medianMs')
    for e in engines:
        r = bench[w].get(e)
        if not r or r.get('status') != 'SUCCEEDED':
            line.append('failed' if r else '-')
            continue
        ratio = f" ({r['medianMs'] / base:.2f}x)" if base and e != 'javet' else ''
        line.append(f"{r['medianMs']}{ratio}")
    body.append(line)
print(table(['workload'] + [f'{e} median ms' for e in engines], body))
print('\nRatios are against javet (V8): above 1 is slower than V8.')

failures = [(w, e, str(r.get('error') or 'no error given').splitlines()[0]) for w in sorted(bench) for e, r in bench[w].items()
            if r.get('status') != 'SUCCEEDED']
if failures:
    print('\nFailed:\n')
    for w, e, why in failures:
        print(f'- {w} on {e}: {why}')

print('\n## Tail latency once warm (p99 ms)\n')
print(table(['workload'] + engines, [[w] + [bench[w].get(e, {}).get('p99Ms', '-') for e in engines] for w in sorted(bench)]))

print('\n## First iteration, cold (ms; for information only)\n')
print(table(['workload'] + engines, [[w] + [bench[w].get(e, {}).get('firstMs', '-') for e in engines] for w in sorted(bench)]))

print('\n## Memory after the workload ran (private MB / peak RSS MB)\n')
print(table(['workload'] + engines, [[w] + [f"{bench[w][e].get('privateMb')} / {bench[w][e].get('peakRssMb')}" if e in bench[w] else '-'
                                           for e in engines] for w in sorted(bench)]))

print('\n## Hostile memory tests (each worker under a kernel memory cap)\n')
hostile = defaultdict(dict)
for r in rows:
    if 'hostile' in r:
        hostile[r['hostile']][r['engine']] = r
def outcome(r):
    if not r:
        return '-'
    if r.get('status') == 'KILLED':
        return 'KILLED by the kernel at the cap'
    if r.get('status') == 'SUCCEEDED':
        return f"ran to the end: {r.get('output')} (peak RSS {r.get('peakRssMb')} MB)"
    first = str(r.get('error', '')).splitlines()[0] if r.get('error') else ''
    return f"stopped: {first[:90]} (peak RSS {r.get('peakRssMb')} MB)"
hostile_engines = [e for e in engines if any(e in hostile[h] for h in hostile)]
print(table(['attack'] + hostile_engines, [[h] + [outcome(hostile[h].get(e)) for e in hostile_engines] for h in hostile]))
