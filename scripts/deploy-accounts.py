"""Deploy a verified accounts release on the LAN host, preserving a rollback volume.

Invoke with release directory, image tag, and a NEW volume name. The optional
update mode detects supported workspace version changes. Explicit organization
migration modes apply their corresponding version checks. Only Gather
is stopped/recreated. The original volume is never mounted writable by the new
release.
"""
import json
import ipaddress
import hashlib
import os
from pathlib import Path
import re
import subprocess
import signal
import sys
import time
import tarfile
import urllib.request


def run(*args, **kwargs):
    return subprocess.check_output(args, text=True, **kwargs).strip()


def inventory():
    ids = run('docker', 'ps', '-aq').split()
    return json.loads(run('docker', 'inspect', *ids)) if ids else []


def api(path):
    with urllib.request.urlopen(base_url + '/api/' + path, timeout=3) as response:
        return json.load(response)


def compose(directory, *args):
    # Use this release's .env, even when the invoking agent has API keys or
    # Compose settings in its own environment.
    variables = set(re.findall(r'\$\{(\w+)', (directory / 'compose.lan.yaml').read_text()))
    env = {k: v for k, v in os.environ.items() if k not in variables and not k.startswith('COMPOSE_')}
    return run('docker', 'compose', '-p', 'gather-lan', '--env-file', '.env', '-f', 'compose.lan.yaml',
               *args, cwd=directory, env=env)


def compose_up(directory):
    compose(directory, 'up', '-d', '--pull', 'never', '--no-deps', 'gather')


def healthcheck():
    for attempt in range(45):
        try:
            if api('health').get('status') == 'ok':
                return
        except (OSError, ValueError):
            pass
        time.sleep(1)
    raise RuntimeError('Gather release failed health checks')


def data_fingerprint(volume, runtime_image, validation_mode):
    return json.loads(run('docker', 'run', '--rm', '--network', 'none', '--user', 'root',
        '--mount', f'type=volume,source={volume},target=/app/data,readonly',
        '--mount', f'type=bind,source={release},target=/verification,readonly',
        runtime_image, 'node', '/verification/inspect-release-data.mjs', '/app/data', validation_mode))


organization_versions = {'organization-v3': (2, 3), 'organization-v4': (3, 4)}


def validate_migration(before, after, mode, *, allow_new_tables):
    if mode in organization_versions:
        previous_version, target_version = organization_versions[mode]
        for snapshot, version, migrated in [(before, str(previous_version), False), (after, str(target_version), True)]:
            versions = snapshot.get('workspaceVersions', {})
            count = snapshot['gather.sqlite']['tables']['user_workspaces']['count']
            if versions != ({version: count} if count else {}):
                raise RuntimeError(f'Unexpected workspace versions before deployment validation ({version} expected)')
            if snapshot.get('organizationMigration') != migrated:
                raise RuntimeError('Unexpected organization migration record')
        before, after = ({k: v for k, v in snapshot.items() if k not in {'organizationMigration', 'workspaceVersions'}}
                         for snapshot in (before, after))
    for database in ('account.sqlite', 'gather.sqlite'):
        if any(snapshot.get(database, {}).get('integrity') != 'ok' for snapshot in (before, after)):
            raise RuntimeError(f'Integrity check failed for {database}')
    # A release may introduce an empty table (for example offline action
    # receipts). Every table present before startup must still match exactly.
    if allow_new_tables:
        after = after.copy()
        for filename in before.keys() & after.keys():
            if filename.endswith('.sqlite'):
                old_tables = before[filename]['tables']
                tables = after[filename]['tables']
                comparable = {name: info for name, info in tables.items() if name in old_tables or info['count']}
                after[filename] = {**after[filename], 'tables': comparable}
    if before != after:
        raise RuntimeError('Account or workspace data changed during migration')


def verify_capabilities(capabilities, mode):
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
if (mode not in {'accounts', 'update'} and mode not in organization_versions) or len(sys.argv) > 5:
    raise SystemExit('Unexpected validation mode')
root = Path(os.environ.get('GATHER_DEPLOY_ROOT', '/home/nolan/dockers/gather')).resolve()
if release.parent != root / 'releases':
    raise SystemExit('Unexpected release directory')
