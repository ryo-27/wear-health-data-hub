import crypto from "node:crypto";
import http from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";
import express from "express";
import { WebSocket, WebSocketServer } from "ws";

const publicDirectory = path.join(path.dirname(fileURLToPath(import.meta.url)), "public");
const MAX_DEVICES = 100;
const MAX_METRICS_PER_DEVICE = 200;
const MAX_RECORDS_PER_MESSAGE = 500;
const MAX_PAIR_ATTEMPTS = 10;
const PAIR_ATTEMPT_WINDOW_MS = 10 * 60 * 1000;
const AUTH_TIMEOUT_MS = 10_000;

export function createDashboardServer({
  historyLimit = 300,
  pairingTtlMs = 10 * 60 * 1000,
  now = () => Date.now(),
} = {}) {
  if (!Number.isInteger(historyLimit) || historyLimit < 1 || historyLimit > 1000) {
    throw new Error("historyLimit must be an integer between 1 and 1000");
  }
  const devices = new Map();
  const pairingCodes = new Map();
  const viewerTokens = new Map();
  const pairAttempts = new Map();
  const app = express();
  const server = http.createServer(app);
  const ingestServer = new WebSocketServer({ noServer: true, maxPayload: 1024 * 1024 });
  const streamServer = new WebSocketServer({ noServer: true, maxPayload: 64 * 1024 });

  app.disable("x-powered-by");
  app.use(express.static(publicDirectory));
  app.get("/vendor/chart.umd.js", (_request, response) => {
    response.sendFile(path.join(path.dirname(fileURLToPath(import.meta.url)), "node_modules/chart.js/dist/chart.umd.js"));
  });
  app.get("/health", (_request, response) => response.json({ status: "ok" }));
  app.post("/api/pair", express.json({ limit: "1kb" }), (request, response) => {
    if (isRateLimited(request.ip ?? "unknown")) {
      response.status(429).json({ error: "試行回数が多すぎる。しばらく待ってから再試行すること。" });
      return;
    }
    const code = request.body?.code;
    if (typeof code !== "string" || !/^\d{8}$/.test(code)) {
      response.status(400).json({ error: "時計に表示された8桁のコードを入力すること。" });
      return;
    }
    const deviceId = pairingCodes.get(code);
    const device = devices.get(deviceId);
    if (!device || device.pairingCode !== code || device.pairingExpiresAt <= now()) {
      response.status(404).json({ error: "コードが無効か期限切れである。時計の新しいコードを確認すること。" });
      return;
    }
    pairingCodes.delete(code);
    device.pairingCode = null;
    clearTimeout(device.pairingTimer);
    for (const [tokenHash, candidateDeviceId] of viewerTokens) {
      if (candidateDeviceId === deviceId) viewerTokens.delete(tokenHash);
    }
    for (const viewer of device.viewers) viewer.close(4401, "再紐付けされた");
    const viewerToken = crypto.randomBytes(32).toString("base64url");
    viewerTokens.set(hash(viewerToken), deviceId);
    response.set("Cache-Control", "no-store");
    response.json({ viewerToken, deviceId });
    safeSend(device.ingest, { type: "paired" });
  });
  app.get("/api/session", (request, response) => {
    const device = authorizedDevice(request);
    if (!device) return response.status(401).json({ error: "紐付けが必要である。" });
    response.set("Cache-Control", "no-store");
    return response.json({ deviceId: device.id, connected: isOpen(device.ingest) });
  });
  app.post("/api/unpair", (request, response) => {
    const token = bearerToken(request);
    const device = token ? devices.get(viewerTokens.get(hash(token))) : null;
    if (!device) return response.status(401).json({ error: "紐付けが必要である。" });
    viewerTokens.delete(hash(token));
    for (const viewer of device.viewers) viewer.close(4401, "紐付け解除");
    if (isOpen(device.ingest)) issuePairingCode(device);
    response.set("Cache-Control", "no-store");
    return response.status(204).end();
  });
  app.get("/api/export", (request, response) => {
    const device = authorizedDevice(request);
    if (!device) return response.status(401).json({ error: "紐付けが必要である。" });
    const metrics = groupExportMetrics(device.history);
    response.set("Cache-Control", "no-store");
    response.set("Content-Disposition", `attachment; filename="wear-health-${device.id.slice(0, 12)}.json"`);
    response.type("json");
    return response.send(`${JSON.stringify({
      schemaVersion: 1,
      deviceId: device.id,
      exportedAt: new Date(now()).toISOString(),
      historyLimitPerMetric: historyLimit,
      metrics,
    }, null, 2)}\n`);
  });
  app.use((error, _request, response, _next) => {
    response.status(error.status ?? 400).json({ error: "リクエストを読み取れない。" });
  });

  server.on("upgrade", (request, socket, head) => {
    let pathname;
    try {
      pathname = new URL(request.url, "http://localhost").pathname;
    } catch {
      socket.destroy();
      return;
    }
    const target = pathname === "/ingest" ? ingestServer : pathname === "/stream" ? streamServer : null;
    if (!target) {
      socket.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n");
      socket.destroy();
      return;
    }
    target.handleUpgrade(request, socket, head, (webSocket) => {
      target.emit("connection", webSocket, request);
    });
  });

  ingestServer.on("connection", (socket) => {
    let device = null;
    const timeout = setTimeout(() => socket.close(4401, "認証タイムアウト"), AUTH_TIMEOUT_MS);
    socket.on("message", (raw) => {
      try {
        const message = JSON.parse(raw.toString());
        if (!device) {
          if (message?.type !== "device-hello") throw new Error("端末認証が必要である。");
          const secret = message.deviceSecret;
          const id = message.deviceId;
          if (!validDeviceIdentity(id, secret)) throw new Error("端末の認証情報が無効である。");
          device = devices.get(id);
          if (!device) {
            if (devices.size >= MAX_DEVICES) throw new Error("端末数の上限に達した。");
            device = {
              id,
              latest: new Map(),
              history: new Map(),
              recordCount: 0,
              ingest: null,
              viewers: new Set(),
              pairingCode: null,
              pairingExpiresAt: 0,
              pairingTimer: null,
            };
            devices.set(id, device);
          }
          clearTimeout(timeout);
          if (isOpen(device.ingest) && device.ingest !== socket) device.ingest.close(4400, "新しい接続に切り替え");
          device.ingest = socket;
          issuePairingCode(device);
          broadcastStatus(device);
          return;
        }
        if (message?.type !== "health-data" || message.deviceId !== device.id) {
          throw new Error("送信形式または端末IDが無効である。");
        }
        const records = validateAndNormalize(message, device.id);
        const newKeys = new Set(records.map((record) => record.key).filter((key) => !device.latest.has(key)));
        if (device.latest.size + newKeys.size > MAX_METRICS_PER_DEVICE) {
          throw new Error("指標数の上限に達した。");
        }
        for (const record of records) {
          device.latest.set(record.key, record);
          const history = device.history.get(record.key) ?? [];
          history.push(record);
          if (history.length > historyLimit) history.splice(0, history.length - historyLimit);
          device.history.set(record.key, history);
        }
        device.recordCount += records.length;
        broadcast(device.viewers, { type: "records", records });
        broadcastStatus(device);
      } catch (error) {
        safeSend(socket, { type: "error", message: error.message });
        if (!device) socket.close(4401, "端末認証失敗");
      }
    });
    socket.on("close", () => {
      clearTimeout(timeout);
      if (device?.ingest === socket) {
        device.ingest = null;
        broadcastStatus(device);
      }
    });
    socket.on("error", (error) => console.error("Wear WebSocket error:", error.message));
  });

  streamServer.on("connection", (socket) => {
    let device = null;
    const timeout = setTimeout(() => socket.close(4401, "認証タイムアウト"), AUTH_TIMEOUT_MS);
    socket.on("message", (raw) => {
      if (device) return;
      let message;
      try { message = JSON.parse(raw.toString()); } catch { socket.close(4401, "認証失敗"); return; }
      const deviceId = message?.type === "subscribe" && typeof message.viewerToken === "string"
        ? viewerTokens.get(hash(message.viewerToken))
        : null;
      device = devices.get(deviceId);
      if (!device) { socket.close(4401, "紐付けが必要である"); return; }
      clearTimeout(timeout);
      device.viewers.add(socket);
      safeSend(socket, {
        type: "snapshot",
        deviceId: device.id,
        latest: [...device.latest.values()],
        history: Object.fromEntries(device.history),
        recordCount: device.recordCount,
        connected: isOpen(device.ingest),
      });
    });
    socket.on("close", () => {
      clearTimeout(timeout);
      device?.viewers.delete(socket);
    });
    socket.on("error", (error) => console.error("Dashboard WebSocket error:", error.message));
  });

  server.on("close", () => {
    for (const device of devices.values()) clearTimeout(device.pairingTimer);
    ingestServer.close();
    streamServer.close();
  });
  return { app, server };

  function authorizedDevice(request) {
    const token = bearerToken(request);
    return token ? devices.get(viewerTokens.get(hash(token))) : null;
  }

  function isRateLimited(ip) {
    const current = pairAttempts.get(ip);
    const state = current && current.resetAt > now() ? current : { count: 0, resetAt: now() + PAIR_ATTEMPT_WINDOW_MS };
    state.count += 1;
    pairAttempts.set(ip, state);
    return state.count > MAX_PAIR_ATTEMPTS;
  }

  function issuePairingCode(device) {
    if (device.pairingCode) pairingCodes.delete(device.pairingCode);
    clearTimeout(device.pairingTimer);
    let code;
    do { code = crypto.randomInt(0, 100_000_000).toString().padStart(8, "0"); }
    while (pairingCodes.has(code));
    device.pairingCode = code;
    device.pairingExpiresAt = now() + pairingTtlMs;
    pairingCodes.set(code, device.id);
    safeSend(device.ingest, { type: "device-ready", deviceId: device.id, pairingCode: code, expiresAt: new Date(device.pairingExpiresAt).toISOString() });
    device.pairingTimer = setTimeout(() => {
      if (isOpen(device.ingest) && device.pairingCode === code) issuePairingCode(device);
    }, pairingTtlMs);
  }

  function broadcastStatus(device) {
    broadcast(device.viewers, {
      type: "status",
      connected: isOpen(device.ingest),
      recordCount: device.recordCount,
    });
  }
}

