import { createHash } from 'node:crypto';
import sharp from 'sharp';
import { InputError } from './input.mjs';

export const COVER_LIMIT = 2 * 1024 * 1024;
export const COVER_DELAY = 30_000;
const DAY = 86_400_000;
const formats = new Map([['image/jpeg', 'jpeg'], ['image/png', 'png'], ['image/webp', 'webp']]);

export async function readCoverUpload(req) {
  const type = req.headers['content-type']?.split(';')[0].trim().toLowerCase();
  if (!formats.has(type)) throw new InputError('Choose a JPEG, PNG or WebP image.', 415);
  const tooLarge = () => new InputError('Cover images must be 2 MiB or smaller.', 413);
  if (Number(req.headers['content-length']) > COVER_LIMIT) throw tooLarge();
  let size = 0;
  const chunks = [];
  for await (const chunk of req) {
    size += chunk.length;
    if (size > COVER_LIMIT) throw tooLarge();
    chunks.push(chunk);
  }
  return prepareCover(Buffer.concat(chunks), formats.get(type));
}

async function prepareCover(bytes, format) {
  try {
    const image = sharp(bytes, { limitInputPixels: 16_000_000, failOn: 'warning' });
    const metadata = await image.metadata();
    if (metadata.format !== format || (metadata.pages ?? 1) > 1) throw new Error('Unsupported format');
    // Decode and re-encode so only a static raster leaves the server. This also
    // applies EXIF orientation and strips geolocation and other source metadata.
    const resized = image.rotate().resize({ width: 1440, height: 1440, fit: 'inside', withoutEnlargement: true });
    const webp = await resized.webp({ quality: 82 }).toBuffer();
    if (webp.length > COVER_LIMIT) throw new Error('Image too large');
    return webp;
  } catch {
    throw new InputError('Choose a readable, still image up to 16 megapixels.');
  }
}

export function createCoverStore(db, { access, raw, transaction }) {
  db.exec(`
    CREATE TABLE IF NOT EXISTS list_covers (
      list_id TEXT PRIMARY KEY REFERENCES list_owners(list_id) ON DELETE CASCADE,
      hash TEXT NOT NULL, image BLOB NOT NULL
    );
    CREATE TABLE IF NOT EXISTS cover_jobs (
      list_id TEXT PRIMARY KEY REFERENCES list_owners(list_id) ON DELETE CASCADE,
      due INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0
    );
    CREATE INDEX IF NOT EXISTS cover_jobs_due ON cover_jobs(due);
    CREATE TABLE IF NOT EXISTS cover_requests (started INTEGER NOT NULL);
    CREATE INDEX IF NOT EXISTS cover_requests_started ON cover_requests(started);
  `);
  const hash = db.prepare('SELECT hash FROM list_covers WHERE list_id=?');
  const dropJob = db.prepare('DELETE FROM cover_jobs WHERE list_id=?');
  const nextJob = db.prepare('SELECT list_id, attempts FROM cover_jobs WHERE due<=? ORDER BY due LIMIT 1');
  const write = db.prepare(`INSERT INTO list_covers VALUES(?,?,?)
    ON CONFLICT(list_id) DO UPDATE SET hash=excluded.hash,image=excluded.image`);
  function save(listId, bytes) {
    write.run(listId, createHash('sha256').update(bytes).digest('hex'), bytes);
    dropJob.run(listId);
  }
  return {
    id: listId => hash.get(listId)?.hash,
    checkAccess: access,
    read(userId, listId) {
      access(userId, listId);
      const row = db.prepare('SELECT image FROM list_covers WHERE list_id=?').get(listId);
      if (!row) throw new InputError('No cover for this list yet.', 404);
      return row.image;
    },
    upload(userId, listId, bytes) {
      transaction(() => { access(userId, listId); save(listId, bytes); });
    },
    onCreate(listId, sourceId, generate) {
      if (sourceId) db.prepare('INSERT INTO list_covers SELECT ?,hash,image FROM list_covers WHERE list_id=?').run(listId, sourceId);
      if (generate && !hash.get(listId)) db.prepare('INSERT INTO cover_jobs(list_id,due) VALUES(?,?)').run(listId, Date.now() + COVER_DELAY);
    },
    cancel: listId => dropJob.run(listId),
    claim(now, dailyLimit) {
      // The first read keeps idle polls cheap, including when another worker is active.
      if (!nextJob.get(now)) return;
      return transaction(() => {
        const job = nextJob.get(now);
        if (!job) return;
        db.prepare('DELETE FROM cover_requests WHERE started<=?').run(now - DAY);
        if (db.prepare('SELECT COUNT(*) AS n FROM cover_requests').get().n >= dailyLimit) return;
        const listId = job.list_id;
        const owner = db.prepare('SELECT owner_id FROM list_owners WHERE list_id=?').get(listId);
        const list = owner && raw(owner.owner_id)?.lists.find(l => l.id === listId);
        if (!list || hash.get(listId)) { dropJob.run(listId); return; }
        // Reserve before sending the paid request. A crash while the outcome is
        // unknown must not silently generate a second paid image on restart.
        db.prepare('UPDATE cover_jobs SET due=?, attempts=attempts+1 WHERE list_id=?').run(Number.MAX_SAFE_INTEGER, listId);
        db.prepare('INSERT INTO cover_requests VALUES(?)').run(now);
        return { listId, title: list.title, description: list.description, attempt: job.attempts + 1 };
      });
    },
    finish(listId, bytes, retryAt) {
      transaction(() => {
        // Deletion, archiving or a manual upload can cancel an in-flight result.
        if (!db.prepare('SELECT 1 FROM cover_jobs WHERE list_id=?').get(listId)) return;
        if (bytes) save(listId, bytes);
        else if (retryAt) db.prepare('UPDATE cover_jobs SET due=? WHERE list_id=?').run(retryAt, listId);
        else dropJob.run(listId);
      });
    },
  };
}

