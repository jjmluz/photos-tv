// Encryption shared with the TV app (data/CookieSync.kt): AES-GCM with a key derived from the
// pairing code by PBKDF2-SHA256. Keep the salt and iteration count in step with the app.

const SALT = "photos-tv-cookie-sync";
const ITERATIONS = 100_000;

export function cleanCode(code) {
  return code.toUpperCase().replace(/[^A-Z0-9]/g, "");
}

export async function deriveKey(code) {
  const material = await crypto.subtle.importKey(
    "raw", new TextEncoder().encode(cleanCode(code)), "PBKDF2", false, ["deriveKey"]);
  return crypto.subtle.deriveKey(
    { name: "PBKDF2", salt: new TextEncoder().encode(SALT), iterations: ITERATIONS, hash: "SHA-256" },
    material, { name: "AES-GCM", length: 256 }, false, ["encrypt"]);
}

/** The request body for POST /cookies: {"iv", "data"} in base64. */
export async function seal(key, payload) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const plain = new TextEncoder().encode(JSON.stringify(payload));
  const data = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv }, key, plain));
  return JSON.stringify({ iv: base64(iv), data: base64(data) });
}

function base64(bytes) {
  let s = "";
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  return btoa(s);
}

/** chrome.cookies.Cookie objects as a Netscape cookies.txt, the format the app imports. */
export function toNetscape(cookies) {
  const lines = cookies.map((c) => {
    const domain = c.hostOnly ? c.domain : (c.domain.startsWith(".") ? c.domain : "." + c.domain);
    return [
      (c.httpOnly ? "#HttpOnly_" : "") + domain,
      c.hostOnly ? "FALSE" : "TRUE",
      c.path,
      c.secure ? "TRUE" : "FALSE",
      Math.floor(c.expirationDate ?? 0),
      c.name,
      c.value,
    ].join("\t");
  });
  return "# Netscape HTTP Cookie File\n" + lines.join("\n") + "\n";
}