if not re.fullmatch(r'gather_lan_accounts_[0-9a-z_]+', new_volume):
    raise SystemExit('Unexpected new volume name')
if new_volume in run('docker', 'volume', 'ls', '--format', '{{.Name}}').splitlines():
    raise SystemExit('New volume already exists; inspect it before retrying')
current = root / 'current'
if not current.is_symlink():
    raise SystemExit('Expected current release symlink')
previous = current.resolve()
before = inventory()
managed = [c for c in before if (c['Config'].get('Labels') or {}).get('com.docker.compose.project') == 'gather-lan']
if len(managed) != 1 or not managed[0]['State']['Running']:
    raise SystemExit('Expected exactly one running Gather container')
active = managed[0]
if active['Config']['Labels'].get('com.docker.compose.service') != 'gather':
    raise SystemExit('Unexpected Compose service')
binding = active['NetworkSettings']['Ports']['3000/tcp'][0]
host = ipaddress.ip_address(binding['HostIp'])
if host.is_unspecified:
    host = ipaddress.ip_address('127.0.0.1' if host.version == 4 else '::1')
base_url = f"http://{'[' + str(host) + ']' if host.version == 6 else host}:{int(binding['HostPort'])}"
previous_version = api('capabilities')['workspaceVersion'] if mode == 'update' else None
mounts = [m for m in active['Mounts'] if m['Destination'] == '/app/data' and m['Type'] == 'volume']
if len(mounts) != 1 or not mounts[0]['Name'].startswith('gather_lan_'):
    raise SystemExit('Unexpected Gather data mount')
old_volume = mounts[0]['Name']
old_image = active['Image']
if mode == 'update':
    config = json.loads(compose(previous, 'config', '--format', 'json'))
    service = config['services']['gather']
    volume_name, = [v['source'] for v in service['volumes'] if v['target'] == '/app/data' and v['type'] == 'volume']
    rollback_image = run('docker', 'image', 'inspect', '--format', '{{.Id}}', service['image'])
    if rollback_image != old_image or config['volumes'][volume_name]['name'] != old_volume:
        raise SystemExit('The current release does not match the running image and volume. Check the release directory before deploying.')
# Preserve dotenv quoting, multiline values and comments. Docker Compose uses
# the last assignment when a key appears more than once.
env_path = release / '.env'
with open(env_path, 'x', encoding='utf8', opener=lambda path, flags: os.open(path, flags, 0o600)) as stream:
    stream.write((previous / '.env').read_text())
    stream.write(f'\nGATHER_IMAGE={image}\nGATHER_DATA_VOLUME={new_volume}\n')
    if mode == 'accounts':
        stream.write('GATHER_COOKIE_SECURE=true\n')
expected_image = json.loads(run('docker', 'image', 'inspect', image))[0]['Id']
if not (release / 'inspect-release-data.mjs').is_file():
    raise SystemExit('Missing data preservation verifier')
run('docker', 'volume', 'create', new_volume)
backup_names = {'accounts': 'data-before-accounts.tar', 'update': 'data-before-update.tar'}
backup = release / backup_names.get(mode, 'data-before-organization.tar')
backup.touch(mode=0o600, exist_ok=False)


def interrupt_deploy(signum, frame):
    raise InterruptedError(f'Deployment interrupted by signal {signum}')


