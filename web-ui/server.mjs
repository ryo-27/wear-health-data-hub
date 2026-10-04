import crypto from "node:crypto";
import http from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";
import express from "express";
import { WebSocket, WebSocketServer } from "ws";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const port = Number(process.env.PORT ?? 8080);
const token = process.env.HEALTH_DASHBOARD_TOKEN ?? "development-token";
const maxHistoryPerMetric = Number(process.env.MAX_HISTORY_PER_METRIC ?? 300);

const app = express();
app.disable("x-powered-by");
app.use(express.static(path.join(__dirname, "public")));
app.get("/vendor/chart.umd.js", (_request, response) => {
  response.sendFile(path.join(__dirname, "node_modules/chart.js/dist/chart.umd.js"));
});
app.get("/health", (_request, response) => {
  response.json({
    status: "ok",
    ingestConnections: ingestClients.size,
    dashboardConnections: streamClients.size,
    metricCount: latestByMetric.size,
  });
});

const server = http.createServer(app);
const ingestServer = new WebSocketServer({ noServer: true, maxPayload: 1024 * 1024 });
const streamServer = new WebSocketServer({ noServer: true, maxPayload: 64 * 1024 });
const ingestClients = new Set();
const streamClients = new Set();
const latestByMetric = new Map();
const historyByMetric = new Map();

server.on("upgrade", (request, socket, head) => {
  const url = new URL(request.url, `http://${request.headers.host}`);
  if (!isAuthorized(url.searchParams.get("token"))) {
    socket.write("HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n");
    socket.destroy();
    return;
  }

  const target = url.pathname === "/ingest"
    ? ingestServer
    : url.pathname === "/stream"
      ? streamServer
      : null;
  if (!target) {
    socket.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n");
    socket.destroy();
    return;
  }

  target.handleUpgrade(request, socket, head, (webSocket) => {
    target.emit("connection", webSocket, request);
  });
});

ingestServer.on("connection", (webSocket) => {
  ingestClients.add(webSocket);
  broadcastStatus();

  webSocket.on("message", (rawData) => {
    try {
      const payload = JSON.parse(rawData.toString());
      const records = validateAndNormalize(payload);
      const message = JSON.stringify({ type: "records", records });

      for (const record of records) {
        latestByMetric.set(record.key, record);
        const history = historyByMetric.get(record.key) ?? [];
        history.push(record);
        if (history.length > maxHistoryPerMetric) {
          history.splice(0, history.length - maxHistoryPerMetric);
        }
        historyByMetric.set(record.key, history);
      }
      broadcast(streamClients, message);
    } catch (error) {
      webSocket.send(JSON.stringify({
        type: "error",
        message: error instanceof Error ? error.message : "Invalid payload",
      }));
    }
  });

  webSocket.on("close", () => {
    ingestClients.delete(webSocket);
    broadcastStatus();
  });
  webSocket.on("error", (error) => {
    console.error("Wear WebSocket error:", error.message);
  });
});

streamServer.on("connection", (webSocket) => {
  streamClients.add(webSocket);
  webSocket.send(JSON.stringify({
    type: "snapshot",
    latest: [...latestByMetric.values()],
    history: Object.fromEntries(historyByMetric),
  }));
  broadcastStatus();

  webSocket.on("close", () => {
    streamClients.delete(webSocket);
    broadcastStatus();
  });
  webSocket.on("error", (error) => {
    console.error("Dashboard WebSocket error:", error.message);
  });
});

function validateAndNormalize(payload) {
  if (payload?.type !== "health-data") {
    throw new Error("type must be health-data");
  }
  if (typeof payload.deviceId !== "string" || payload.deviceId.length === 0) {
    throw new Error("deviceId is required");
  }
  if (!Array.isArray(payload.records) || payload.records.length === 0) {
    throw new Error("records must be a non-empty array");
  }
  if (payload.records.length > 500) {
    throw new Error("too many records in one message");
  }

  const serverReceivedAt = new Date().toISOString();
  return payload.records.map((record) => {
    for (const field of ["source", "dataType", "pointType", "value", "endTime", "receivedAt"]) {
      if (typeof record?.[field] !== "string" || record[field].length === 0) {
        throw new Error(`record.${field} is required`);
      }
    }

    const normalized = {
      deviceId: payload.deviceId.slice(0, 128),
      source: record.source.slice(0, 32),
      dataType: record.dataType.slice(0, 128),
      pointType: record.pointType.slice(0, 32),
      value: record.value.slice(0, 2048),
      unit: optionalString(record.unit, 64),
      startTime: optionalString(record.startTime, 64),
      endTime: record.endTime.slice(0, 64),
      receivedAt: record.receivedAt.slice(0, 64),
      accuracy: optionalString(record.accuracy, 512),
      sentAt: optionalString(payload.sentAt, 64),
      serverReceivedAt,
    };
    normalized.key = `${normalized.deviceId}:${normalized.source}:${normalized.dataType}`;
    return normalized;
  });
}

function optionalString(value, maxLength) {
  return typeof value === "string" && value.length > 0
    ? value.slice(0, maxLength)
    : null;
}

function isAuthorized(candidate) {
  if (typeof candidate !== "string") return false;
  const expected = Buffer.from(token);
  const actual = Buffer.from(candidate);
  return expected.length === actual.length && crypto.timingSafeEqual(expected, actual);
}

function broadcastStatus() {
  broadcast(streamClients, JSON.stringify({
    type: "status",
    ingestConnections: ingestClients.size,
    dashboardConnections: streamClients.size,
  }));
}

function broadcast(clients, message) {
  for (const client of clients) {
    if (client.readyState === WebSocket.OPEN) {
      client.send(message);
    }
  }
}

server.listen(port, "0.0.0.0", () => {
  console.log(`Health dashboard: http://localhost:${port}`);
  if (token === "development-token") {
    console.warn("Using the development token. Set HEALTH_DASHBOARD_TOKEN before LAN use.");
  }
});
