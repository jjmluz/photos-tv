const form = document.getElementById("form");
const address = document.getElementById("address");
const code = document.getElementById("code");
const status = document.getElementById("status");

function show(result) {
  if (!result) return;
  const when = new Date(result.at).toLocaleTimeString("pt-PT", { hour: "2-digit", minute: "2-digit" });
  status.textContent = `${result.message} (${when})`;
  status.className = result.ok ? "ok" : "err";
}

const saved = await chrome.storage.local.get(["address", "code", "lastSync"]);
address.value = saved.address ?? "";
code.value = saved.code ?? "";
show(saved.lastSync);

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  let host = address.value.trim().replace(/^https?:\/\//, "").replace(/\/.*$/, "");
  if (!host.includes(":")) host += ":8765";
  // Only this TV's address, asked for when it's set rather than any http site up front.
  const granted = await chrome.permissions.request({ origins: [`http://${host.split(":")[0]}/*`] });
  if (!granted) {
    show({ ok: false, message: "Sem permissão para contactar a TV.", at: Date.now() });
    return;
  }
  await chrome.storage.local.set({ address: host, code: code.value.trim() });
  address.value = host;
  status.textContent = "A sincronizar…";
  status.className = "";
  show(await chrome.runtime.sendMessage({ type: "syncNow" }));
});