signal.signal(signal.SIGTERM, interrupt_deploy)
signal.signal(signal.SIGHUP, interrupt_deploy)
publishing = False
try:
    run('docker', 'stop', active['Id'])
    run('docker', 'run', '--rm', '--network', 'none', '--user', 'root',
        '--mount', f'type=volume,source={old_volume},target=/source,readonly',
        '--mount', f'type=bind,source={release},target=/backup',
        old_image, 'tar', '-C', '/source', '-cf', f'/backup/{backup.name}', '.')
    os.chmod(backup, 0o600)
    with tarfile.open(backup) as archive:
        members = archive.getnames()
        if not any(name.endswith('account.sqlite') for name in members) or not any(name.endswith('gather.sqlite') for name in members):
            raise RuntimeError('Backup is missing required databases')
    with backup.open('rb') as stream:
        backup_sha256 = hashlib.file_digest(stream, 'sha256').hexdigest()
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
    capabilities = json.loads(isolated)
    validation_mode = mode
    if mode == 'update' and previous_version != capabilities.get('workspaceVersion'):
        pair = previous_version, capabilities.get('workspaceVersion')
        validation_mode = next((name for name, versions in organization_versions.items() if versions == pair), None)
        if not validation_mode:
            raise RuntimeError(f'Unsupported workspace version change {pair}')
    verify_capabilities(capabilities, validation_mode)
    data_before = data_fingerprint(old_volume, old_image, validation_mode)
    data_after = data_fingerprint(new_volume, image, validation_mode)
    validate_migration(data_before, data_after, validation_mode, allow_new_tables=mode == 'update')
    publishing = True
    compose_up(release)
    healthcheck()
    if mode == 'update':
        with urllib.request.urlopen(base_url + '/', timeout=5) as page:
            html = page.read().decode('utf-8')
        entry = re.search(r'<script\b[^>]* src="(/assets/[^"]+\.js)"', html)
        if 'id="root"' not in html or not entry:
            raise RuntimeError('Published web page is missing the Gather app entry point')
        with urllib.request.urlopen(base_url + entry[1], timeout=5) as bundle:
            if 'javascript' not in bundle.headers.get_content_type() or not bundle.read(1):
                raise RuntimeError('Published web app bundle is unavailable')
    live_capabilities = api('capabilities')
    verify_capabilities(live_capabilities, validation_mode)
    if live_capabilities.get('workspaceVersion') != capabilities.get('workspaceVersion'):
        raise RuntimeError('Published workspace version differs from the verified candidate')
    session = api('auth/session')
    if session.get('authenticated') or session.get('setupRequired'):
        raise RuntimeError('Unexpected anonymous account session response')
    after = inventory()
    deployed = [c for c in after if (c['Config'].get('Labels') or {}).get('com.docker.compose.project') == 'gather-lan']
    if len(deployed) != 1 or deployed[0]['Image'] != expected_image or not any(m.get('Name') == new_volume and m['Destination'] == '/app/data' for m in deployed[0]['Mounts']):
        raise RuntimeError('Deployed image or data volume does not match the verified release')
    unrelated = {c['Id']: (c['State']['StartedAt'], c['RestartCount']) for c in after}
    unrelated_unchanged = all(
        unrelated.get(c['Id']) == (c['State']['StartedAt'], c['RestartCount']) for c in before if c not in managed
    )
    if mode != 'update' and not unrelated_unchanged:
        raise RuntimeError('An unrelated container changed during migration')
    temporary = current.with_name('current.accounts-next')
    if temporary.exists() or temporary.is_symlink():
        raise RuntimeError('Unexpected pending release symlink')
    report = {'image': image, 'imageId': expected_image, 'baseRuntimeImageId': old_image, 'validationMode': validation_mode,
              'volume': new_volume, 'previousRelease': str(previous),
              'rollbackVolume': old_volume, 'backup': str(backup), 'backupSha256': backup_sha256,
              'containersChecked': len(before), 'unrelatedContainersUnchanged': unrelated_unchanged,
              'accountAndWorkspaceDataPreserved': True, 'dataFingerprint': data_after, 'capabilities': live_capabilities}
    (release / 'deployment.json').write_text(json.dumps(report, indent=2), encoding='utf8')
    temporary.symlink_to(release, target_is_directory=True)
    temporary.replace(current)
except (Exception, KeyboardInterrupt):
    if current.resolve() == release:
        raise  # The atomic publication already completed.
    if publishing and (mode == 'update' or mode in organization_versions):
        candidate = next((c for c in inventory() if c['Image'] == expected_image and
                          (c['Config'].get('Labels') or {}).get('com.docker.compose.project') == 'gather-lan'), None)
        if candidate and candidate['State']['Running']:
            run('docker', 'stop', candidate['Id'])
        try:
            changed = data_fingerprint(new_volume, image, validation_mode) != data_after
        except Exception:
            # A failed comparison cannot rule out writes to the candidate.
            compose_up(release)
            raise
        if changed:
            # The new server may already have accepted a write. Keep it on
            # the migrated copy so an automatic rollback cannot lose data.
            compose_up(release)
            raise RuntimeError('Candidate volume changed after publication; automatic rollback withheld to preserve new writes')
    compose_up(previous)
    healthcheck()
    raise
print(json.dumps(report), flush=True)
