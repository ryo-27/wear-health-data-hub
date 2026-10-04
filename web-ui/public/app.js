const latest = new Map();
const history = new Map();
const tokenDialog = document.querySelector("#token-dialog");
const tokenForm = document.querySelector("#token-form");
const tokenInput = document.querySelector("#token-input");
const tokenError = document.querySelector("#token-error");
const metricSelect = document.querySelector("#metric-select");
const metricCards = document.querySelector("#metric-cards");
const connectionDot = document.querySelector("#connection-dot");
const connectionLabel = document.querySelector("#connection-label");
const lastReceived = document.querySelector("#last-received");
const wearCount = document.querySelector("#wear-count");
const metricCount = document.querySelector("#metric-count");
const deviceCount = document.querySelector("#device-count");
const recordCount = document.querySelector("#record-count");

let socket;
let reconnectTimer;
let activeSource = "ALL";
let totalRecords = 0;
let connectedOnce = false;

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
    plugins: {
      legend: { labels: { color: "#b8c2cf" } },
    },
    scales: {
      x: {
        ticks: { color: "#718096", maxTicksLimit: 8 },
        grid: { color: "rgba(255,255,255,0.04)" },
      },
      y: {
        ticks: { color: "#718096" },
        grid: { color: "rgba(255,255,255,0.06)" },
      },
    },
  },
});

tokenForm.addEventListener("submit", (event) => {
  event.preventDefault();
  tokenError.hidden = true;
  localStorage.setItem("health-dashboard-token", tokenInput.value);
  connect(tokenInput.value);
});

metricSelect.addEventListener("change", renderChart);

document.querySelector("#source-filters").addEventListener("click", (event) => {
  const button = event.target.closest("[data-source]");
  if (!button) return;
  activeSource = button.dataset.source;
  document.querySelectorAll(".filter").forEach((item) => {
    item.classList.toggle("active", item === button);
  });
  renderCards();
});

const savedToken = localStorage.getItem("health-dashboard-token");
if (savedToken) {
  tokenInput.value = savedToken;
  connect(savedToken);
} else {
  tokenDialog.showModal();
}

function connect(token) {
  clearTimeout(reconnectTimer);
  socket?.close();
  setConnection(false, "接続中…");

  const protocol = location.protocol === "https:" ? "wss:" : "ws:";
  const url = `${protocol}//${location.host}/stream?token=${encodeURIComponent(token)}`;
  let opened = false;
  socket = new WebSocket(url);

  socket.addEventListener("open", () => {
    opened = true;
    connectedOnce = true;
    setConnection(true, "WebUI接続中");
    if (tokenDialog.open) tokenDialog.close();
  });

  socket.addEventListener("message", (event) => {
    const message = JSON.parse(event.data);
    if (message.type === "snapshot") {
      latest.clear();
      history.clear();
      for (const record of message.latest ?? []) latest.set(record.key, record);
      for (const [key, records] of Object.entries(message.history ?? {})) {
        history.set(key, records);
      }
      totalRecords = [...history.values()].reduce((sum, records) => sum + records.length, 0);
      render();
    } else if (message.type === "records") {
      applyRecords(message.records ?? []);
    } else if (message.type === "status") {
      wearCount.textContent = String(message.ingestConnections ?? 0);
    }
  });

  socket.addEventListener("close", () => {
    setConnection(false, "切断");
    if (!opened && !connectedOnce) {
      tokenError.hidden = false;
      if (!tokenDialog.open) tokenDialog.showModal();
      return;
    }
    reconnectTimer = setTimeout(() => connect(token), 2000);
  });

  socket.addEventListener("error", () => {
    if (!opened) socket.close();
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
  if (records.length > 0) {
    const newest = records.at(-1);
    lastReceived.textContent = `最終受信 ${formatTime(newest.serverReceivedAt)}`;
  }
  render();
}

function render() {
  const records = [...latest.values()];
  metricCount.textContent = String(records.length);
  deviceCount.textContent = String(new Set(records.map((record) => record.deviceId)).size);
  recordCount.textContent = totalRecords.toLocaleString("ja-JP");

  const newest = records.toSorted(
    (left, right) => Date.parse(right.serverReceivedAt) - Date.parse(left.serverReceivedAt),
  )[0];
  if (newest) lastReceived.textContent = `最終受信 ${formatTime(newest.serverReceivedAt)}`;

  renderMetricOptions();
  renderCards();
  renderChart();
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
  for (const record of numericRecords) {
    metricSelect.add(new Option(recordLabel(record), record.key));
  }
  metricSelect.value = numericRecords.some((record) => record.key === previous)
    ? previous
    : numericRecords[0].key;
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
    empty.textContent = "該当する最新データはまだありません。";
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
    ? `${current.dataType}${current.unit ? ` (${current.unit})` : ""}`
    : "データ待機中";
  chart.update();
}

function numericValue(record) {
  const value = Number(record.value);
  return Number.isFinite(value) ? value : null;
}

function recordLabel(record) {
  return `${record.dataType} / ${record.source} / ${record.deviceId.slice(0, 8)}`;
}

function formatTime(value) {
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? "時刻不明"
    : new Intl.DateTimeFormat("ja-JP", {
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit",
      }).format(date);
}

function setConnection(online, label) {
  connectionDot.classList.toggle("online", online);
  connectionDot.classList.toggle("offline", !online);
  connectionLabel.textContent = label;
}
