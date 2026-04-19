const API_BASE = localStorage.getItem("crawlerApiBase") || "http://localhost:3600";
const ACTIVE_CRAWLER_FLAG_KEY = "crawlerHasActiveRunner";

function setText(id, value) {
  const el = document.getElementById(id);
  if (el) el.textContent = String(value ?? "-");
}

function setBadge(id, rawStatus) {
  const el = document.getElementById(id);
  if (!el) return;
  const status = String(rawStatus || "UNKNOWN").toUpperCase();
  el.textContent = status;
  el.className = "badge " + status.toLowerCase();
}

async function callApi(path, options = {}) {
  const res = await fetch(API_BASE + path, {
    headers: { "Content-Type": "application/json" },
    ...options
  });
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
  return res.json();
}

function setActiveCrawlerFlag(isActive) {
  localStorage.setItem(ACTIVE_CRAWLER_FLAG_KEY, isActive ? "1" : "0");
}

function hasActiveCrawlerFlag() {
  return localStorage.getItem(ACTIVE_CRAWLER_FLAG_KEY) === "1";
}

function openResultDetails(button) {
  const row = button.closest(".result");
  if (!row) return;
  const target = row.querySelector(".result-details");
  if (!target) return;
  const nextState = target.hasAttribute("hidden");
  if (nextState) target.removeAttribute("hidden");
  else target.setAttribute("hidden", "");
  button.textContent = nextState ? "×" : "i";
  button.setAttribute("aria-label", nextState ? "Hide details" : "Show details");
  button.setAttribute("title", nextState ? "Hide details" : "Show details");
}
