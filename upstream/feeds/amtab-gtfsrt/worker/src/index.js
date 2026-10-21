// Cloudflare Worker: serves AMTAB Bari real-time data as GTFS-Realtime protobuf.
//   /trip-updates.pb  /vehicle-positions.pb  /health
import { tripUpdates, vehiclePositions } from "./gtfsrt.js";

const SOURCE = "https://avl.amtab.it/WSExportGTFS_RT/api/gtfs";
const CACHE_SECONDS = 20;
/// When AMTAB is down, the last good feed is served this long (its header timestamp shows its age).
const STALE_SECONDS = 300;
const UPSTREAM_TIMEOUT_MS = 10_000;
const ROUTES = ["/trip-updates.pb", "/vehicle-positions.pb", "/health"];

async function upstream(env, path) {
  const res = await fetch(`${env.AMTAB_BASE || SOURCE}/${path}`, {
    headers: { "User-Agent": env.USER_AGENT || "Publit-AMTAB-GTFSRT/0.2 (+https://github.com/elija-7580/Publit-)", Accept: "application/json" },
    cf: { cacheTtl: CACHE_SECONDS, cacheEverything: true },
    // A hanging upstream must not hold every consumer's request open.
    signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
  });
  if (!res.ok) throw new Error(`upstream ${path}: HTTP ${res.status}`);
  return res.json();
}

function reply(body, type, status = 200, maxAge = CACHE_SECONDS) {
  return new Response(body, {
    status,
    headers: { "Content-Type": type, "Cache-Control": `public, max-age=${maxAge}`, "Access-Control-Allow-Origin": "*" },
  });
}

export function route(request) {
  return new URL(request.url).pathname.replace(/\/$/, "");
}

export async function handle(request, env = {}, load = upstream) {
  if (request.method !== "GET" && request.method !== "HEAD") return reply("method not allowed", "text/plain", 405, 0);
  const path = route(request);
  if (!ROUTES.includes(path)) return reply("not found", "text/plain", 404);
  try {
    if (path === "/vehicle-positions.pb") {
      return reply(vehiclePositions(await load(env, "VechiclePosition")).bytes, "application/x-protobuf"); // sic: AMTAB's spelling
    }
    // Both upstream calls at once: the slower one sets the response time, not their sum.
    const [vp, tu] = await Promise.all([load(env, "VechiclePosition"), load(env, "TripUpdates")]);
    const out = tripUpdates(tu, vp);
    if (path === "/trip-updates.pb") return reply(out.bytes, "application/x-protobuf");
    return reply(JSON.stringify({ ok: true, trip_updates: out.count, vehicles: vehiclePositions(vp).count, feed_timestamp: out.timestamp }),
      "application/json", 200, 0);
  } catch (err) {
    return reply(path === "/health" ? JSON.stringify({ ok: false, error: String(err.message || err) }) : "upstream unavailable",
      path === "/health" ? "application/json" : "text/plain", 503, 0);
  }
}

/// One cache entry per route: query strings (e.g. cache busters) must not bypass the cache
/// and reach AMTAB on every request.
export function cacheKeys(request) {
  const url = new URL(request.url);
  const base = `${url.origin}${route(request)}`;
  return { fresh: new Request(base), stale: new Request(`${base}?stale`) };
}

export default {
  async fetch(request, env, ctx) {
    const path = route(request);
    const cacheable = request.method === "GET" && path !== "/health" && ROUTES.includes(path);
    if (!cacheable) return handle(request, env);
    const cache = caches.default;
    const keys = cacheKeys(request);
    const hit = await cache.match(keys.fresh);
    if (hit) return hit;
    const res = await handle(request, env);
    if (res.ok) {
      const stale = new Response(res.clone().body, res);
      stale.headers.set("Cache-Control", `public, max-age=${STALE_SECONDS}`);
      ctx.waitUntil(Promise.all([cache.put(keys.fresh, res.clone()), cache.put(keys.stale, stale)]));
      return res;
    }
    const old = await cache.match(keys.stale);
    if (old) {
      const served = new Response(old.body, old);
      served.headers.set("Cache-Control", "public, max-age=5");
      served.headers.set("X-Feed-Stale", "1");
      return served;
    }
    return res;
  },
};
