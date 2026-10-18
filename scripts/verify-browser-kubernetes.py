#!/usr/bin/env python3
"""Local kind proof using the shipped chart and images. Never selects a cloud account.

Requires an existing disposable `kind-perf-browser-verify` context and locally built images.
Leaves the proof namespace up for UI inspection; delete the disposable kind cluster to clean up.
"""
import base64
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import datetime as dt

root = Path(__file__).resolve().parents[1]
ns = 'perf-browser-proof'
runner_ns = 'perf-browser-proof-runners'
context = 'kind-perf-browser-verify'
work = root / '.local' / 'browser-proof'
work.mkdir(parents=True, exist_ok=True)

def command(args, data=None, capture=True):
    result = subprocess.run(args, input=data, text=True, stdout=subprocess.PIPE if capture else None,
                            stderr=subprocess.PIPE, cwd=root)
    if result.returncode:
        raise RuntimeError(f'{args[0:3]} failed: {result.stderr[-2000:]}')
    return result.stdout or ''

def kubectl(*args, data=None): return command(['kubectl', '--context', context, *args], data)
def apply(obj): kubectl('apply', '-f', '-', data=json.dumps(obj))
def secret(name, values, namespace=ns):
    apply({'apiVersion':'v1','kind':'Secret','metadata':{'name':name,'namespace':namespace},'type':'Opaque','stringData':values})
def deployment(name, image, port, env=None, command_args=None, volumes=None, mounts=None):
    container = {'name':name,'image':image,'ports':[{'containerPort':port}], 'imagePullPolicy':'IfNotPresent'}
    if env: container['env'] = env
    if command_args: container['command'] = command_args
    if mounts: container['volumeMounts'] = mounts
    pod = {'containers':[container]}
    if volumes: pod['volumes'] = volumes
    apply({'apiVersion':'apps/v1','kind':'Deployment','metadata':{'name':name,'namespace':ns},
           'spec':{'replicas':1,'selector':{'matchLabels':{'app':name}},'template':{'metadata':{'labels':{'app':name}},'spec':pod}}})
    apply({'apiVersion':'v1','kind':'Service','metadata':{'name':name,'namespace':ns},
           'spec':{'selector':{'app':name},'ports':[{'port':port,'targetPort':port}]}})

def from_secret(key, name): return {'name':key,'valueFrom':{'secretKeyRef':{'name':name,'key':key}}}

# Refuse to rotate credentials underneath a previously initialized proof database.
existing = kubectl('get', 'namespace', ns, '--ignore-not-found', '-o', 'name').strip()
if existing:
    raise SystemExit('Proof namespace already exists. Reuse its private token, or create a fresh disposable cluster.')
for name in [ns, runner_ns]: apply({'apiVersion':'v1','kind':'Namespace','metadata':{'name':name}})
token = secrets.token_urlsafe(32)
(work / 'token').write_text(token); os.chmod(work / 'token', 0o600)
identity = [{'subject':'service:browser-proof','teams':['proof-team'],
             'scopes':['definitions:read','definitions:write','runs:execute','runs:read','runs:cancel'],
             'tokenSha256':hashlib.sha256(token.encode()).hexdigest(),
             'expiresAt':(dt.datetime.now(dt.timezone.utc)+dt.timedelta(hours=12)).isoformat()}]
secret('platform-auth', {'identities.json':json.dumps(identity)})
password = secrets.token_urlsafe(24)
secret('platform-db', {'username':'perf','password':password})
secret('proof-postgres', {'POSTGRES_USER':'perf','POSTGRES_PASSWORD':password,'POSTGRES_DB':'perfplatform'})
storage_user = secrets.token_hex(8); storage_password = secrets.token_urlsafe(24)
secret('proof-minio', {'MINIO_ROOT_USER':storage_user,'MINIO_ROOT_PASSWORD':storage_password})
for namespace in [ns, runner_ns]:
    secret('storage-access', {'AWS_ACCESS_KEY_ID':storage_user,'AWS_SECRET_ACCESS_KEY':storage_password}, namespace)