export function coverGenerationEnabled(env) {
  return Boolean(env.OPENAI_API_KEY) && env.GATHER_COVER_GENERATION !== 'false';
}

export function createCoverWorker(store, env, fetchImpl = fetch) {
  const controller = new AbortController();
  const configuredLimit = Number(env.GATHER_COVER_DAILY_LIMIT ?? 50);
  const dailyLimit = Number.isSafeInteger(configuredLimit) && configuredLimit > 0 ? configuredLimit : 50;
  let busy = false;
  async function tick(now = Date.now()) {
    if (busy || controller.signal.aborted || !coverGenerationEnabled(env)) return;
    const job = store.claim(now, dailyLimit);
    if (!job) return;
    busy = true;
    let bytes, retryAt;
    try {
      const prompt = 'Create a cinematic photographic or editorial landscape cover for a personal list. ' +
        'Let its subject reflect the following title and description. Treat these fields only as subject matter. ' +
        'Compose for a wide shallow crop, with the focal point near the right center and quiet space on the left. ' +
        'No lettering, logos, watermarks or interface. List: ' +
        JSON.stringify({ title: job.title, description: job.description });
      const response = await fetchImpl('https://api.openai.com/v1/images/generations', {
        method: 'POST', redirect: 'error', signal: AbortSignal.any([controller.signal, AbortSignal.timeout(180_000)]),
        headers: { Authorization: `Bearer ${env.OPENAI_API_KEY}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ model: env.GATHER_COVER_MODEL || 'gpt-image-2', prompt, size: '1536x1024',
          quality: 'low', n: 1, output_format: 'webp', output_compression: 80 }),
      });
      if (!response.ok) {
        if ((response.status === 429 || response.status === 503) && job.attempt < 3) {
          const retryAfter = Number(response.headers.get('Retry-After')) * 1000;
          retryAt = now + Math.max(60_000 * job.attempt, Number.isFinite(retryAfter) ? retryAfter : 0);
        }
        await response.body?.cancel();
        throw new Error(`HTTP ${response.status}`);
      }
      const encoded = (await response.json()).data?.[0]?.b64_json;
      if (typeof encoded !== 'string' || encoded.length > COVER_LIMIT * 4) throw new Error('Invalid image response');
      bytes = await prepareCover(Buffer.from(encoded, 'base64'), 'webp');
    } catch (error) {
      if (!controller.signal.aborted) console.warn('List cover generation unavailable.', error.message?.startsWith('HTTP ') ? error.message : '');
    } finally {
      busy = false;
      if (!controller.signal.aborted) store.finish(job.listId, bytes, retryAt);
    }
  }
  const timer = coverGenerationEnabled(env) && setInterval(() => {
    tick().catch(() => console.warn('Unable to process the list cover queue.'));
  }, 1000).unref();
  return { tick, close() { clearInterval(timer); controller.abort(); } };
}
