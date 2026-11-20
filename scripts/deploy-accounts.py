"""Deploy a verified accounts release on the LAN host, preserving a rollback volume.

Invoke with release directory, image tag, and a NEW volume name. The optional
organization migration modes permit only the corresponding workspace version change. Only Gather
is stopped/recreated. The original volume is never mounted writable by the new
release.
"""
import json
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import tarfile
import urllib.request


def run(*args, **kwargs):
    return subprocess.check_output(args, text=True, **kwargs).strip()


def inventory():
    ids = run('docker', 'ps', '-aq').split()
    return json.loads(run('docker', 'inspect', *ids)) if ids else []


def healthcheck():
    for attempt in range(45):
        try:
            with urllib.request.urlopen('http://192.168.68.68:48732/api/health', timeout=3) as response:
                if json.load(response).get('status') == 'ok':
                    return
        except Exception:
            time.sleep(1)
    raise RuntimeError('Gather release failed health checks')


def data_fingerprint(volume, runtime_image):
    return json.loads(run('docker', 'run', '--rm', '--network', 'none', '--user', 'root',
        '--mount', f'type=volume,source={volume},target=/app/data,readonly',
        '--mount', f'type=bind,source={release},target=/verification,readonly',
        runtime_image, 'node', '/verification/inspect-release-data.mjs', '/app/data', mode))


organization_versions = {'organization-v3': (2, 3), 'organization-v4': (3, 4)}


def validate_migration(before, after):
    if mode in organization_versions:
        previous_version, target_version = organization_versions[mode]
        for snapshot, version, migrated in [(before, str(previous_version), False), (after, str(target_version), True)]:
            versions = snapshot.get('workspaceVersions', {})
            if versions != {version: snapshot['gather.sqlite']['tables']['user_workspaces']['count']}:
                raise RuntimeError(f'Unexpected workspace versions before deployment validation ({version} expected)')
            if snapshot.get('organizationMigration') != migrated:
                raise RuntimeError('Unexpected organization migration record')
        before, after = ({k: v for k, v in snapshot.items() if k not in {'organizationMigration', 'workspaceVersions'}}
                         for snapshot in (before, after))
    for database in ('account.sqlite', 'gather.sqlite'):
        if any(snapshot.get(database, {}).get('integrity') != 'ok' for snapshot in (before, after)):
            raise RuntimeError(f'Integrity check failed for {database}')
    if before != after:
        raise RuntimeError('Account or workspace data changed during migration')


def verify_capabilities(capabilities):
    if capabilities.get('apiVersion') != 1 or 'bearer' not in capabilities.get('authentication', []):
        raise RuntimeError('Native API capabilities are missing')
    if mode in organization_versions:
        features = {'list-organization', 'numbered-lists', 'guarded-order'}
        if mode == 'organization-v4':
            features.update({'multiple-recipients', 'bulk-assignments', 'visible-item-order'})
        if capabilities.get('workspaceVersion') != organization_versions[mode][1] or not features <= set(capabilities.get('features', [])):
            raise RuntimeError('Organization capabilities are missing')


release = Path(sys.argv[1]).resolve()
image, new_volume = sys.argv[2:4]
mode = sys.argv[4] if len(sys.argv) == 5 else 'accounts'
if (mode != 'accounts' and mode not in organization_versions) or len(sys.argv) > 5:
    raise SystemExit('Unexpected validation mode')
if release.parent != Path('/home/nolan/dockers/gather/releases'):
    raise SystemExit('Unexpected release directory')
if not re.fullmatch(r'gather_lan_accounts_[0-9a-z_]+', new_volume):
    raise SystemExit('Unexpected new volume name')
if new_volume in run('docker', 'volume', 'ls', '--format', '{{.Name}}').splitlines():
    raise SystemExit('New volume already exists; inspect it before retrying')
current = Path('/home/nolan/dockers/gather/current')
if not current.is_symlink():
    raise SystemExit('Expected current release symlink')
previous = current.resolve()
before = inventory()
managed = [c for c in before if (c['Config'].get('Labels') or {}).get('com.docker.compose.project') == 'gather-lan']
if len(managed) != 1 or not managed[0]['State']['Running']:
    raise SystemExit('Expected exactly one running Gather container')
active = managed[0]
mounts = [m for m in active['Mounts'] if m['Destination'] == '/app/data' and m['Type'] == 'volume']
if len(mounts) != 1 or not mounts[0]['Name'].startswith('gather_lan_'):
    raise SystemExit('Unexpected Gather data mount')
old_volume = mounts[0]['Name']
old_image = active['Image']
settings = {}
for line in (previous / '.env').read_text().splitlines():
    if '=' in line and not line.lstrip().startswith('#'):
        key, value = line.split('=', 1)
        settings[key] = value
settings.update(GATHER_IMAGE=image, GATHER_DATA_VOLUME=new_volume)
if mode == 'accounts':
    settings['GATHER_COOKIE_SECURE'] = 'true'
env_path = release / '.env'
env_path.touch(mode=0o600, exist_ok=False)
env_path.write_text(''.join(f'{key}={value}\n' for key, value in settings.items()), encoding='utf8')
os.chmod(env_path, 0o600)
expected_image = json.loads(run('docker', 'image', 'inspect', image))[0]['Id']
if not (release / 'inspect-release-data.mjs').is_file():
    raise SystemExit('Missing data preservation verifier')
