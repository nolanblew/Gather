import { createServer } from 'node:http';
import { readFile, stat } from 'node:fs/promises';
import { resolve, extname, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createUserStore, InputError, WORKSPACE_BYTE_LIMIT } from './store.mjs';
import { createSearchService } from './search.mjs';
import { createAuth } from './auth.mjs';

const BODY_LIMIT = WORKSPACE_BYTE_LIMIT;
const mime = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png', '.jpg': 'image/jpeg', '.webp': 'image/webp', '.ico': 'image/x-icon', '.woff2': 'font/woff2', '.webmanifest': 'application/manifest+json' };
async function body(req, limit = BODY_LIMIT) {
  const chunks = []; let size = 0;
  for await (const chunk of req) { size += chunk.length; if (size > limit) throw new InputError(limit === BODY_LIMIT ? 'Request exceeds 5 MiB limit.' : 'Request exceeds 8 KiB limit.', 413); chunks.push(chunk); }
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { throw new InputError('Invalid JSON body.'); }
}
function checkOrigin(req) {
  if (req.headers['sec-fetch-site'] === 'cross-site') throw new InputError('Cross-site writes are not allowed.', 403);
  if (!req.headers.origin) return;
  let origin; try { origin = new URL(req.headers.origin); } catch { throw new InputError('Invalid origin.', 403); }
  if (!['http:', 'https:'].includes(origin.protocol) || origin.host !== req.headers.host) throw new InputError('Origin must match this server.', 403);
}
export function createApp({ dataDir = process.env.DATA_DIR || resolve('data'), distDir = resolve('dist'), searchEnv = process.env, authEnv = process.env } = {}) {
  let store;
  let auth;
  let search;
  try {
    auth = createAuth({ dataDir, env: authEnv });
    store = createUserStore(dataDir, { legacyOwnerId: auth.legacyOwnerId, username: auth.username });
    search = createSearchService({ dataDir, env: searchEnv });
  }
  catch (error) { auth?.close(); store?.close(); throw error; }
  const server = createServer(async (req, res) => {
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('X-Frame-Options', 'DENY');
    res.setHeader('Referrer-Policy', 'same-origin');
    res.setHeader('Content-Security-Policy', "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; script-src 'self'; connect-src 'self'; font-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'");
    const json = (status, value) => { res.statusCode = status; res.setHeader('Content-Type', 'application/json; charset=utf-8'); res.setHeader('Cache-Control', 'no-store'); res.end(JSON.stringify(value)); };
    try {
      const url = new URL(req.url, 'http://localhost');
      if (url.pathname.startsWith('/api/')) {
        // Native reads retain the same origin policy as writes. Native clients
        // omit browser Origin/Fetch Metadata headers; CORS is never enabled.
        if (req.headers.authorization || url.pathname.startsWith('/api/native/')) checkOrigin(req);
        if (req.method === 'POST') {
          checkOrigin(req);
          if (!req.headers['content-type']?.toLowerCase().startsWith('application/json')) throw new InputError('Use application/json.', 415);
        }
        if (req.method === 'GET' && url.pathname === '/api/health') { store.health(); return json(200, { status: 'ok' }); }
        if (req.method === 'GET' && url.pathname === '/api/capabilities') return json(200, { apiVersion: 1, workspaceVersion: 2, authentication: ['cookie', 'bearer'], features: ['sharing', 'search', 'import', 'export'] });
        if (url.pathname.startsWith('/api/native/auth/')) {
          const method=url.pathname.slice('/api/native/auth/'.length);
          if (method==='session' && req.method==='GET') return json(200,auth.session(req,'bearer'));
          if (req.method==='POST' && ['register','login','password','logout'].includes(method)) {
            const input=await body(req,8192);
            if (method==='logout') return json(200,auth.logout(req,'bearer'));
            const result=await auth[method](input,req,'bearer');
            store.ensure(result.userId);
            return json(200,result);
          }
          return json(404,{error:'API route not found.'});
        }
        if (url.pathname === '/api/auth/session' && req.method === 'GET') return json(200, auth.session(req));
        if (url.pathname === '/api/auth/setup' && req.method === 'POST') throw new InputError('Create your own account to get started.', 410);
        if (['/api/auth/register', '/api/auth/login'].includes(url.pathname) && req.method === 'POST') { const result = await auth[url.pathname.endsWith('/register') ? 'register' : 'login'](await body(req, 8192), req); store.ensure(result.userId); res.setHeader('Set-Cookie', result.setCookie); const {setCookie,...session}=result; return json(200,session); }
        if (url.pathname === '/api/auth/logout' && req.method === 'POST') { const result = auth.logout(req); res.setHeader('Set-Cookie', result.setCookie); return json(200, auth.session(req)); }
        if (url.pathname === '/api/auth/password' && req.method === 'POST') { const result = await auth.password(await body(req, 8192), req); res.setHeader('Set-Cookie', result.setCookie); const {setCookie,...session}=result; return json(200,session); }
        const actor = auth.current(req);
        if (!actor) return json(401, { error: 'Authentication required.' });
        const getWorkspace = () => { if (auth.current(req)?.id !== actor.id) throw new InputError('Authentication required.',401); return store.get(actor.id); };
        if (req.method === 'GET' && url.pathname === '/api/sharing') return json(200,store.sharing(actor.id,url.searchParams.get('listId')));
        if (req.method === 'POST' && ['/api/sharing/invite','/api/sharing/revoke','/api/sharing/remove','/api/sharing/leave','/api/invitations/join','/api/invitations/preview'].includes(url.pathname)) {
          const input=await body(req,8192);
          if(auth.current(req)?.id!==actor.id) throw new InputError('Authentication required.',401);
          if(!input || typeof input!=='object' || Array.isArray(input)) throw new InputError('Expected an object.');
          if (url.pathname === '/api/invitations/preview') return json(200,store.preview(actor.id,input.token));
          if (url.pathname === '/api/invitations/join') return json(200,store.join(actor.id,input.token));
          const method=url.pathname.slice('/api/sharing/'.length);
          return json(200,store[method](actor.id,input.listId,input.userId));
        }
        if (req.method === 'GET' && url.pathname === '/api/search/status') return json(200, search.status());
        if (req.method === 'GET' && ['/api/workspace', '/api/export'].includes(url.pathname)) { if (url.pathname === '/api/export') res.setHeader('Content-Disposition', 'attachment; filename="gather-backup.json"'); return json(200, url.pathname === '/api/export' ? store.export(actor.id) : getWorkspace()); }
        if (req.method === 'POST' && ['/api/actions', '/api/import', '/api/search'].includes(url.pathname)) {
          const input = await body(req, url.pathname === '/api/search' ? 8192 : BODY_LIMIT);
          if(auth.current(req)?.id!==actor.id) throw new InputError('Authentication required.',401);
          if (url.pathname === '/api/search') {
            if (!input || typeof input !== 'object' || Array.isArray(input)) throw new InputError('Expected a search object.');
            const { query, scope = 'library', categoryId = null, limit = 60 } = input;
            if (typeof query !== 'string' || query.length > 500) throw new InputError('Search query must be text up to 500 characters.');
            if (!['library', 'favorites', 'templates', 'archive', 'all'].includes(scope)) throw new InputError('Unknown search scope.');
            if (!Number.isSafeInteger(limit) || limit < 1 || limit > 125) throw new InputError('Search limit must be an integer from 1 to 125.');
            if (categoryId !== null && (typeof categoryId !== 'string' || !getWorkspace().categories.some(c => c.id === categoryId))) throw new InputError('Search category not found.');
            return json(200, await search.search({ query, scope, categoryId, limit }, getWorkspace));
          }
          return json(200, url.pathname === '/api/import' ? store.import(input,actor.id) : store.action(input,actor.id));
        }
        return json(404, { error: 'API route not found.' });
      }
      if (!['GET', 'HEAD'].includes(req.method)) return json(405, { error: 'Method not allowed.' });
      let decoded; try { decoded = decodeURIComponent(url.pathname); } catch { throw new InputError('Invalid path.'); }
      if (decoded.includes('\0') || decoded.includes('\\')) throw new InputError('Invalid path.');
      const root = resolve(distDir), target = resolve(root, '.' + decoded);
      if (target !== root && !target.startsWith(root + sep)) throw new InputError('Invalid path.', 403);
      let file = target;
      try { if (!(await stat(file)).isFile()) file = resolve(root, 'index.html'); } catch { file = extname(decoded) ? null : resolve(root, 'index.html'); }
      if (!file) return json(404, { error: 'File not found.' });
      let content; try { content = await readFile(file); } catch { return json(404, { error: 'Frontend has not been built. Run npm run build.' }); }
      res.setHeader('Content-Type', mime[extname(file)] || 'application/octet-stream');
      res.setHeader('Cache-Control', extname(file) === '.html' || url.pathname === '/sw.js' || extname(file) === '.webmanifest' ? 'no-cache' : 'public, max-age=3600');
      res.end(req.method === 'HEAD' ? undefined : content);
    } catch (error) { if (!(error instanceof InputError)) console.error('Request failed:', error); if (!res.headersSent) json(error.status || 500, { error: error instanceof InputError ? error.message : 'Unable to save changes. Please try again.' }); else res.end(); }
  });
  server.on('close', () => { search.close(); auth.close(); store.close(); });
  return server;
}
export async function startServer(options = {}) {
  const server = createApp(options);
  const port = options.port ?? Number(process.env.PORT || 3000), host = options.host ?? process.env.HOST ?? '127.0.0.1';
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(port, host, resolve); });
  return server;
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const server = await startServer();
  console.log(`Gather is ready at http://${process.env.HOST || '127.0.0.1'}:${server.address().port}`);
  for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => server.close(() => process.exit(0)));
}
