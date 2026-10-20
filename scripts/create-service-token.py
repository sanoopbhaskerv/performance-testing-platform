#!/usr/bin/env python3
"""Create a scoped standalone machine identity without printing credential material."""
import argparse
import datetime as dt
import hashlib
import json
import os
import re
import secrets
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--subject', required=True)
parser.add_argument('--team', required=True)
parser.add_argument('--output', type=Path, required=True, help='New operator identity JSON file')
parser.add_argument('--token-output', type=Path, required=True, help='New private file containing the access token')
parser.add_argument('--days', type=int, default=90)
parser.add_argument('--scope', action='append', help='Repeat to grant scopes; defaults to team author/runner scopes')
args = parser.parse_args()
if not re.fullmatch(r'[a-z0-9][a-z0-9-]{0,62}', args.team) or not 1 <= args.days <= 365:
    parser.error('Use a lowercase team name and an expiry between 1 and 365 days.')
if args.output.resolve() == args.token_output.resolve(): parser.error('Use separate output files.')
for file in [args.output, args.token_output]:
    if file.exists(): parser.error(f'{file} already exists; refusing to replace an identity or token.')
    file.parent.mkdir(parents=True, exist_ok=True)
token = secrets.token_urlsafe(32)
identity = dict(subject=args.subject, teams=[args.team], tokenSha256=hashlib.sha256(token.encode()).hexdigest(),
                scopes=args.scope or ['definitions:read', 'definitions:write', 'runs:execute', 'runs:read', 'runs:cancel'],
                expiresAt=(dt.datetime.now(dt.timezone.utc) + dt.timedelta(days=args.days)).isoformat().replace('+00:00', 'Z'))
for file, content in [(args.output, json.dumps([identity], indent=2) + '\n'), (args.token_output, token + '\n')]:
    fd = os.open(file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as stream: stream.write(content)
print(f'Identity hashes: {args.output}\nPrivate access token: {args.token_output}\nKeep both files out of source control. Tokens expire and remain scoped to the named team.')
