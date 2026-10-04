const latest = new Map();
const history = new Map();
const pairPanel = document.querySelector("#pair-panel");
const pairForm = document.querySelector("#pair-form");
const pairCode = document.querySelector("#pair-code");
const pairError = document.querySelector("#pair-error");
const dashboard = document.querySelector("#dashboard");
const metricSelect = document.querySelector("#metric-select");
const metricCards = document.querySelector("#metric-cards");
const connectionDot = document.querySelector("#connection-dot");
const connectionLabel = document.querySelector("#connection-label");
const lastReceived = document.querySelector("#last-received");
const wearCount = document.querySelector("#wear-count");
const metricCount = document.querySelector("#metric-count");
const deviceLabel = document.querySelector("#device-label");
const recordCount = document.querySelector("#record-count");
const switchDevice = document.querySelector("#switch-device");
const exportButton = document.querySelector("#export-json");

let socket;
let reconnectTimer;
let viewerToken = localStorage.getItem("wear-health-viewer-token");
let activeSource = "ALL";
let totalRecords = 0;
let watchConnected = false;
let newestReceivedAt = null;

const chart = new Chart(document.querySelector("#metric-chart"), {
  type: "line",
  data: {
    labels: [],
    datasets: [{
      label: "データ待機中",
      data: [],
      borderColor: "#46e6a7",
      backgroundColor: "rgba(70, 230, 167, 0.12)",
      borderWidth: 2,
      pointRadius: 2,
      pointHoverRadius: 5,
      tension: 0.28,
      fill: true,
    }],
  },
  options: {
    responsive: true,
    maintainAspectRatio: false,
    animation: { duration: 180 },
    interaction: { intersect: false, mode: "index" },
    plugins: { legend: { labels: { color: "#b8c2cf" } } },
    scales: {
      x: { ticks: { color: "#718096", maxTicksLimit: 8 }, grid: { color: "rgba(255,255,255,0.04)" } },
      y: { ticks: { color: "#718096" }, grid: { color: "rgba(255,255,255,0.06)" } },
    },
  },
});

pairForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  pairError.hidden = true;
  const button = pairForm.querySelector("button");
  button.disabled = true;
  try {
    const response = await fetch("/api/pair", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code: pairCode.value.trim() }),
    });
    const result = await response.json();
    if (!response.ok) throw new Error(result.error ?? "紐付けに失敗した。");
    viewerToken = result.viewerToken;
    localStorage.setItem("wear-health-viewer-token", viewerToken);
    pairCode.value = "";
    showDashboard(result.deviceId);
    connect(viewerToken);
  } catch (error) {
    pairError.textContent = error.message;
    pairError.hidden = false;
  } finally {
    button.disabled = false;
  }
});

switchDevice.addEventListener("click", () => {
  clearTimeout(reconnectTimer);
  if (viewerToken) {
    fetch("/api/unpair", {
      method: "POST",
      headers: { Authorization: `Bearer ${viewerToken}` },
    }).catch(() => {});
  }
  viewerToken = null;
  localStorage.removeItem("wear-health-viewer-token");
  socket?.close();
  latest.clear();
  history.clear();
  showPairing();
});

exportButton.addEventListener("click", async () => {
  exportButton.disabled = true;
  try {
    const response = await fetch("/api/export", {
      headers: { Authorization: `Bearer ${viewerToken}` },
    });
    if (!response.ok) throw new Error("JSON を取得できない。時計を再度紐付けること。");
    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `wear-health-${deviceLabel.textContent}-${new Date().toISOString().slice(0, 10)}.json`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 60_000);
  } catch (error) {
    alert(error.message);
  } finally {
    exportButton.disabled = false;
  }
});

metricSelect.addEventListener("change", renderChart);
document.querySelector("#source-filters").addEventListener("click", (event) => {
  const button = event.target.closest("[data-source]");
  if (!button) return;
  activeSource = button.dataset.source;
  document.querySelectorAll(".filter").forEach((item) => item.classList.toggle("active", item === button));
  renderCards();
});

setInterval(renderFreshness, 30_000);
if (viewerToken) {
  restoreSession(viewerToken);
} else {
  showPairing();
}

async function restoreSession(token) {
  try {
    const response = await fetch("/api/session", { headers: { Authorization: `Bearer ${token}` } });
    if (!response.ok) throw new Error("紐付けが切れた。");
    const result = await response.json();
    if (token !== viewerToken) return;
    showDashboard(result.deviceId);
    connect(token);
  } catch {
    if (token !== viewerToken) return;
    viewerToken = null;
    localStorage.removeItem("wear-health-viewer-token");
    showPairing();
  }
}

function showPairing() {
  pairPanel.hidden = false;
  dashboard.hidden = true;
  switchDevice.hidden = true;
  setConnection(false, "未紐付け");
}

function showDashboard(deviceId) {
  pairPanel.hidden = true;
  dashboard.hidden = false;
  switchDevice.hidden = false;
  deviceLabel.textContent = deviceId.slice(0, 12);
  setConnection(false, "時計を確認中…");
}