run('docker', 'volume', 'create', new_volume)
backup = release / ('data-before-organization.tar' if mode in organization_versions else 'data-before-accounts.tar')
backup.touch(mode=0o600, exist_ok=False)
stopped = False
publishing = False
try:
    run('docker', 'stop', active['Id'])
    stopped = True
    data_before = data_fingerprint(old_volume, old_image)
    run('docker', 'run', '--rm', '--network', 'none', '--user', 'root',
        '--mount', f'type=volume,source={old_volume},target=/source,readonly',
        '--mount', f'type=bind,source={release},target=/backup',
        old_image, 'tar', '-C', '/source', '-cf', f'/backup/{backup.name}', '.')
    os.chmod(backup, 0o600)
    with tarfile.open(backup) as archive:
        members = archive.getnames()
        if not any(name.endswith('account.sqlite') for name in members) or not any(name.endswith('gather.sqlite') for name in members):
            raise RuntimeError('Backup is missing required databases')
    backup_sha256 = hashlib.file_digest(backup.open('rb'), 'sha256').hexdigest()
    run('docker', 'run', '--rm', '--network', 'none', '--user', 'root',
        '--mount', f'type=volume,source={new_volume},target=/target',
        '--mount', f'type=bind,source={release},target=/backup,readonly',
        old_image, 'tar', '-C', '/target', '-xf', f'/backup/{backup.name}')
    # Migrate and compare the cloned databases before publishing the new server.
    # This also avoids confusing legitimate post-start user edits with data loss.
    isolated = run('docker', 'run', '--rm', '--network', 'none',
        '--mount', f'type=volume,source={new_volume},target=/app/data',
        '-e', 'GATHER_COOKIE_SECURE=true', '-e', 'SEARCH_PROVIDER=fuzzy', image,
        'node', '--input-type=module', '-e',
        "import { startServer } from './server/index.js'; const server=await startServer({port:0,host:'127.0.0.1'}); "
        "try { const base='http://127.0.0.1:'+server.address().port; "
        "const health=await fetch(base+'/api/health'); if(!health.ok) throw Error('Health check failed'); "
        "console.log(JSON.stringify(await (await fetch(base+'/api/capabilities')).json())); } "
        "finally { await new Promise(resolve=>server.close(resolve)); }")
    verify_capabilities(json.loads(isolated))
    data_after = data_fingerprint(new_volume, image)
    validate_migration(data_before, data_after)
    publishing = True
    run('docker', 'compose', '-f', 'compose.lan.yaml', 'up', '-d', '--pull', 'never', '--no-deps', 'gather', cwd=release)
    healthcheck()
    with urllib.request.urlopen('http://192.168.68.68:48732/api/capabilities', timeout=3) as response:
        capabilities = json.load(response)
        verify_capabilities(capabilities)
    with urllib.request.urlopen('http://192.168.68.68:48732/api/auth/session', timeout=3) as response:
        session = json.load(response)
        if session.get('authenticated') or session.get('setupRequired'):
            raise RuntimeError('Unexpected anonymous account session response')
    after = inventory()
    deployed = [c for c in after if (c['Config'].get('Labels') or {}).get('com.docker.compose.project') == 'gather-lan']
    if len(deployed) != 1 or deployed[0]['Image'] != expected_image or not any(m.get('Name') == new_volume and m['Destination'] == '/app/data' for m in deployed[0]['Mounts']):
        raise RuntimeError('Deployed image or data volume does not match the verified release')
    unrelated = {c['Id']: (c['State']['StartedAt'], c['RestartCount']) for c in after}
    for container in before:
        if container not in managed and unrelated.get(container['Id']) != (container['State']['StartedAt'], container['RestartCount']):
            raise RuntimeError('An unrelated container changed: ' + container['Name'])
    temporary = current.with_name('current.accounts-next')
    if temporary.exists() or temporary.is_symlink():
        raise RuntimeError('Unexpected pending release symlink')
    report = {'image': image, 'imageId': expected_image, 'baseRuntimeImageId': old_image, 'validationMode': mode,
              'volume': new_volume, 'previousRelease': str(previous),
              'rollbackVolume': old_volume, 'backup': str(backup), 'backupSha256': backup_sha256,
              'containersChecked': len(before), 'unrelatedContainersUnchanged': True,
              'accountAndWorkspaceDataPreserved': True, 'dataFingerprint': data_after, 'capabilities': capabilities}
    (release / 'deployment.json').write_text(json.dumps(report, indent=2), encoding='utf8')
    temporary.symlink_to(release, target_is_directory=True)
    temporary.replace(current)
except Exception:
    if stopped:
        if publishing and mode in organization_versions:
            candidate = next((c for c in inventory() if c['Image'] == expected_image and
                              (c['Config'].get('Labels') or {}).get('com.docker.compose.project') == 'gather-lan'), None)
            if candidate and candidate['State']['Running']:
                run('docker', 'stop', candidate['Id'])
            if data_fingerprint(new_volume, image) != data_after:
                # The new server may already have accepted a write. Keep it on
                # the migrated copy so an automatic rollback cannot lose data.
                run('docker', 'compose', '-f', 'compose.lan.yaml', 'up', '-d', '--pull', 'never', '--no-deps', 'gather', cwd=release)
                raise RuntimeError('Candidate volume changed after publication; automatic rollback withheld to preserve new writes')
        subprocess.run(['docker', 'compose', '-f', 'compose.lan.yaml', 'up', '-d', '--pull', 'never', '--no-deps', 'gather'], cwd=previous, check=True)
        healthcheck()
    raise
print(json.dumps(report), flush=True)
