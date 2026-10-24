import { deriveKey, seal, toNetscape } from "./crypto.js";

const TIMEOUT_MS = 5000;

/** Sends this browser's Google cookies to the TV; records and returns the outcome. */
export async function pushCookies() {
  const { address, code } = await chrome.storage.local.get(["address", "code"]);
  if (!address || !code) return record(false, "Configure o endereço e o código da TV.");

  const cookies = await chrome.cookies.getAll({ domain: "google.com" });
  if (!cookies.some((c) => c.name === "SID" || c.name.endsWith("PSID"))) {
    return record(false, "Sem sessão Google neste navegador. Abra photos.google.com e inicie sessão.");
  }
  const body = await seal(await deriveKey(code), { sentAt: Date.now(), cookies: toNetscape(cookies) });

  let response;
  try {
    response = await fetch(`http://${address}/cookies`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body,
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
  } catch {
    return record(false, "A TV não respondeu. A app Fotos está aberta e na mesma rede?");
  }
  if (response.status === 401) return record(false, "O código não corresponde ao que a TV mostra.");
  if (!response.ok) return record(false, `A TV recusou o envio (HTTP ${response.status}).`);
  return record(true, "Sessão sincronizada com a TV.");
}

async function record(ok, message) {
  const lastSync = { ok, message, at: Date.now() };
  await chrome.storage.local.set({ lastSync });
  return lastSync;
}
