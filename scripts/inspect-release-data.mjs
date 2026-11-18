// Run inside a Gather container with a read-only data mount. Reports hashes and
// counts only, never account details, list contents, or session material.
import { DatabaseSync } from 'node:sqlite';
import { createHash } from 'node:crypto';
import { existsSync, copyFileSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const root = process.argv[2] || '/app/data';
const organization = process.argv[3] === 'organization-v3';
// A cleanly stopped WAL database can require a new shared-memory sidecar even
// when opened read-only. Inspect a temporary copy so the source mount stays
// genuinely read-only. Retain a WAL when present; never copy only its main DB.
const snapshot = mkdtempSync(join(tmpdir(), 'gather-verify-'));
const report = {};
function stable(value) {
  if (Array.isArray(value)) return value.map(stable);
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map(key => [key, stable(value[key])]));
  return value;
}

try {
for (const filename of ['account.sqlite', 'workspace.sqlite', 'gather.sqlite']) {
  const source = join(root, filename);
  if (!existsSync(source)) continue;
  const path = join(snapshot, filename);
  copyFileSync(source, path);
  if (existsSync(source + '-wal')) copyFileSync(source + '-wal', path + '-wal');
  const db = new DatabaseSync(path, { readOnly: true });
  const tables = db.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name").all();
  report[filename] = { integrity: db.prepare('PRAGMA integrity_check').get().integrity_check, tables: {} };
  for (const { name } of tables) {
    const quoted = '"' + name.replaceAll('"', '""') + '"';
    const columns = db.prepare(`PRAGMA table_info(${quoted})`).all().map(row => row.name);
    // The additive transport column is excluded to compare old cookie sessions
    // with the same records after native auth migration.
    const comparableColumns = columns.filter(column => organization || column !== 'transport');
    const selected = comparableColumns.map(column => '"' + column.replaceAll('"', '""') + '"').join(',');
    let rows = db.prepare(`SELECT ${selected} FROM ${quoted}`).all();
    if (organization && filename === 'gather.sqlite' && name === 'user_workspaces') {
      report.workspaceVersions = {};
      rows = rows.map(row => {
        let workspace;
        try { workspace = JSON.parse(row.document); } catch { throw new Error('Invalid stored workspace document.'); }
        if (!Number.isInteger(workspace.version)) throw new Error('Invalid stored workspace version.');
        report.workspaceVersions[workspace.version] = (report.workspaceVersions[workspace.version] || 0) + 1;
        delete workspace.version;
        return { ...row, document: stable(workspace) };
      });
    }
    if (organization && filename === 'gather.sqlite' && name === 'user_migrations') {
      report.organizationMigration = rows.some(row => row.name === 'workspace-v3');
      rows = rows.filter(row => row.name !== 'workspace-v3');
    }
    rows = rows.map(row => JSON.stringify(row)).sort();
    report[filename].tables[name] = { count: rows.length, sha256: createHash('sha256').update(JSON.stringify(rows)).digest('hex') };
  }
  db.close();
}
console.log(JSON.stringify(report, null, 2));
} finally {
  rmSync(snapshot, { recursive: true, force: true });
}
