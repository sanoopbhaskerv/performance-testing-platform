#!/usr/bin/env python3
"""Portable REST client for editors, humans and CI; requires Python 3 only."""
import argparse
import json
import os
from pathlib import Path
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

TERMINAL = {'COMPLETED', 'FAILED', 'CANCELLED', 'EXPIRED', 'REJECTED'}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default=os.environ.get('PERF_PLATFORM_URL', 'http://localhost:8080'))
    sub = parser.add_subparsers(dest='command', required=True)
    for name in ['validate', 'create', 'version']:
        cmd = sub.add_parser(name); cmd.add_argument('--spec', type=Path, required=True)
        if name == 'version': cmd.add_argument('--definition', required=True)
    cmd = sub.add_parser('upload-browser'); cmd.add_argument('--team', required=True)
    cmd.add_argument('--script', type=Path, required=True); cmd.add_argument('--reviewed', action='store_true', required=True)
    cmd = sub.add_parser('run'); cmd.add_argument('--version', required=True); cmd.add_argument('--idempotency-key', required=True)
    for name in ['wait', 'report', 'cancel']:
        cmd = sub.add_parser(name); cmd.add_argument('--run', required=True)
        if name == 'wait':
            cmd.add_argument('--timeout', type=int, default=600); cmd.add_argument('--interval', type=float, default=3)
    args = parser.parse_args()
    url = urllib.parse.urlsplit(args.url)
    if url.username or url.password: parser.error('Do not put credentials in the platform URL.')
    if url.scheme != 'https' and not (url.scheme == 'http' and url.hostname in ['localhost', '127.0.0.1', '::1']):
        parser.error('Use HTTPS, or a localhost port-forward for a local deployment.')
    token_file = os.environ.get('PERF_PLATFORM_TOKEN_FILE')
    token = Path(token_file).read_text().strip() if token_file else os.environ.get('PERF_PLATFORM_TOKEN', '').strip()
    if not token: parser.error('Set PERF_PLATFORM_TOKEN_FILE or PERF_PLATFORM_TOKEN. The credential is never printed.')

    def request(method, route, data=None, content_type='application/json', extra=None):
        headers = {'Authorization': 'Bearer ' + token, 'X-API-Key': token, 'Content-Type': content_type, **(extra or {})}
        body = data.encode() if isinstance(data, str) else data
        req = urllib.request.Request(args.url.rstrip('/') + '/api/v2/' + route, data=body, headers=headers, method=method)
        try:
            # Do not redirect an Authorization header to another origin.
            class NoRedirect(urllib.request.HTTPRedirectHandler):
                def redirect_request(self, *unused, **kwargs): return None
            with urllib.request.build_opener(NoRedirect()).open(req, timeout=30) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            try: problem = json.load(error)
            except (ValueError, UnicodeError): problem = {'detail': 'The platform rejected the request.'}
            print(json.dumps({'status': error.code, 'code': problem.get('code'), 'detail': problem.get('detail'), 'findings': problem.get('findings')}, indent=2), file=sys.stderr)
            raise SystemExit(1)
        except urllib.error.URLError:
            print('Could not reach the platform. No load was retried. Check its URL and health.', file=sys.stderr)
            raise SystemExit(1)

    c = args.command
    if c in ['validate', 'create', 'version']:
        route = {'validate': 'test-definitions/validate', 'create': 'test-definitions', 'version': f'test-definitions/{getattr(args, "definition", "")}/versions'}[c]
        result = request('POST', route, args.spec.read_bytes(), 'application/yaml')
    elif c == 'upload-browser':
        result = request('POST', 'browser-sources?' + urllib.parse.urlencode({'team': args.team, 'reviewed': 'true'}), args.script.read_bytes(), 'text/plain')
    elif c == 'run':
        result = request('POST', 'test-runs', json.dumps({'definitionVersionId': args.version}), extra={'Idempotency-Key': args.idempotency_key})
    elif c == 'report': result = request('GET', f'test-runs/{args.run}/report')
    elif c == 'cancel': result = request('POST', f'test-runs/{args.run}/cancel')
    else:
        deadline = time.monotonic() + args.timeout
        while True:
            result = request('GET', f'test-runs/{args.run}')
            if result['status'] in TERMINAL: break
            if time.monotonic() >= deadline:
                print(json.dumps({'runId': args.run, 'status': result['status'], 'detail': 'Wait timed out; the run may still be active. No retry or cancellation was sent.'}), file=sys.stderr)
                return 2
            time.sleep(max(0.2, args.interval))
    print(json.dumps(result, indent=2))
    if c == 'validate' and not result.get('valid'): return 1
    if c == 'wait' and result['status'] != 'COMPLETED': return 1
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
