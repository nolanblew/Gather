"""Run on the LAN Docker host after uploading a verified Gather release."""
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
import urllib.request

release = Path(sys.argv[1]).resolve()
if release.parent != Path('/home/nolan/dockers/gather/releases'):
    raise SystemExit('Unexpected release directory')
image = sys.argv[2]
address, port = '192.168.68.68', 48732
volume = 'gather_lan_data'

def run(*args, **kwargs):
    return subprocess.check_output(args, text=True, **kwargs).strip()

def containers():
    ids = run('docker', 'ps', '-aq').split()
    return json.loads(run('docker', 'inspect', *ids)) if ids else []

before = containers()
managed = [c for c in before if (c['Config'].get('Labels') or {}).get('com.docker.compose.project') == 'gather-lan']
for c in before:
    if c in managed:
        continue
    for bindings in (c['HostConfig'].get('PortBindings') or {}).values():
        if any(str(b.get('HostPort')) == str(port) for b in bindings or []):
            raise SystemExit('Port is configured by ' + c['Name'])
if not any(c['State']['Running'] for c in managed):
    with socket.socket() as probe:
        probe.bind(('0.0.0.0', port))

url = f'http://{address}:{port}'
if managed:
    # Never replace an existing workspace with the uploaded initial snapshot.
    active = next((c for c in managed if c['State']['Running']), None)
    if not active:
        raise SystemExit('Start the existing Gather container before backing up and updating')
    export_code = "import {createStore} from './server/store.mjs'; const s=createStore('/app/data'); try {process.stdout.write(JSON.stringify(s.get()));} finally {s.close();}"
    backup = subprocess.check_output(['docker', 'exec', active['Id'], 'node', '--input-type=module', '-e', export_code])
    backup_path = release / 'backup-before-update.json'
    backup_path.touch(mode=0o600, exist_ok=True)
    backup_path.write_bytes(backup)

run('docker', 'load', '-i', str(release / 'image.tar'))
volumes = run('docker', 'volume', 'ls', '--format', '{{.Name}}').splitlines()
if volume not in volumes:
    run('docker', 'volume', 'create', volume)
    code = "import {readFileSync} from 'node:fs'; import {createStore} from './server/store.mjs'; const s=createStore('/app/data'); try {const w=s.import(JSON.parse(readFileSync('/initial.json','utf8')));console.log('Imported '+w.lists.length+' lists');} finally {s.close();}"
    print(run('docker', 'run', '--rm', '--network', 'none', '--cpus', '0.5', '--memory', '512m',
              '--mount', f'type=volume,source={volume},target=/app/data',
              '--mount', f'type=bind,source={release / "initial-workspace.json"},target=/initial.json,readonly',
              image, 'node', '--input-type=module', '-e', code), flush=True)

old_env = Path('/home/nolan/dockers/gather/current/.env')
settings = {}
if old_env.exists():
    for line in old_env.read_text().splitlines():
        if '=' in line and not line.lstrip().startswith('#'):
            key, value = line.split('=', 1)
            settings[key] = value
settings.update(GATHER_IMAGE=image, GATHER_BIND_ADDRESS=address, GATHER_HOST_PORT=str(port), GATHER_COOKIE_SECURE='true')
env_path = release / '.env'
env_path.touch(mode=0o600, exist_ok=True)
env_path.write_text(''.join(f'{key}={value}\n' for key, value in settings.items()), encoding='utf8')
subprocess.run(['docker', 'compose', '-f', 'compose.lan.yaml', 'up', '-d', '--pull', 'never'], cwd=release, check=True)
for attempt in range(60):
    try:
        with urllib.request.urlopen(url + '/api/health', timeout=3) as response:
            if json.load(response).get('status') == 'ok':
                break
    except Exception:
        time.sleep(1)
else:
    raise SystemExit('Gather did not become healthy')

after = containers()
if managed:
    updated = next(c for c in after if (c['Config'].get('Labels') or {}).get('com.docker.compose.project') == 'gather-lan' and c['State']['Running'])
    after_export = subprocess.check_output(['docker', 'exec', updated['Id'], 'node', '--input-type=module', '-e', export_code])
    if after_export != backup:
        raise SystemExit('Workspace changed across deployment; inspect backup before proceeding')
unrelated = {c['Id']: (c['State']['StartedAt'], c['RestartCount']) for c in after}
for c in before:
    if c not in managed and unrelated.get(c['Id']) != (c['State']['StartedAt'], c['RestartCount']):
        raise SystemExit('An unrelated container changed: ' + c['Name'])
current = Path('/home/nolan/dockers/gather/current')
if current.exists() and not current.is_symlink():
    raise SystemExit('Current release path is not a symlink')
temporary = current.with_name('current.next')
if temporary.is_symlink():
    temporary.unlink()
temporary.symlink_to(release, target_is_directory=True)
temporary.replace(current)
report = {'url': url, 'image': image, 'volume': volume, 'containersChecked': len(before), 'unrelatedContainersUnchanged': True}
(release / 'deployment.json').write_text(json.dumps(report, indent=2), encoding='utf8')
print(json.dumps(report), flush=True)
