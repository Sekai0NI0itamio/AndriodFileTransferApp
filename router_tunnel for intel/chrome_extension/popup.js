const enabledEl = document.getElementById("enabled");
const statusEl = document.getElementById("status");
const listEl = document.getElementById("list");
const domainInputEl = document.getElementById("domainInput");
const addBtn = document.getElementById("addBtn");
const proxyPortEl = document.getElementById("proxyPort");
const savePortBtn = document.getElementById("savePort");

let state = { domains: [], enabled: false, proxyPort: 25561 };

function sendMessage(msg) {
  return new Promise((resolve) => chrome.runtime.sendMessage(msg, resolve));
}

function updateStatus() {
  if (state.enabled) {
    statusEl.textContent = `Routing ON for ${state.domains.length} site(s)`;
    statusEl.className = "status on";
  } else {
    statusEl.textContent = "Routing OFF (all traffic uses VPN)";
    statusEl.className = "status off";
  }
}

function renderList() {
  listEl.innerHTML = "";
  if (!state.domains.length) {
    const empty = document.createElement("div");
    empty.className = "empty";
    empty.textContent = "No sites added";
    listEl.appendChild(empty);
    return;
  }

  const domains = [...state.domains].sort();
  for (const domain of domains) {
    const row = document.createElement("div");
    row.className = "list-row";

    const name = document.createElement("div");
    name.className = "list-host";
    name.textContent = domain;

    const btn = document.createElement("button");
    btn.className = "link-btn";
    btn.textContent = "Remove";
    btn.addEventListener("click", async () => {
      await sendMessage({ type: "removeDomain", domain });
      await loadState();
      updateUI();
    });

    row.appendChild(name);
    row.appendChild(btn);
    listEl.appendChild(row);
  }
}

function updateUI() {
  enabledEl.checked = !!state.enabled;
  proxyPortEl.value = state.proxyPort || 25561;
  updateStatus();
  renderList();
}

async function loadState() {
  const resp = await sendMessage({ type: "getState" });
  if (resp && resp.ok && resp.state) state = resp.state;
}

addBtn.addEventListener("click", async () => {
  const input = domainInputEl.value.trim();
  if (!input) return;
  const resp = await sendMessage({ type: "addDomain", input });
  if (resp && resp.ok) {
    domainInputEl.value = "";
    await loadState();
    updateUI();
  } else if (resp && resp.error) {
    statusEl.textContent = `Add failed: ${resp.error}`;
    statusEl.className = "status muted";
  }
});

domainInputEl.addEventListener("keydown", (e) => {
  if (e.key === "Enter") addBtn.click();
});

enabledEl.addEventListener("change", async () => {
  await sendMessage({ type: "setEnabled", enabled: enabledEl.checked });
  await loadState();
  updateUI();
});

savePortBtn.addEventListener("click", async () => {
  const port = Number(proxyPortEl.value || 0);
  if (!port || port < 1 || port > 65535) return;
  await sendMessage({ type: "setPort", proxyPort: port });
  await loadState();
  updateUI();
});

(async () => {
  await loadState();
  updateUI();
})();
