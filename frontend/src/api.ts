async function csrfHeaders() {
  const headers: Record<string, string> = {};
  const tokenResponse = await fetch("/auth/csrf", { credentials: "same-origin" });
  if (!tokenResponse.ok) throw new Error("CSRF unavailable");
  const token = await tokenResponse.json() as { token: string; headerName: string };
  headers[token.headerName] = token.token;
  return headers;
}

export async function api(path: string, method = "GET", body?: object) {
  const headers = method === "GET" ? {} : await csrfHeaders();
  if (body) headers["Content-Type"] = "application/json";
  return fetch(path, { method, headers, credentials: "same-origin", body: body ? JSON.stringify(body) : undefined });
}

export async function apiMultipart(path: string, body: FormData) {
  return fetch(path, { method: "POST", headers: await csrfHeaders(), credentials: "same-origin", body });
}
