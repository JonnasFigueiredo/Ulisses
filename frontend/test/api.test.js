import { afterEach, beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import {
  api, ApiError, AUTH_EXPIRED_EVENT, getServerUrl, getToken, setServerUrl, setToken,
} from '../src/api.js';

const originalFetch = globalThis.fetch;

beforeEach(() => {
  const values = new Map();
  globalThis.localStorage = {
    getItem: (key) => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: (key) => values.delete(key),
  };
  globalThis.window = new EventTarget();
});

afterEach(() => {
  globalThis.fetch = originalFetch;
  delete globalThis.localStorage;
  delete globalThis.window;
});

test('envia JSON e token ao servidor configurado', async () => {
  setServerUrl(' https://ulisses.example/ ');
  setToken('session-token');
  globalThis.fetch = async (url, options) => {
    assert.equal(url, 'https://ulisses.example/api/face/status');
    assert.equal(options.headers.Authorization, 'Bearer session-token');
    assert.equal(options.headers['Content-Type'], 'application/json');
    assert.equal(options.body, '{"value":1}');
    return Response.json({ ok: true });
  };
  assert.deepEqual(await api('/api/face/status', { method: 'POST', body: { value: 1 } }), { ok: true });
});

test('preserva boundary do multipart e nao envia JWT no login', async () => {
  setToken('old-token');
  const form = new FormData();
  form.append('frame', new Blob(['frame'], { type: 'image/jpeg' }), 'pose.jpg');
  globalThis.fetch = async (_url, options) => {
    assert.equal(options.body, form);
    assert.equal(options.headers['Content-Type'], undefined);
    assert.equal(options.headers.Authorization, undefined);
    return Response.json({ ok: true });
  };
  await api('/api/auth/login', { method: 'POST', form, authenticated: false });
});

test('trocar de servidor limpa a sessao sem vazar o JWT anterior', () => {
  setServerUrl('https://first.example');
  setToken('first-token');
  setServerUrl('https://first.example/');
  assert.equal(getToken(), 'first-token');
  setServerUrl('https://second.example');
  assert.equal(getToken(), null);
  assert.equal(getServerUrl(), 'https://second.example');
});

test('rejeita URL invalida sem substituir o servidor nem a sessao', () => {
  setServerUrl('https://first.example');
  setToken('first-token');
  for (const url of ['javascript:alert(1)', 'https://user:secret@example.com', 'https://example.com?next=/api', 'not a url']) {
    assert.throws(() => setServerUrl(url));
  }
  assert.equal(getServerUrl(), 'https://first.example');
  assert.equal(getToken(), 'first-token');
});

test('401 invalida a sessao atual e avisa o contexto de autenticacao', async () => {
  setToken('expired-token');
  let expired = 0;
  window.addEventListener(AUTH_EXPIRED_EVENT, () => { expired += 1; });
  globalThis.fetch = async () => Response.json({ error: 'Sessao expirada' }, { status: 401 });
  await assert.rejects(api('/api/auth/me'), { status: 401, message: 'Sessao expirada' });
  assert.equal(getToken(), null);
  assert.equal(expired, 1);
});

test('401 atrasado de uma sessao antiga nao encerra um novo login', async () => {
  setToken('old-token');
  globalThis.fetch = async () => {
    setToken('new-token');
    return Response.json({ error: 'Sessao expirada' }, { status: 401 });
  };
  await assert.rejects(api('/api/auth/me'), { status: 401 });
  assert.equal(getToken(), 'new-token');
});

test('falhas transitorias preservam a sessao para nova tentativa', async () => {
  setToken('valid-token');
  globalThis.fetch = async () => { throw new TypeError('Failed to fetch'); };
  await assert.rejects(api('/api/auth/me'), (error) => error instanceof ApiError && error.status === 0);
  assert.equal(getToken(), 'valid-token');
  globalThis.fetch = async () => Response.json({ error: 'Indisponivel' }, { status: 503 });
  await assert.rejects(api('/api/auth/me'), { status: 503 });
  assert.equal(getToken(), 'valid-token');
});

test('retorna erro explicito para HTML e aceita resposta 204', async () => {
  globalThis.fetch = async () => new Response('<html>SPA fallback</html>');
  await assert.rejects(api('/api/auth/me'), { status: 502 });
  globalThis.fetch = async () => new Response(null, { status: 204 });
  assert.equal(await api('/api/face/enrollment', { method: 'DELETE' }), null);
});

test('requisicao sem resposta termina no timeout e aborta o transporte', async () => {
  let aborted = false;
  globalThis.fetch = async (_url, { signal }) => new Promise((_resolve, reject) => {
    signal.addEventListener('abort', () => {
      aborted = true;
      reject(signal.reason);
    }, { once: true });
  });
  await assert.rejects(api('/api/auth/me', { timeoutMs: 10 }), /demorou demais/);
  assert.equal(aborted, true);
});

test('timeout tambem abrange a leitura do corpo da resposta', async () => {
  globalThis.fetch = async (_url, { signal }) => ({
    status: 200,
    ok: true,
    text: () => new Promise((_resolve, reject) => {
      signal.addEventListener('abort', () => reject(signal.reason), { once: true });
    }),
  });
  await assert.rejects(api('/api/auth/me', { timeoutMs: 10 }), /demorou demais/);
});

test('cancelamento do chamador conserva o motivo de aborto', async () => {
  const controller = new AbortController();
  globalThis.fetch = async (_url, { signal }) => new Promise((_resolve, reject) => {
    signal.addEventListener('abort', () => reject(signal.reason), { once: true });
  });
  const result = api('/api/auth/me', { signal: controller.signal });
  controller.abort();
  await assert.rejects(result, { name: 'AbortError' });
});
