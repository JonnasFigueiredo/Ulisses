const TOKEN_KEY = 'ulisses_token';
const SERVER_KEY = 'ulisses_server';
export const AUTH_EXPIRED_EVENT = 'ulisses:auth-expired';
const DEFAULT_TIMEOUT_MS = 120000;

/**
 * URL base do backend. No navegador fica vazia (o proxy do Vite resolve /api);
 * no aplicativo Android (Capacitor) o usuario informa o IP do servidor na tela
 * de login, ex.: http://192.168.0.10:8080
 */
export function getServerUrl() {
  return localStorage.getItem(SERVER_KEY) || '';
}

export function setServerUrl(url) {
  const clean = (url || '').trim().replace(/\/+$/, '');
  if (clean) {
    let parsed;
    try {
      parsed = new URL(clean);
    } catch {
      throw new Error('Informe um endereco de servidor HTTP ou HTTPS valido.');
    }
    if (!['http:', 'https:'].includes(parsed.protocol) || parsed.username || parsed.password || parsed.search || parsed.hash) {
      throw new Error('Informe um endereco HTTP ou HTTPS sem credenciais, parametros ou fragmentos.');
    }
  }
  // Uma sessao pertence ao servidor que a emitiu.
  if (clean !== getServerUrl()) clearToken();
  if (clean) {
    localStorage.setItem(SERVER_KEY, clean);
  } else {
    localStorage.removeItem(SERVER_KEY);
  }
}

export const isNativeApp = () => Boolean(window.Capacitor?.isNativePlatform?.());

export function getToken() {
  return localStorage.getItem(TOKEN_KEY);
}

export function setToken(token) {
  localStorage.setItem(TOKEN_KEY, token);
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY);
}

export class ApiError extends Error {
  constructor(status, message) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

/**
 * Cliente HTTP minimal: JSON por padrao, FormData quando `form` e passado,
 * token JWT sempre que existir.
 */
export async function api(path, {
  method = 'GET', body, form, signal, timeoutMs = DEFAULT_TIMEOUT_MS, authenticated = true,
} = {}) {
  const headers = {};
  const token = authenticated ? getToken() : null;
  if (token) headers.Authorization = `Bearer ${token}`;

  let requestBody;
  if (form) {
    requestBody = form;
  } else if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    requestBody = JSON.stringify(body);
  }

  const controller = new AbortController();
  const abortFromCaller = () => controller.abort(signal.reason);
  if (signal?.aborted) abortFromCaller();
  else signal?.addEventListener('abort', abortFromCaller, { once: true });
  let timedOut = false;
  const timer = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, timeoutMs);

  try {
    const response = await fetch(getServerUrl() + path, {
      method, headers, body: requestBody, signal: controller.signal,
    });
    if (response.status === 401 && token && token === getToken()) {
      clearToken();
      window.dispatchEvent(new Event(AUTH_EXPIRED_EVENT));
    }

    let data = null;
    const text = await response.text();
    if (text) {
      try {
        data = JSON.parse(text);
      } catch {
        if (response.ok) {
          throw new ApiError(502, 'O servidor retornou uma resposta invalida. Confira o endereco configurado.');
        }
      }
    }
    if (!response.ok) {
      throw new ApiError(response.status, data?.error || `Erro ${response.status}`);
    }
    if (data == null && response.status !== 204) {
      throw new ApiError(502, 'O servidor retornou uma resposta vazia. Tente novamente.');
    }
    return data;
  } catch (error) {
    if (signal?.aborted) throw signal.reason || error;
    if (timedOut) throw new ApiError(0, 'O servidor demorou demais para responder. Tente novamente.');
    if (error instanceof ApiError) throw error;
    throw new ApiError(
      0,
      isNativeApp()
        ? 'Nao foi possivel conectar ao servidor. Confira o endereco configurado e se o celular esta na mesma rede Wi-Fi.'
        : 'Nao foi possivel conectar ao servidor. Verifique se o backend esta rodando.',
    );
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', abortFromCaller);
  }
}
