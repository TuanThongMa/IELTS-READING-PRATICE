export async function api(path: string, method = "GET", body?: object) {
  const headers: Record<string, string> = {};
  if (method !== "GET") {
    const tokenResponse = await fetch("/auth/csrf", { credentials: "same-origin" });
    if (!tokenResponse.ok) throw new Error("CSRF unavailable");
    const token = await tokenResponse.json() as { token: string; headerName: string };
    headers[token.headerName] = token.token;
  }
  if (body) headers["Content-Type"] = "application/json";
  return fetch(path, { method, headers, credentials: "same-origin", body: body ? JSON.stringify(body) : undefined });
}
