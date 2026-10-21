// AMTAB Bari JSON GTFS-Realtime -> GTFS-Realtime protobuf, without dependencies.
// Mirrors ../amtab_gtfsrt.py byte for byte (checked against its output in test/).

export const SPEED_DIVISOR = 3.6; // AMTAB reports km/h, GTFS-RT expects m/s

// ---- minimal protobuf writer (fields are written in field-number order, like the reference implementation)
class Writer {
  constructor() { this.bytes = []; }
  varint(v) {
    let n = BigInt.asUintN(64, BigInt(v));
    do {
      let b = Number(n & 0x7fn);
      n >>= 7n;
      if (n) b |= 0x80;
      this.bytes.push(b);
    } while (n);
  }
  tag(field, wire) { this.varint((field << 3) | wire); }
  uint(field, v) { this.tag(field, 0); this.varint(v); }
  int(field, v) { this.tag(field, 0); this.varint(v); } // int32/int64: negatives as 10-byte two's complement
  bool(field, v) { this.tag(field, 0); this.varint(v ? 1 : 0); }
  string(field, s) { this.bytesField(field, new TextEncoder().encode(String(s))); }
  float(field, v) {
    this.tag(field, 5);
    const b = new Uint8Array(4);
    new DataView(b.buffer).setFloat32(0, v, true);
    this.bytes.push(...b);
  }
  bytesField(field, arr) { this.tag(field, 2); this.varint(arr.length); this.bytes.push(...arr); }
  message(field, fn) { const w = new Writer(); fn(w); this.bytesField(field, w.bytes); }
  finish() { return new Uint8Array(this.bytes); }
}

const isInt = (v) => Number.isInteger(v);
const present = (v) => v !== null && v !== undefined;

export function romeDate(ts) {
  const p = new Intl.DateTimeFormat("en-CA", { timeZone: "Europe/Rome", year: "numeric", month: "2-digit", day: "2-digit" })
    .formatToParts(new Date(ts * 1000));
  const g = (t) => p.find((x) => x.type === t).value;
  return `${g("year")}${g("month")}${g("day")}`;
}

function headerTimestamp(src) {
  return Math.trunc(Number(src?.Header?.Timestamp) || Date.now() / 1000);
}

function writeHeader(w, ts) {
  w.message(1, (h) => { h.string(1, "2.0"); h.uint(2, 0); h.uint(3, ts); });
}

function writeTrip(w, trip, startDate) {
  if (trip.TripId) w.string(1, trip.TripId);
  const sd = trip.StartDate || startDate;
  if (sd) w.string(3, sd);
  if (isInt(trip.schedule_relationship)) w.uint(4, trip.schedule_relationship);
  if (trip.RouteId) w.string(5, trip.RouteId);
}

function writeEvent(w, field, ev) {
  if (!ev || (!present(ev.Delay) && !ev.Time)) return;
  w.message(field, (e) => {
    if (present(ev.Delay)) e.int(1, Math.trunc(ev.Delay));
    if (ev.Time) e.int(2, Math.trunc(ev.Time));
  });
}

function startDatesByTrip(vp) {
  const out = new Map();
  for (const e of vp?.Entities || []) {
    const t = e?.Vehicle?.Trip;
    if (t?.TripId && t?.StartDate) out.set(String(t.TripId), String(t.StartDate));
  }
  return out;
}

function vehiclesByTrip(vp) {
  const out = new Map();
  for (const e of vp?.Entities || []) {
    const v = e?.Vehicle;
    const tid = v?.Trip?.TripId;
    if (tid && v?.Vehicle) out.set(String(tid), v.Vehicle);
  }
  return out;
}

export function tripUpdates(tu, vp = {}) {
  const dates = startDatesByTrip(vp);
  const vehicles = vehiclesByTrip(vp);
  const ts = headerTimestamp(tu);
  const today = romeDate(ts);
  const w = new Writer();
  writeHeader(w, ts);
  let count = 0;
  for (const e of tu?.Entities || []) {
    const t = e?.TripUpdate;
    if (!t?.Trip?.TripId) continue;
    const tid = String(t.Trip.TripId);
    count++;
    w.message(2, (ent) => {
      ent.string(1, e.Id || tid);
      ent.message(3, (u) => {
        u.message(1, (d) => writeTrip(d, t.Trip, dates.get(tid) || today));
        for (const s of t.StopTimeUpdates || []) {
          u.message(2, (x) => {
            if (present(s.StopSequence)) x.uint(1, s.StopSequence);
            writeEvent(x, 2, s.Arrival);
            writeEvent(x, 3, s.Departure);
            if (s.StopId) x.string(4, s.StopId);
            if (isInt(s.schedule_relationship)) x.uint(5, s.schedule_relationship);
          });
        }
        const veh = t.Vehicle || vehicles.get(tid);
        if (veh?.Id) u.message(3, (v) => { v.string(1, veh.Id); if (veh.Label) v.string(2, veh.Label); });
        u.uint(4, ts);
        if (present(t.Delay)) u.int(5, Math.trunc(t.Delay));
      });
    });
  }
  return { bytes: w.finish(), count, timestamp: ts };
}

export function vehiclePositions(vp) {
  const ts = headerTimestamp(vp);
  const today = romeDate(ts);
  const w = new Writer();
  writeHeader(w, ts);
  let count = 0;
  for (const e of vp?.Entities || []) {
    const v = e?.Vehicle;
    if (!v || e.IsDeleted) continue;
    const pos = v.Position || {};
    if (!present(pos.Latitude) || !present(pos.Longitude)) continue;
    count++;
    w.message(2, (ent) => {
      ent.string(1, e.Id || v.Vehicle?.Id);
      ent.message(4, (x) => {
        if (v.Trip) x.message(1, (d) => writeTrip(d, v.Trip, today));
        x.message(2, (p) => {
          p.float(1, Number(pos.Latitude));
          p.float(2, Number(pos.Longitude));
          if (present(pos.Bearing)) p.float(3, Number(pos.Bearing));
          if (present(pos.Speed)) p.float(5, Number(pos.Speed) / SPEED_DIVISOR);
        });
        if (present(v.CurrentStopSequence)) x.uint(3, v.CurrentStopSequence);
        if (isInt(v.CurrentStatus)) x.uint(4, v.CurrentStatus);
        x.uint(5, Math.trunc(Number(v.Timestamp) || ts));
        if (isInt(v.congestion_level)) x.uint(6, v.congestion_level);
        if (v.StopId) x.string(7, v.StopId);
        if (v.Vehicle && (v.Vehicle.Id || v.Vehicle.Label)) {
          x.message(8, (d) => { if (v.Vehicle.Id) d.string(1, v.Vehicle.Id); if (v.Vehicle.Label) d.string(2, v.Vehicle.Label); });
        }
      });
    });
  }
  return { bytes: w.finish(), count, timestamp: ts };
}
