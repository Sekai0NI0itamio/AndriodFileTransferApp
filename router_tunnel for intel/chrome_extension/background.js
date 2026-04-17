const DEFAULT_PORT = 25561;

function storageGet(keys) {
  return new Promise((resolve) => chrome.storage.local.get(keys, resolve));
}

function storageSet(obj) {
  return new Promise((resolve) => chrome.storage.local.set(obj, resolve));
}

function proxySet(value) {
  return new Promise((resolve) => chrome.proxy.settings.set({ value, scope: "regular" }, resolve));
}

function normalizeHost(host) {
  return (host || "").trim().toLowerCase().replace(/\.+$/, "");
}

function isIp(host) {
  if (!host) return false;
  if (host.includes(":")) return true; // IPv6
  return /^\d{1,3}(\.\d{1,3}){3}$/.test(host);
}

function baseDomain(host) {
  if (!host) return "";
  if (host === "localhost") return host;
  if (isIp(host)) return host;

  const parts = host.split(".").filter(Boolean);
  if (parts.length <= 2) return host;
  return parts.slice(-2).join(".");
}

function parseInputToDomain(input) {
  let raw = (input || "").trim();
  if (!raw) return { error: "Empty" };

  let host = "";
  try {
    if (raw.includes("://")) {
      host = new URL(raw).hostname;
    } else if (raw.includes("/") || raw.includes("?")) {
      host = new URL("http://" + raw).hostname;
    } else {
      host = raw;
    }
  } catch {
    return { error: "Invalid URL" };
  }

  host = normalizeHost(host);
  if (!host) return { error: "Invalid host" };
  if (host.startsWith("[")) host = host.replace(/^\[|\]$/g, "");

  const domain = baseDomain(host);
  return { domain };
}

function buildPac(domains, port) {
  const list = JSON.stringify(domains);
  return `var ROUTER_DOMAINS = ${list};\n` +
    `function FindProxyForURL(url, host) {\n` +
    `  for (var i = 0; i < ROUTER_DOMAINS.length; i++) {\n` +
    `    var d = ROUTER_DOMAINS[i];\n` +
    `    if (host === d || dnsDomainIs(host, "." + d)) {\n` +
    `      return \"PROXY 127.0.0.1:${port}\";\n` +
    `    }\n` +
    `  }\n` +
    `  return \"DIRECT\";\n` +
    `}\n`;
}

async function loadState() {
  const res = await storageGet(["domains", "enabled", "proxyPort", "rules"]);
  let domains = Array.isArray(res.domains) ? res.domains : null;
  let enabled = typeof res.enabled === "boolean" ? res.enabled : false;

  if (!domains) {
    if (res.rules && typeof res.rules === "object") {
      domains = Object.keys(res.rules || {});
      enabled = domains.length > 0;
    } else {
      domains = [];
    }
  }

  domains = domains
    .map((d) => normalizeHost(d))
    .filter(Boolean);

  const proxyPort = res.proxyPort || DEFAULT_PORT;
  return { domains, enabled, proxyPort };
}

async function saveState(state) {
  await storageSet({ domains: state.domains, enabled: state.enabled, proxyPort: state.proxyPort });
}

async function applyProxy(state) {
  const domains = state.domains || [];
  if (!state.enabled || domains.length === 0) {
    await proxySet({ mode: "direct" });
    return;
  }
  const pac = buildPac(domains, state.proxyPort);
  await proxySet({ mode: "pac_script", pacScript: { data: pac } });
}

async function refreshState() {
  const state = await loadState();
  await applyProxy(state);
  return state;
}

chrome.runtime.onInstalled.addListener(() => {
  refreshState();
});

chrome.runtime.onStartup.addListener(() => {
  refreshState();
});

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  (async () => {
    if (msg.type === "getState") {
      const state = await refreshState();
      sendResponse({ ok: true, state });
      return;
    }

    if (msg.type === "setEnabled") {
      const state = await loadState();
      state.enabled = !!msg.enabled;
      await saveState(state);
      await applyProxy(state);
      sendResponse({ ok: true });
      return;
    }

    if (msg.type === "addDomain") {
      const state = await loadState();
      const parsed = parseInputToDomain(msg.input);
      if (parsed.error) {
        sendResponse({ ok: false, error: parsed.error });
        return;
      }
      const domain = parsed.domain;
      if (!state.domains.includes(domain)) state.domains.push(domain);
      state.domains.sort();
      await saveState(state);
      await applyProxy(state);
      sendResponse({ ok: true, domain });
      return;
    }

    if (msg.type === "removeDomain") {
      const state = await loadState();
      const domain = normalizeHost(msg.domain);
      state.domains = state.domains.filter((d) => d !== domain);
      await saveState(state);
      await applyProxy(state);
      sendResponse({ ok: true });
      return;
    }

    if (msg.type === "setPort") {
      const state = await loadState();
      const port = Number(msg.proxyPort || 0);
      if (port > 0 && port <= 65535) {
        state.proxyPort = port;
        await saveState(state);
        await applyProxy(state);
      }
      sendResponse({ ok: true });
      return;
    }

    sendResponse({ ok: false, error: "Unknown message" });
  })();
  return true;
});
