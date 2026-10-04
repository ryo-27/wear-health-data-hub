import { createDashboardServer } from "./server-core.mjs";

const port = Number(process.env.PORT ?? 8080);
const historyLimit = Number(process.env.MAX_HISTORY_PER_METRIC ?? 300);
const { server } = createDashboardServer({ historyLimit });

server.listen(port, "0.0.0.0", () => {
  console.log(`Wear Health Data Hub: http://localhost:${port}`);
});
