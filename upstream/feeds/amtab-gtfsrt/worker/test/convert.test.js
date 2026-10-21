import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { tripUpdates, vehiclePositions, romeDate } from "../src/gtfsrt.js";
import worker, { handle, cacheKeys } from "../src/index.js";

const fx = (n) => new URL(`../../tests/fixtures/${n}`, import.meta.url);
const json = (n) => JSON.parse(readFileSync(fx(n), "utf8"));
const golden = (n) => new Uint8Array(Buffer.from(readFileSync(fx(n), "utf8"), "base64"));
const tu = json("trip_updates.json");
const vp = json("vehicle_positions.json");

test("trip updates are byte-identical to the Python reference converter", () => {
  assert.deepEqual(tripUpdates(tu, vp).bytes, golden("trip_updates.golden.pb.b64"));
});

test("vehicle positions are byte-identical to the Python reference converter", () => {
  assert.deepEqual(vehiclePositions(vp).bytes, golden("vehicle_positions.golden.pb.b64"));
});

test("entity counts", () => {
  assert.equal(tripUpdates(tu, vp).count, 21);
  assert.equal(vehiclePositions(vp).count, 22);
});

test("service date uses Rome time, not UTC", () => {
  // 2026-10-06 22:30 UTC is already 7 October in Rome.
  assert.equal(romeDate(Date.UTC(2026, 9, 6, 22, 30) / 1000), "20261007");
});

test("empty upstream payload gives a valid empty feed", () => {
  const out = tripUpdates({ Header: { Timestamp: 1 }, Entities: null }, {});
  assert.equal(out.count, 0);
  assert.deepEqual([...out.bytes.slice(0, 2)], [0x0a, 0x09]); // header message only: version, incrementality, timestamp
});

test("HTTP routes", async () => {
  const load = async (_env, p) => (p === "TripUpdates" ? tu : vp);
  const pb = await handle(new Request("https://x/trip-updates.pb"), {}, load);
  assert.equal(pb.status, 200);
  assert.equal(pb.headers.get("content-type"), "application/x-protobuf");
  const health = await (await handle(new Request("https://x/health"), {}, load)).json();
  assert.deepEqual([health.ok, health.trip_updates, health.vehicles], [true, 21, 22]);
  assert.equal((await handle(new Request("https://x/nope"), {}, load)).status, 404);
  const down = await handle(new Request("https://x/health"), {}, async () => { throw new Error("timeout"); });
  assert.equal(down.status, 503);
});

test("only GET and HEAD", async () => {
  const load = async () => { throw new Error("must not be called"); };
  assert.equal((await handle(new Request("https://x/trip-updates.pb", { method: "POST" }), {}, load)).status, 405);
});

test("trip updates load both upstream feeds at once", async () => {
  const vp = JSON.parse(readFileSync(fx("vehicle_positions.json"), "utf8"));
  const tu = JSON.parse(readFileSync(fx("trip_updates.json"), "utf8"));
  let running = 0, peak = 0;
  const load = async (_env, path) => {
    running++; peak = Math.max(peak, running);
    await new Promise((r) => setTimeout(r, 10));
    running--;
    return path === "TripUpdates" ? tu : vp;
  };
  assert.equal((await handle(new Request("https://x/trip-updates.pb"), {}, load)).status, 200);
  assert.equal(peak, 2);
});

test("cache key ignores query strings", () => {
  const a = cacheKeys(new Request("https://x/trip-updates.pb?bust=1")).fresh.url;
  const b = cacheKeys(new Request("https://x/trip-updates.pb/")).fresh.url;
  assert.equal(a, "https://x/trip-updates.pb");
  assert.equal(a, b);
});

test("serves the last good feed while upstream is down", async () => {
  const store = new Map();
  globalThis.caches = { default: {
    match: async (req) => { const r = store.get(req.url); return r ? r.clone() : undefined; },
    put: async (req, res) => { store.set(req.url, res); },
  } };
  const pending = [];
  const ctx = { waitUntil: (p) => pending.push(p) };
  const good = new Response(new Uint8Array([1, 2, 3]), { headers: { "Content-Type": "application/x-protobuf" } });
  store.set("https://x/trip-updates.pb?stale", good);
  const env = { AMTAB_BASE: "https://127.0.0.1:9/none" };
  const realFetch = globalThis.fetch;
  globalThis.fetch = async () => { throw new Error("down"); };
  try {
    const res = await worker.fetch(new Request("https://x/trip-updates.pb"), env, ctx);
    assert.equal(res.status, 200);
    assert.equal(res.headers.get("X-Feed-Stale"), "1");
    assert.deepEqual([...new Uint8Array(await res.arrayBuffer())], [1, 2, 3]);
  } finally {
    globalThis.fetch = realFetch;
    delete globalThis.caches;
  }
});
