"""Exercise the runner's upload contract without a cluster or external storage."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


class RunnerUploadTest(unittest.TestCase):
    def test_every_evidence_upload_carries_its_original_fingerprint(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            binary = root / 'bin'
            binary.mkdir()
            (root / 'objects').mkdir()
            for name, source in {
                'aws': '''import json,os,sys
from pathlib import Path
args=sys.argv[1:]; args=args[args.index('s3'):]; source,destination=args[2:4]
if source.startswith('s3://'):
    Path(destination).write_text('<jmeterTestPlan/>')
else:
    data=Path(source).read_bytes(); name=Path(source).name
    root=Path(os.environ['TEST_OBJECTS'])
    (root/name).write_bytes(data)
    (root/(name+'.metadata')).write_text(json.dumps({
        'digest':args[args.index('--metadata')+1],
        'contentType':args[args.index('--content-type')+1]}))
''',
                'jmeter': '''import sys
from pathlib import Path
args=sys.argv[1:]
Path(args[args.index('-l')+1]).write_text('timeStamp,elapsed,success\\n1000,2,true\\n')
Path(args[args.index('-j')+1]).write_text('runner evidence\\n')
''',
            }.items():
                program = binary / name
                program.write_text('#!/usr/bin/env python3\n' + source)
                program.chmod(0o755)
            environment = {**os.environ, 'PATH': str(binary) + os.pathsep + os.environ['PATH'],
                'TEST_OBJECTS': str(root / 'objects'), 'PERF_WORKSPACE': str(root),
                'PERF_RUN_ID': 'run-id', 'PERF_SHARD_ID': 'shard-id', 'PERF_SHARD_INDEX': '0',
                'PERF_SHARD_COUNT': '1', 'PERF_PLAN_DIGEST': 'sha256:plan',
                'PERF_WORKLOAD_KEY': 'plan.jmx', 'PERF_ARTIFACT_BUCKET': 'test',
                'PERF_ARTIFACT_REGION': 'us-east-1',
                'PERF_ARTIFACT_PREFIX': 'teams/team/runs/run/shards/shard/',
                'PERF_PROGRESS_INTERVAL_SECONDS': '0', 'PERF_DEADLINE_SECONDS': '1'}
            result = subprocess.run(['bash', str(Path(__file__).with_name('entrypoint.sh'))],
                env=environment, capture_output=True, text=True, timeout=8)
            self.assertEqual(0, result.returncode, result.stderr)
            for name, content_type in [('results.jtl', 'text/csv'), ('jmeter.log', 'text/plain'),
                                       ('runner-manifest.json', 'application/json')]:
                body = (root / 'objects' / name).read_bytes()
                metadata = json.loads((root / 'objects' / (name + '.metadata')).read_text())
                self.assertEqual('perf-digest=sha256:' + hashlib.sha256(body).hexdigest(), metadata['digest'])
                self.assertEqual(content_type, metadata['contentType'])
            manifest = json.loads((root / 'objects' / 'runner-manifest.json').read_text())
            self.assertEqual('sha256:plan', manifest['planDigest'])
            self.assertEqual('COMPLETED', manifest['status'])


if __name__ == '__main__':
    unittest.main()
