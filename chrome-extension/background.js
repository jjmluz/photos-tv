import { pushCookies } from "./sync.js";

// Push every minute, so the TV picks up the session soon after the app opens, and soon after
// Google rotates a session cookie in this browser.

const SESSION_COOKIE = /(^|_)(SID|PSID|PSIDTS|PSIDCC)$/;
let pending;

function schedule() {
  chrome.alarms.create("sync", { periodInMinutes: 1 });
}

chrome.runtime.onInstalled.addListener(schedule);
chrome.runtime.onStartup.addListener(schedule);
chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === "sync") pushCookies();
});

chrome.cookies.onChanged.addListener(({ cookie, removed }) => {
  if (removed || !cookie.domain.endsWith("google.com") || !SESSION_COOKIE.test(cookie.name)) return;
  // Rotations change several cookies at once; send them together.
  clearTimeout(pending);
  pending = setTimeout(pushCookies, 3000);
});

chrome.runtime.onMessage.addListener((message, _sender, reply) => {
  if (message?.type !== "syncNow") return;
  pushCookies().then(reply);
  return true;
});
