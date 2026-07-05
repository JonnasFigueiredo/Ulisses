const TOKEN_KEY = 'ulisses_token';
const SERVER_KEY = 'ulisses_server';

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
    this.status = status;
  }
}

/**
 * Cliente HTTP minimal: JSON por padrao, FormData quando `form` e passado,
 * token JWT sempre que existir.
 */
export async function api(path, { method = 'GET', body, form } = {}) {
  const headers = {};
  const token = getToken();
  if (token) headers.Authorization = `Bearer ${token}`;

  let requestBody;
  if (form) {
    requestBody = form;
  } else if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    requestBody = JSON.stringify(body);
  }

  let response;
  try {
    response = await fetch(getServerUrl() + path, { method, headers, body: requestBody });
  } catch {
    throw new ApiError(
      0,
      isNativeApp()
        ? 'Nao foi possivel conectar ao servidor. Confira o endereco configurado e se o celular esta na mesma rede Wi-Fi.'
        : 'Nao foi possivel conectar ao servidor. Verifique se o backend esta rodando.',
    );
  }

  let data = null;
  const text = await response.text();
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = null;
    }
  }

  if (!response.ok) {
    throw new ApiError(response.status, data?.error || `Erro ${response.status}`);
  }
  return data;
}