deployment('postgres', 'postgres:16.2-alpine', 5432,
           [from_secret(k,'proof-postgres') for k in ['POSTGRES_USER','POSTGRES_PASSWORD','POSTGRES_DB']])
deployment('minio', 'perfplatform/minio-test:2024-11-07-1', 9000,
           [from_secret(k,'proof-minio') for k in ['MINIO_ROOT_USER','MINIO_ROOT_PASSWORD']], ['minio','server','/data'])
program = '''const http=require('http');http.createServer((req,res)=>{res.setHeader('Content-Type','text/html');res.end(req.url==='/finish'?'<h1>Journey complete</h1>':'<h1>Performance proof</h1><a href="/finish">Continue</a>')}).listen(8081,'0.0.0.0');'''
deployment('browser-target', 'perfplatform/playwright-runner:1.62.1-1', 8081, command_args=['node','-e',program])
for name in ['postgres','minio','browser-target']:
    kubectl('-n', ns, 'rollout', 'status', f'deployment/{name}', '--timeout=120s')
apply({'apiVersion':'v1','kind':'Pod','metadata':{'name':'proof-bucket','namespace':ns},'spec':{
    'restartPolicy':'Never','containers':[{'name':'create-bucket','image':'perfplatform/playwright-runner:1.62.1-1',
    'imagePullPolicy':'IfNotPresent','envFrom':[{'secretRef':{'name':'storage-access'}}],
    'env':[{'name':'AWS_DEFAULT_REGION','value':'us-east-1'},{'name':'AWS_EC2_METADATA_DISABLED','value':'true'}],
    'command':['aws','--endpoint-url',f'http://minio.{ns}.svc.cluster.local:9000','s3','mb','s3://perf-artifacts']}]}})
kubectl('-n',ns,'wait','--for=jsonpath={.status.phase}=Succeeded','pod/proof-bucket','--timeout=60s')
kubectl('-n',ns,'delete','pod/proof-bucket')
values = {
    'deploymentProfile':'production','image':{'repository':'perfplatform/platform-core','tag':'browser-verify'},
    'auth':{'serviceTokenSecret':'platform-auth'},'database':{'existingSecret':'platform-db'},
    'postgres':{'enabled':False,'externalUrl':f'jdbc:postgresql://postgres.{ns}.svc.cluster.local:5432/perfplatform'},
    'artifacts':{'bucket':'perf-artifacts','region':'us-east-1','endpoint':f'http://minio.{ns}.svc.cluster.local:9000',
                 'pathStyleAccess':True,'controlPlaneCredentialsSecret':'storage-access','runnerCredentialsSecret':'storage-access'},
    'runnerCell':{'namespace':runner_ns,'images':{'playwright':'perfplatform/playwright-runner:1.62.1-1'},
                  'egress':{'allowedCidrs':['10.244.0.0/16','10.96.0.0/12']},
                  'resources':{'cpuRequest':'250m','cpuLimit':'2','memoryRequest':'512Mi','memoryLimit':'2Gi'}},
    'prometheus':{'enabled':False},'grafana':{'enabled':False},'ingress':{'enabled':False},
    'teamConfigs':{},'resources':{'platformCore':{'requests':{'cpu':'250m','memory':'512Mi'},'limits':{'cpu':'2','memory':'2Gi'}}},
}
values_file=work/'values.json'; values_file.write_text(json.dumps(values)); os.chmod(values_file,0o600)
manifest=command(['helm','template','proof','helm/performance-platform','--namespace',ns,'-f',str(values_file)])
kubectl('apply','-f','-',data=manifest)
kubectl('-n',ns,'rollout','status','deployment/proof-performance-platform','--timeout=180s')
print('Production-profile proof stack ready. Start a port-forward:')
print(f'kubectl --context {context} -n {ns} port-forward service/proof-performance-platform 18080:8080')
print(f'Use the private access token at {work / "token"}. No credential was printed.')
