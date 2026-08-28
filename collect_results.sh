#!/bin/bash
set -euo pipefail

PYTHON_CMD=()

if command -v python3 >/dev/null 2>&1 \
  && python3 -c 'import json' >/dev/null 2>&1; then
  PYTHON_CMD=(python3)
elif command -v python >/dev/null 2>&1 \
  && python -c 'import json' >/dev/null 2>&1; then
  PYTHON_CMD=(python)
elif command -v py >/dev/null 2>&1 \
  && py -3 -c 'import json' >/dev/null 2>&1; then
  PYTHON_CMD=(py -3)
else
  echo "Python 3 실행 파일을 찾지 못했습니다." >&2
  exit 1
fi

"${PYTHON_CMD[@]}" - << 'PY'
import glob
import hashlib
import json
import os
import re
from collections import defaultdict

METRICS = [
    'measure_watch_subscribe_ack_duration',
    'measure_chat_delivery_delay',
    'measure_watch_connect_duration',
]
MANIFEST_PATH = 'results/measurement-manifest.txt'
CONDITION_SPECS = {
    'A': ('A-1inst-cache-on', ('backend-a',), '30s'),
    'B': ('B-1inst-cache-off', ('backend-a',), '1ms'),
    'C': ('C-2inst-cache-on', ('backend-a', 'backend-b'), '30s'),
}

rows = defaultdict(list)
invalid_reasons = []


