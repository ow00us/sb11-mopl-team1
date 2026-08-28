#!/bin/bash
set -euo pipefail

python3 - << 'PY'
import json, glob, os, re
from collections import defaultdict

METRICS = ['watch_subscribe_ack_duration', 'chat_delivery_delay', 'watch_connect_duration']
rows = defaultdict(list)

for f in sorted(glob.glob('results/*-summary.json')):
    cond = re.sub(r'-run\d+-summary\.json$', '', os.path.basename(f))
    with open(f) as fp:
        d = json.load(fp)
    m = d.get('metrics', {})
    entry = {'file': os.path.basename(f)}
    for name in METRICS:
        s = m.get(name, {})
        entry[name] = (s.get('med'), s.get('p(95)'), s.get('max'))
    err = m.get('stomp_error_rate', {})
    entry['error_rate'] = err.get('value')
    entry['chat_sent'] = m.get('chat_sent_total', {}).get('count')
    entry['chat_recv'] = m.get('chat_received_total', {}).get('count')
    rows[cond].append(entry)

for cond, entries in rows.items():
    print(f"\n=== {cond} ({len(entries)}회) ===")
    for name in METRICS:
        p95s = [e[name][1] for e in entries if e[name][1] is not None]
        if not p95s: continue
        for i, e in enumerate(entries, 1):
            med, p95, mx = e[name]
            print(f"  {name} run{i}: p50={med} p95={p95} max={mx}")
        print(f"  → {name} p95 편차: {max(p95s) - min(p95s):.1f} (min={min(p95s):.1f}, max={max(p95s):.1f})")
    for i, e in enumerate(entries, 1):
        print(f"  run{i}: error_rate={e['error_rate']} chat_sent={e['chat_sent']} chat_recv={e['chat_recv']}")

print("\n=== 드롭 카운터 ===")
for f in sorted(glob.glob('results/*-dropped-*.txt')):
    total = 0
    with open(f) as fp:
        for line in fp:
            if line.startswith('mopl_watchingsession_ratelimit_dropped_total'):
                try: total += float(line.split()[-1])
                except (ValueError, IndexError): pass
    print(f"  {os.path.basename(f)}: {total:.0f}")
PY
