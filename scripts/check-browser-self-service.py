#!/usr/bin/env python3
"""Exercise the disposable kind proof through the public CLI and API, never a cloud target.

Start scripts/verify-browser-kubernetes.py and its localhost:18080 port-forward first.
Generates only small browser cohorts against the bundled browser-target fixture.
"""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request
import uuid

root = Path(__file__).resolve().parents[1]
work = root / '.local' / 'browser-proof'
token = (work / 'token').read_text().strip()
base = 'http://127.0.0.1:18080'
headers = {'Authorization': 'Bearer ' + token}
env = {**os.environ, 'PERF_PLATFORM_URL': base, 'PERF_PLATFORM_TOKEN_FILE': str(work / 'token')}

def api(method, route, body=None):
    request = urllib.request.Request(base + '/api/v2/' + route, method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={**headers, 'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=30) as response: return json.load(response)

def cli(*args, expected=0):
    result = subprocess.run(['python3', str(root / 'scripts/perf.py'), *args], env=env, text=True, capture_output=True)
    if result.returncode != expected: raise AssertionError(result.stderr or result.stdout)
    return json.loads(result.stdout)

def definition(suffix, script, iterations=1):
    source_file = work / (suffix + '.spec.ts'); source_file.write_text(script)
    source = cli('upload-browser', '--team', 'proof-team', '--script', str(source_file), '--reviewed')
    name = 'proof-' + suffix + '-' + uuid.uuid4().hex[:8]
    spec = {'apiVersion':'performance.ameriprise.com/v1alpha1','kind':'PerformanceTest',
      'metadata':{'name':name,'team':'proof-team','application':'browser-target'},
      'spec':{'environment':{'name':'performance','runnerCell':'standalone','targetClass':'non-production'},
        'sources':[{'name':'journey','type':'playwright','uri':source['uri'],'digest':source['digest']}],
        'workloads':[{'name':'journey','type':'browser','engine':'playwright','sourceRef':'journey',
          'browserUsers':1,'iterationsPerUser':iterations,'browserProfile':{'device':'desktop','network':'none','cpuThrottle':1}}],
        'objectives':[{'metric':'browser.journey.duration','scope':'journey','percentile':95,'threshold':'8s'},
                      {'metric':'browser.error.rate','scope':'journey','threshold':'0%'}],
        'governance':{'maxDuration':'2m'}}}
    path = work / (suffix + '.json'); path.write_text(json.dumps(spec))
    assert cli('validate', '--spec', str(path))['valid']
    created = cli('create', '--spec', str(path))
    return created['version']['id']

def trigger(version):
    key = 'proof-' + uuid.uuid4().hex
    run = cli('run', '--version', version, '--idempotency-key', key)
    repeated = cli('run', '--version', version, '--idempotency-key', key)
    assert run['id'] == repeated['id'], 'Idempotency did not preserve the run'
    return run['id']

api('POST', 'targets', {'team':'proof-team','application':'browser-target','environment':'performance',
    'targetClass':'NON_PRODUCTION','baseUrl':'http://browser-target.perf-browser-proof.svc.cluster.local:8081'})
success_script = """import { test, expect } from '@playwright/test';
test('Complete the journey', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('link', {name:'Continue'}).click();
  await expect(page.getByRole('heading')).toHaveText('Journey complete');
});
"""
run_id = trigger(definition('success', success_script, 2))
assert cli('wait', '--run', run_id, '--timeout', '120')['status'] == 'COMPLETED'
report = cli('report', '--run', run_id)
assert not report['synthetic'] and report['measurements']['complete']
assert report['measurements']['overall']['sampleCount'] == 2, 'Secondary evidence diluted headline journey count'
assert all(o['verdict'] == 'PASS' for o in report['objectives'])
artifact_count = 0
for artifact in report['artifacts']:
    request = urllib.request.Request(base + f"/api/v2/test-runs/{run_id}/artifacts/{artifact['id']}/content", headers=headers)
    with urllib.request.urlopen(request, timeout=30) as response:
        body = response.read()
        assert artifact['digest'] == 'sha256:' + hashlib.sha256(body).hexdigest(), 'Artifact digest missing or incorrect'
        artifact_count += 1
request = urllib.request.Request(base + f'/api/v2/test-runs/{run_id}/report')
try: urllib.request.urlopen(request, timeout=30); raise AssertionError('Report was public')
except urllib.error.HTTPError as error: assert error.code == 401
failure_script = success_script.replace("toHaveText('Journey complete')", "toHaveText('Deliberate failure', {timeout:1000})")
failed_id = trigger(definition('assertion', failure_script))
cli('wait', '--run', failed_id, '--timeout', '120', expected=1)
failed_report = cli('report', '--run', failed_id)
assert any(o['metric'] == 'browser.error.rate' and o['verdict'] == 'BREACH' for o in failed_report['objectives'])
assert any(a['objectKey'].endswith('browser-evidence.tar.gz') and a['sizeBytes'] > 1000 for a in failed_report['artifacts'])
slow_script = success_script.replace("await page.goto('/');", "await page.goto('/'); await page.waitForTimeout(45000);")
cancel_id = trigger(definition('cancel', slow_script))
for attempt in range(60):
    state = api('GET', f'test-runs/{cancel_id}')
    if state['status'] == 'RUNNING': break
    time.sleep(1)
else: raise AssertionError('Cancellation test never entered RUNNING')
time.sleep(5)  # Let the actual browser enter its deliberately long journey before stopping it.
cli('cancel', '--run', cancel_id)
cli('cancel', '--run', cancel_id)
assert cli('wait', '--run', cancel_id, '--timeout', '120', expected=1)['status'] == 'CANCELLED'
summary = {'successRun':run_id,'assertionRun':failed_id,'cancelledRun':cancel_id,
           'verifiedArtifacts':artifact_count,'checks':['CLI authoring','validation','immutable source','idempotency',
           'real journey metrics','objective pass and breach','private digest-verified downloads','cancellation']}
(work / 'verification.json').write_text(json.dumps(summary, indent=2))
print(json.dumps(summary, indent=2))