def file_sha256(path):
    digest = hashlib.sha256()
    with open(path, 'rb') as fp:
        for chunk in iter(lambda: fp.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def read_manifest():
    if not os.path.exists(MANIFEST_PATH):
        invalid_reasons.append('measurement manifest missing')
        return {}

    manifest = {}
    with open(MANIFEST_PATH, encoding='utf-8') as fp:
        for raw_line in fp:
            line = raw_line.rstrip('\n')
            if '=' not in line:
                continue
            key, value = line.split('=', 1)
            manifest[key] = value.rstrip('\r')
    return manifest


manifest = read_manifest()
measurement_id = manifest.get('measurement_id')
conditions = manifest.get('conditions', '')

try:
    expected_runs = int(manifest.get('runs', '0'))
except ValueError:
    expected_runs = 0
try:
    expected_peak_vus = int(manifest.get('peak_vus', '0'))
except ValueError:
    expected_peak_vus = 0

if manifest.get('status') != 'complete':
    invalid_reasons.append(
        f"measurement status is {manifest.get('status')!r}, expected 'complete'"
    )
if not measurement_id:
    invalid_reasons.append('measurement_id missing')
if expected_runs < 1:
    invalid_reasons.append(f'invalid runs value: {manifest.get("runs")!r}')
if not conditions or any(condition not in CONDITION_SPECS for condition in conditions):
    invalid_reasons.append(f'invalid conditions value: {conditions!r}')

compose_override_path = manifest.get(
    'compose_override_snapshot_path',
    'docker-compose.perf-override.yml',
)
hash_targets = {
    'run_all_sha256': 'run_all.sh',
    'collect_results_sha256': 'collect_results.sh',
    'compose_override_sha256': compose_override_path,
    'scenario_sha256': 'k6/watch-chat-spike.js',
    'auth_helper_sha256': 'k6/lib/auth.js',
    'stomp_helper_sha256': 'k6/lib/stomp.js',
}
for manifest_key, path in hash_targets.items():
    expected_hash = manifest.get(manifest_key)
    if not expected_hash or not os.path.exists(path):
        invalid_reasons.append(f'{manifest_key} or {path} missing')
    elif file_sha256(path) != expected_hash:
        invalid_reasons.append(f'{path}: hash differs from measurement manifest')

expected_prefixes = []
for condition in conditions:
    condition_prefix = CONDITION_SPECS.get(condition, ('', (), ''))[0]
    for run in range(1, expected_runs + 1):
        expected_prefixes.append(f'{condition_prefix}-run{run}')

actual_summary_names = {
    os.path.basename(path)
    for path in glob.glob('results/*-summary.json')
}
expected_summary_names = {
    f'{prefix}-summary.json'
    for prefix in expected_prefixes
}
if actual_summary_names != expected_summary_names:
    invalid_reasons.append(
        'summary set mismatch: '
        f'missing={sorted(expected_summary_names - actual_summary_names)}, '
        f'unexpected={sorted(actual_summary_names - expected_summary_names)}'
    )

for path in sorted(glob.glob('results/*-summary.json')):
    name = os.path.basename(path)
    condition = re.sub(r'-run\d+-summary\.json$', '', name)

    with open(path, encoding='utf-8') as fp:
        data = json.load(fp)

    if data.get('measurement_id') != measurement_id:
        invalid_reasons.append(
            f"{name}: measurement_id={data.get('measurement_id')!r}, "
            f"expected={measurement_id!r}"
        )

    metrics = data.get('metrics', {})
    run_match = re.search(r'-run(\d+)-summary\.json$', name)
    entry = {
        'file': name,
        'run': int(run_match.group(1)) if run_match else None,
    }

    def metric_values(metric_name):
        metric = metrics.get(metric_name, {})
        return metric.get('values', metric)

    for metric_name in METRICS:
        values = metric_values(metric_name)
        entry[metric_name] = (
            values.get('med'),
            values.get('p(95)'),
            values.get('max'),
        )

    def rate_result(metric_name):
        values = metric_values(metric_name)
        errors = values.get('passes') or 0
        successes = values.get('fails') or 0
        return {
            'rate': values.get('value', values.get('rate')),
            'errors': errors,
            'samples': errors + successes,
        }

    entry['error'] = rate_result('stomp_error_rate')
    entry['watch_error'] = rate_result('watch_error_rate')
    entry['chat_error'] = rate_result('chat_error_rate')
    entry['measure_error'] = rate_result('measure_stomp_error_rate')
    entry['measure_watch_error'] = rate_result('measure_watch_error_rate')
    entry['measure_chat_error'] = rate_result('measure_chat_error_rate')
    entry['attempts'] = metric_values('session_attempts_total').get('count')
    entry['measure_attempts'] = metric_values(
        'measure_session_attempts_total'
    ).get('count')
    entry['iterations'] = metric_values('iterations').get('count')
    entry['ws_sessions'] = metric_values('ws_sessions').get('count')
    entry['vus_max'] = metric_values('vus_max').get(
        'value',
        metric_values('vus_max').get('max'),
    )
    entry['vus_actual_max'] = metric_values('vus').get('max')

    total_counts = {
        entry['error']['samples'],
        entry['watch_error']['samples'],
        entry['chat_error']['samples'],
        entry['attempts'],
        entry['iterations'],
    }
    measure_counts = {
        entry['measure_error']['samples'],
        entry['measure_watch_error']['samples'],
        entry['measure_chat_error']['samples'],
        entry['measure_attempts'],
    }
    entry['sample_count_valid'] = (
        None not in total_counts
        and None not in measure_counts
        and len(total_counts) == 1
        and len(measure_counts) == 1
        and entry['measure_attempts'] > 0
        and entry['attempts'] - entry['measure_attempts'] == 20
        and entry['ws_sessions'] is not None
        and 0 <= entry['ws_sessions'] <= entry['attempts']
        and entry['vus_max'] == expected_peak_vus
        and entry['vus_actual_max'] == expected_peak_vus
    )
    if not entry['sample_count_valid']:
        invalid_reasons.append(
            f"{name}: inconsistent samples "
            f"total={sorted(str(value) for value in total_counts)}, "
            f"measure={sorted(str(value) for value in measure_counts)}, "
            f"ws_sessions={entry['ws_sessions']}, vus_max={entry['vus_max']}, "
            f"vus_actual_max={entry['vus_actual_max']}"
        )
    entry['chat_sent'] = metric_values('measure_chat_sent_total').get('count')
    entry['chat_recv'] = metric_values('measure_chat_received_total').get('count')
    entry['chat_echo'] = metric_values('measure_chat_echo_total').get('count')
    entry['chat_backlog_recv'] = metric_values(
        'measure_chat_backlog_received_total'
    ).get('count')
    rows[condition].append(entry)

if not rows:
    invalid_reasons.append('summary files missing')

for condition, entries in rows.items():
    condition_code = condition[:1]
    _, services, expected_ttl = CONDITION_SPECS.get(
        condition_code,
        ('', (), ''),
    )
    for entry in entries:
        prefix = re.sub(r'-summary\.json$', '', entry['file'])

        for service in services:
            required_paths = [
                os.path.join(
                    'results',
                    f'{prefix}-config-{service}.txt',
                ),
                os.path.join(
                    'results',
                    f'{prefix}-metrics-{service}-before.txt',
                ),
                os.path.join(
                    'results',
                    f'{prefix}-metrics-{service}-after.txt',
                ),
            ]
            for required_path in required_paths:
                if not os.path.exists(required_path):
                    invalid_reasons.append(
                        f'{prefix} {service}: missing '
                        f'{os.path.basename(required_path)}'
                    )

            config_path = required_paths[0]
            if os.path.exists(config_path):
                with open(config_path, encoding='utf-8') as fp:
                    actual_ttl = fp.read().strip()
                if actual_ttl != expected_ttl:
                    invalid_reasons.append(
                        f'{prefix} {service}: expected TTL '
                        f'{expected_ttl}, got {actual_ttl!r}'
                    )

for condition, entries in rows.items():
    entries.sort(key=lambda entry: entry['run'] or 0)
    print(f"\n=== {condition} ({len(entries)}회) ===")

    for metric_name in METRICS:
        p95s = [
            entry[metric_name][1]
            for entry in entries
            if entry[metric_name][1] is not None
        ]
        if not p95s:
            continue

        for entry in entries:
            median, p95, maximum = entry[metric_name]
            print(
                f"  {metric_name} run{entry['run']}: "
                f"p50={median} p95={p95} max={maximum}"
            )

        print(
            f"  → {metric_name} p95 편차: "
            f"{max(p95s) - min(p95s):.1f} "
            f"(min={min(p95s):.1f}, max={max(p95s):.1f})"
        )

    for entry in entries:
        validity = '' if entry['sample_count_valid'] else ' INVALID(sample count)'
        error = entry['measure_error']
        watch_error = entry['measure_watch_error']
        chat_error = entry['measure_chat_error']
        print(
            f"  run{entry['run']}: measure_error_rate={error['rate']} "
            f"errors={error['errors']}/{error['samples']} "
            f"watch_errors={watch_error['errors']} "
            f"chat_errors={chat_error['errors']} "
            f"measure_attempts={entry['measure_attempts']} "
            f"total_attempts={entry['attempts']} "
            f"iterations={entry['iterations']} "
            f"ws_sessions={entry['ws_sessions']} "
            f"vus_max={entry['vus_max']} "
            f"vus_actual_max={entry['vus_actual_max']} "
            f"chat_sent={entry['chat_sent']} "
            f"chat_recv={entry['chat_recv']} "
            f"chat_echo={entry['chat_echo']} "
            f"chat_backlog_recv={entry['chat_backlog_recv']}"
            f"{validity}"
        )


def parse_prometheus(path):
    samples = {}
    pattern = re.compile(
        r'^([a-zA-Z_:][a-zA-Z0-9_:]*(?:\{[^}]*\})?)\s+'
        r'(-?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?)$'
    )

    with open(path, encoding='utf-8') as fp:
        for raw_line in fp:
            line = raw_line.strip()
            match = pattern.match(line)
            if match:
                samples[match.group(1)] = float(match.group(2))

    return samples


print("\n=== 회차별 서버 메트릭 증분 ===")
after_pattern = re.compile(
    r'^(?P<prefix>.+)-metrics-(?P<service>backend-[ab])-after\.txt$'
)
server_deltas = {}
process_starts = defaultdict(dict)

for after_path in sorted(glob.glob('results/*-metrics-*-after.txt')):
    after_name = os.path.basename(after_path)
    match = after_pattern.match(after_name)
    if not match:
        continue

    prefix = match.group('prefix')
    service = match.group('service')
    before_path = os.path.join(
        'results',
        f'{prefix}-metrics-{service}-before.txt',
    )

    if not os.path.exists(before_path):
        print(f"  {prefix} {service}: before snapshot 누락")
        invalid_reasons.append(
            f"{prefix} {service}: before snapshot missing"
        )
        continue

    before = parse_prometheus(before_path)
    after = parse_prometheus(after_path)

    before_start = before.get('process_start_time_seconds')
    after_start = after.get('process_start_time_seconds')
    if (
        before_start is None
        or after_start is None
        or before_start != after_start
    ):
        print(
            f"  {prefix} {service}: INVALID "
            "(측정 중 backend 프로세스 재시작 또는 시작 시각 누락)"
        )
        invalid_reasons.append(
            f"{prefix} {service}: process start time changed or missing"
        )
        continue

    process_starts[service][prefix] = before_start

    def delta_sum(metric_prefix, required_label=None):
        total = 0.0
        matched = False

        for key, after_value in after.items():
            if not key.startswith(metric_prefix):
                continue
            if required_label and required_label not in key:
                continue

            matched = True
            total += after_value - before.get(key, 0.0)

        return total if matched else None

    def current_sum(metric_prefix):
        values = [
            value
            for key, value in after.items()
            if key.startswith(metric_prefix)
        ]
        return sum(values) if values else None

    dropped = delta_sum(
        'mopl_watchingsession_ratelimit_dropped_total'
    )
    published = delta_sum(
        'mopl_realtime_relay_published_messages_total',
        'outcome="succeeded"',
    )
    publish_failed = delta_sum(
        'mopl_realtime_relay_published_messages_total',
        'outcome="failed"',
    )
    delivered = delta_sum(
        'mopl_realtime_relay_delivered_messages_total'
    )
    handler_failures = delta_sum(
        'mopl_realtime_relay_handler_failures_total'
    )
    discarded_nonself = 0.0
    discarded_matched = False
    for key, after_value in after.items():
        if not key.startswith('mopl_realtime_relay_discarded_messages_total'):
            continue
        if 'reason="self"' in key:
            continue
        discarded_matched = True
        discarded_nonself += after_value - before.get(key, 0.0)
    if not discarded_matched:
        discarded_nonself = None
    subscribed = current_sum('mopl_realtime_relay_subscribed')
    pool_timeouts = delta_sum(
        'hikaricp_connections_timeout_total'
    )
    pool_active = current_sum('hikaricp_connections_active')
    pool_idle = current_sum('hikaricp_connections_idle')
    pool_pending = current_sum('hikaricp_connections_pending')
    pool_max = current_sum('hikaricp_connections_max')
    server_deltas[(prefix, service)] = {
        'relay_published': published,
        'relay_publish_failed': publish_failed,
        'relay_delivered': delivered,
        'relay_handler_failures': handler_failures,
        'relay_discarded_nonself': discarded_nonself,
        'relay_subscribed': subscribed,
    }

    if dropped is None:
        invalid_reasons.append(f'{prefix} {service}: drop metric missing')
    if pool_timeouts is None:
        invalid_reasons.append(f'{prefix} {service}: Hikari timeout metric missing')

    print(
        f"  {prefix} {service}: "
        f"dropped={dropped} "
        f"relay_published={published} "
        f"relay_publish_failed={publish_failed} "
        f"relay_delivered={delivered} "
        f"relay_handler_failures={handler_failures} "
        f"relay_discarded_nonself={discarded_nonself} "
        f"relay_subscribed={subscribed} "
        f"hikari_timeouts={pool_timeouts} "
        f"hikari_after="
        f"{pool_active}/{pool_idle}/{pool_pending}/{pool_max}"
    )

for service, starts_by_prefix in process_starts.items():
    starts = list(starts_by_prefix.values())
    if len(starts) != len(set(starts)):
        invalid_reasons.append(
            f'{service}: process_start_time_seconds reused across runs'
        )

for entries in rows.values():
    for entry in entries:
        prefix = re.sub(r'-summary\.json$', '', entry['file'])
        if not prefix.startswith('C-'):
            continue

        relay = {
            service: server_deltas.get((prefix, service), {})
            for service in ('backend-a', 'backend-b')
        }
        for service, values in relay.items():
            published = values.get('relay_published')
            delivered = values.get('relay_delivered')
            publish_failed = values.get('relay_publish_failed')
            handler_failures = values.get('relay_handler_failures') or 0.0
            discarded_nonself = values.get('relay_discarded_nonself')
            subscribed = values.get('relay_subscribed')

            if published is None or published <= 0:
                invalid_reasons.append(
                    f'{prefix} {service}: relay publish delta '
                    f'must be positive, got {published}'
                )
            if delivered is None or delivered <= 0:
                invalid_reasons.append(
                    f'{prefix} {service}: relay delivered delta '
                    f'must be positive, got {delivered}'
                )
            if publish_failed != 0:
                invalid_reasons.append(
                    f'{prefix} {service}: relay publish failures={publish_failed}'
                )
            if handler_failures != 0:
                invalid_reasons.append(
                    f'{prefix} {service}: relay handler failures={handler_failures}'
                )
            if discarded_nonself != 0:
                invalid_reasons.append(
                    f'{prefix} {service}: relay non-self discards={discarded_nonself}'
                )
            if subscribed != 1:
                invalid_reasons.append(
                    f'{prefix} {service}: relay subscribed={subscribed}'
                )

        cross_pairs = (
            ('backend-a', 'backend-b'),
            ('backend-b', 'backend-a'),
        )
        for publisher, receiver in cross_pairs:
            published = relay[publisher].get('relay_published')
            delivered = relay[receiver].get('relay_delivered')
            if published is None or delivered is None:
                continue

            mismatch = abs(published - delivered)
            tolerance = max(5.0, max(published, delivered) * 0.005)
            print(
                f'  {prefix} {publisher}->{receiver}: '
                f'published={published} delivered={delivered} '
                f'mismatch={mismatch} tolerance={tolerance}'
            )
            if mismatch > tolerance:
                invalid_reasons.append(
                    f'{prefix} {publisher}->{receiver}: relay cross-count '
                    f'mismatch={mismatch}, tolerance={tolerance}'
                )

if invalid_reasons:
    print("\n=== INVALID 결과 ===")
    for reason in invalid_reasons:
        print(f"  - {reason}")
    raise SystemExit(1)
PY