function connect(token) {
  clearTimeout(reconnectTimer);
  socket?.close();
  const protocol = location.protocol === "https:" ? "wss:" : "ws:";
  const currentSocket = new WebSocket(`${protocol}//${location.host}/stream`);
  socket = currentSocket;

  currentSocket.addEventListener("open", () => {
    currentSocket.send(JSON.stringify({ type: "subscribe", viewerToken: token }));
  });
  currentSocket.addEventListener("message", (event) => {
    if (socket !== currentSocket) return;
    let message;
    try { message = JSON.parse(event.data); } catch { return; }
    if (message.type === "snapshot") {
      latest.clear();
      history.clear();
      for (const record of message.latest ?? []) latest.set(record.key, record);
      for (const [key, records] of Object.entries(message.history ?? {})) history.set(key, records);
      totalRecords = message.recordCount ?? 0;
      watchConnected = Boolean(message.connected);
      render();
      renderConnection();
    } else if (message.type === "records") {
      applyRecords(message.records ?? []);
    } else if (message.type === "status") {
      watchConnected = Boolean(message.connected);
      totalRecords = message.recordCount ?? totalRecords;
      recordCount.textContent = totalRecords.toLocaleString("ja-JP");
      renderConnection();
    }
  });
  currentSocket.addEventListener("close", (event) => {
    if (socket !== currentSocket || token !== viewerToken) return;
    if (event.code === 4401) {
      viewerToken = null;
      localStorage.removeItem("wear-health-viewer-token");
      showPairing();
      return;
    }
    setConnection(false, "ダッシュボード切断・再接続中");
    reconnectTimer = setTimeout(() => connect(token), 3000);
  });
}

function applyRecords(records) {
  for (const record of records) {
    latest.set(record.key, record);
    const metricHistory = history.get(record.key) ?? [];
    metricHistory.push(record);
    if (metricHistory.length > 300) metricHistory.splice(0, metricHistory.length - 300);
    history.set(record.key, metricHistory);
  }
  totalRecords += records.length;
  render();
}

function render() {
  const records = [...latest.values()];
  metricCount.textContent = String(records.length);
  wearCount.textContent = watchConnected ? "接続中" : "未接続";
  recordCount.textContent = totalRecords.toLocaleString("ja-JP");
  newestReceivedAt = records.toSorted(
    (left, right) => Date.parse(right.serverReceivedAt) - Date.parse(left.serverReceivedAt),
  )[0]?.serverReceivedAt ?? null;
  renderFreshness();
  renderMetricOptions();
  renderCards();
  renderChart();
}

function renderFreshness() {
  if (!newestReceivedAt) {
    lastReceived.textContent = "受信データなし";
    return;
  }
  const ageMinutes = Math.floor((Date.now() - Date.parse(newestReceivedAt)) / 60_000);
  lastReceived.textContent = `最終受信 ${formatTime(newestReceivedAt)}${ageMinutes >= 2 ? `・${ageMinutes}分前` : ""}`;
}

function renderConnection() {
  wearCount.textContent = watchConnected ? "接続中" : "未接続";
  setConnection(watchConnected, watchConnected ? "時計接続中" : "時計未接続");
}

function renderMetricOptions() {
  const previous = metricSelect.value;
  const numericRecords = [...latest.values()]
    .filter((record) => numericValue(record) !== null)
    .toSorted((left, right) => recordLabel(left).localeCompare(recordLabel(right), "ja"));
  metricSelect.replaceChildren();
  if (numericRecords.length === 0) {
    metricSelect.add(new Option("数値データ待機中", ""));
    return;
  }
  for (const record of numericRecords) metricSelect.add(new Option(recordLabel(record), record.key));
  metricSelect.value = numericRecords.some((record) => record.key === previous)
    ? previous : numericRecords[0].key;
}

function renderCards() {
  const records = [...latest.values()]
    .filter((record) => activeSource === "ALL" || record.source === activeSource)
    .toSorted((left, right) => {
      const sourceOrder = left.source.localeCompare(right.source);
      return sourceOrder || left.dataType.localeCompare(right.dataType, "ja");
    });
  metricCards.replaceChildren();
  if (records.length === 0) {
    const empty = document.createElement("p");
    empty.className = "empty-state";
    empty.textContent = "該当する最新データはまだない。";
    metricCards.append(empty);
    return;
  }
  for (const record of records) {
    const card = document.createElement("article");
    card.className = `metric-card ${record.source.toLowerCase()}`;
    const title = document.createElement("div");
    title.className = "metric-title";
    const name = document.createElement("span");
    name.textContent = record.dataType;
    const badge = document.createElement("span");
    badge.className = "badge";
    badge.textContent = record.source;
    title.append(name, badge);
    const value = document.createElement("div");
    value.className = "metric-value";
    value.textContent = record.value;
    if (record.unit) {
      const unit = document.createElement("span");
      unit.className = "metric-unit";
      unit.textContent = ` ${record.unit}`;
      value.append(unit);
    }
    const meta = document.createElement("div");
    meta.className = "metric-meta";
    const pointType = document.createElement("span");
    pointType.textContent = record.pointType;
    const time = document.createElement("time");
    time.dateTime = record.endTime;
    time.textContent = formatTime(record.endTime);
    meta.append(pointType, time);
    card.title = record.accuracy ?? "";
    card.append(title, value, meta);
    metricCards.append(card);
  }
}

function renderChart() {
  const key = metricSelect.value;
  const records = (history.get(key) ?? [])
    .filter((record) => numericValue(record) !== null)
    .slice(-120);
  const current = latest.get(key);
  chart.data.labels = records.map((record) => formatTime(record.endTime));
  chart.data.datasets[0].data = records.map(numericValue);
  chart.data.datasets[0].label = current
    ? `${current.dataType}${current.unit ? ` (${current.unit})` : ""}` : "データ待機中";
  chart.update();
}

function numericValue(record) {
  const value = Number(record.value);
  return Number.isFinite(value) ? value : null;
}

function recordLabel(record) {
  return `${record.dataType} / ${record.source}`;
}

function formatTime(value) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "時刻不明" : new Intl.DateTimeFormat("ja-JP", {
    hour: "2-digit", minute: "2-digit", second: "2-digit",
  }).format(date);
}

function setConnection(online, label) {
  connectionDot.classList.toggle("online", online);
  connectionDot.classList.toggle("offline", !online);
  connectionLabel.textContent = label;
}