function validDeviceIdentity(deviceId, secret) {
  if (typeof deviceId !== "string" || !/^[a-f0-9]{64}$/.test(deviceId)) return false;
  if (typeof secret !== "string" || !/^[A-Za-z0-9_-]{43}$/.test(secret)) return false;
  const bytes = Buffer.from(secret, "base64url");
  if (bytes.length !== 32) return false;
  const expected = crypto.createHash("sha256").update(bytes).digest("hex");
  return crypto.timingSafeEqual(Buffer.from(expected), Buffer.from(deviceId));
}

function validateAndNormalize(payload, deviceId) {
  if (!Array.isArray(payload.records) || payload.records.length === 0 || payload.records.length > MAX_RECORDS_PER_MESSAGE) {
    throw new Error("レコード数が無効である。");
  }
  const serverReceivedAt = new Date().toISOString();
  return payload.records.map((record) => {
    for (const field of ["source", "dataType", "pointType", "value", "endTime", "receivedAt"]) {
      if (typeof record?.[field] !== "string" || record[field].length === 0) {
        throw new Error(`record.${field} is required`);
      }
    }
    const normalized = {
      deviceId,
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
    normalized.key = `${deviceId}:${normalized.source}:${normalized.dataType}`;
    return normalized;
  });
}

function optionalString(value, maxLength) {
  return typeof value === "string" && value.length > 0 ? value.slice(0, maxLength) : null;
}

function groupExportMetrics(historyByKey) {
  const sources = new Map();
  for (const records of historyByKey.values()) {
    if (records.length === 0) continue;
    const { source, dataType } = records[0];
    const dataTypes = sources.get(source) ?? new Map();
    const units = new Set(records.map((record) => record.unit));
    dataTypes.set(dataType, {
      unit: units.size === 1 ? records[0].unit : null,
      sampleCount: records.length,
      samples: records.map((record) => ({
        pointType: record.pointType,
        value: structuredValue(record),
        rawValue: record.value,
        unit: record.unit,
        startTime: record.startTime,
        endTime: record.endTime,
        receivedAt: record.receivedAt,
        serverReceivedAt: record.serverReceivedAt,
        accuracy: record.accuracy,
      })),
    });
    sources.set(source, dataTypes);
  }
  return Object.fromEntries([...sources].sort(([left], [right]) => left.localeCompare(right)).map(
    ([source, dataTypes]) => [source, Object.fromEntries([...dataTypes].sort(([left], [right]) => left.localeCompare(right)))],
  ));
}

function structuredValue(record) {
  if (record.pointType === "statistical") {
    try {
      const value = JSON.parse(record.value);
      if (["min", "max", "average"].every((key) => typeof value?.[key] === "number" && Number.isFinite(value[key]))) {
        return { min: value.min, max: value.max, average: value.average };
      }
    } catch { /* 元の文字列をそのまま残す。 */ }
  }
  if (/^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$/.test(record.value)) {
    const numeric = Number(record.value);
    if (Number.isFinite(numeric) && (!Number.isInteger(numeric) || Number.isSafeInteger(numeric))) return numeric;
  }
  return record.value;
}

function hash(value) {
  return crypto.createHash("sha256").update(value).digest("hex");
}

function bearerToken(request) {
  return /^Bearer ([A-Za-z0-9_-]{43})$/.exec(request.get("Authorization") ?? "")?.[1] ?? null;
}

function isOpen(socket) {
  return socket?.readyState === WebSocket.OPEN;
}

function safeSend(socket, payload) {
  if (isOpen(socket)) socket.send(JSON.stringify(payload));
}

function broadcast(sockets, payload) {
  for (const socket of sockets) safeSend(socket, payload);
}
