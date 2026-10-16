// Pregenerates a Medieval Fantasy City Generator layout for every station.
//
// Usage:  node tools/generate-station-towns.js data/towns/tasks.txt data/towns [workers]
//
// The task file (written by `java -jar build/Districts.jar --plan-towns`) has one line per station:
//   districtId regionId stationId size seed classKey
// Output: one file per district, data/towns/district-NN.bin, little-endian:
//   "DTWN" u32 version=1, u32 count, u32 quantum (coordinate units per generator unit)
//   index  count × { u16 regionId, u16 stationId, u32 offset, u32 length }
//   records, each zlib-deflated:
//     u8 class, u8 size, u8 seedAttempt, f32 cityRadius
//     u16 wards × { u8 type, u8 walled, poly shape, u16 buildings × poly }
//     poly wall, points towers, points gates, u16 streets × poly, u16 roads × poly
//   poly/points = u16 count, then count × (i16 dx, i16 dy) deltas in 1/quantum generator units.
"use strict";
const fs = require("fs");
const path = require("path");
const zlib = require("zlib");
const os = require("os");
const { fork } = require("child_process");

const QUANTUM = 16;
const CLASSES = ["largeTown", "smallCity", "largeCity"];
const WARD_TYPES = ["Empty", "Ward", "CraftsmenWard", "MerchantWard", "Cathedral", "AdministrationWard", "Slum",
    "PatriciateWard", "Market", "MilitaryWard", "Park", "GateWard", "Farm", "Castle"];

if (process.argv[2] === "--worker") runWorker();
else runMaster();

function runMaster() {
    const [taskFile, outputDir, workerArg] = process.argv.slice(2);
    if (!taskFile || !outputDir) {
        console.error("Usage: node generate-station-towns.js <tasks.txt> <outputDir> [workers]");
        process.exit(2);
    }
    const byDistrict = new Map();
    for (const line of fs.readFileSync(taskFile, "utf8").split(/\r?\n/)) {
        if (!line.trim()) continue;
        const [district, region, station, size, seed, cls] = line.trim().split(/\s+/);
        if (!byDistrict.has(+district)) byDistrict.set(+district, []);
        byDistrict.get(+district).push({ region: +region, station: +station, size: +size, seed: +seed, cls });
    }
    fs.mkdirSync(outputDir, { recursive: true });
    // Biggest districts first so the slowest jobs don't finish last.
    const jobs = [...byDistrict.entries()].sort((a, b) => b[1].length - a[1].length);
    const total = jobs.reduce((sum, [, tasks]) => sum + tasks.length, 0);
    const workers = Math.max(1, Math.min(jobs.length, +workerArg || os.cpus().length - 1));
    console.log(`Generating ${total} station layouts in ${jobs.length} districts with ${workers} workers...`);
    const started = Date.now();
    let done = 0, retried = 0, bytes = 0, active = 0;
    const next = (worker) => {
        const job = jobs.shift();
        if (!job) { worker.kill(); if (--active === 0) finish(); return; }
        const [district, tasks] = job;
        worker.send({ district, tasks, file: path.join(outputDir, `district-${String(district).padStart(2, "0")}.bin`) });
    };
    const finish = () => {
        console.log(`Done: ${done} layouts, ${retried} needed a fallback seed, ${(bytes / 1e6).toFixed(1)} MB, ` +
            `${((Date.now() - started) / 1000).toFixed(1)} s`);
    };
    for (let i = 0; i < workers; i++) {
        const worker = fork(__filename, ["--worker"]);
        active++;
        worker.on("message", (result) => {
            done += result.count; retried += result.retried; bytes += result.bytes;
            process.stdout.write(`  district ${String(result.district).padStart(2)}: ${result.count} layouts\n`);
            next(worker);
        });
        worker.on("exit", (code) => { if (code) { console.error(`Worker failed with exit code ${code}`); process.exit(1); } });
        next(worker);
    }
}

function runWorker() {
    const gen = require(path.join(__dirname, "towngen", "towngen.js")).StationTownGen;
    process.on("message", ({ district, tasks, file }) => {
        const records = [];
        let retried = 0;
        tasks.sort((a, b) => a.region - b.region || a.station - b.station);
        for (const task of tasks) {
            let town = null, attempt = 0;
            // A handful of seeds make the generator fail; fall back to nearby seeds, reproducibly.
            for (; attempt < 8 && !town; attempt++) {
                try { town = gen.generateTown(task.size, task.seed + attempt * 7919); } catch (error) { town = null; }
            }
            if (!town) throw new Error(`Could not generate district ${district} station ${task.region}/${task.station}`);
            if (attempt > 1) retried++;
            records.push({ task, data: zlib.deflateSync(encode(task, town, attempt - 1), { level: 9 }) });
        }
        const headerSize = 16 + records.length * 12;
        const header = Buffer.alloc(headerSize);
        header.write("DTWN", 0, "ascii");
        header.writeUInt32LE(1, 4);
        header.writeUInt32LE(records.length, 8);
        header.writeUInt32LE(QUANTUM, 12);
        let offset = headerSize;
        records.forEach(({ task, data }, index) => {
            const at = 16 + index * 12;
            header.writeUInt16LE(task.region, at);
            header.writeUInt16LE(task.station, at + 2);
            header.writeUInt32LE(offset, at + 4);
            header.writeUInt32LE(data.length, at + 8);
            offset += data.length;
        });
        const temporary = file + ".tmp";
        fs.writeFileSync(temporary, Buffer.concat([header, ...records.map((record) => record.data)]));
        fs.renameSync(temporary, file);
        process.send({ district, count: records.length, retried, bytes: offset });
    });
}

function encode(task, town, attempt) {
    const out = new Writer();
    out.u8(Math.max(0, CLASSES.indexOf(task.cls)));
    out.u8(task.size);
    out.u8(attempt);
    out.f32(town.radius);
    out.u16(town.wards.length);
    for (const ward of town.wards) {
        const type = WARD_TYPES.indexOf(ward.type);
        out.u8(type < 0 ? 1 : type);
        out.u8(ward.walled ? 1 : 0);
        out.poly(ward.shape);
        out.u16(ward.buildings.length);
        for (const building of ward.buildings) out.poly(building);
    }
    out.poly(town.wall || []);
    out.poly(town.towers);
    out.poly(town.gates);
    out.u16(town.streets.length);
    for (const street of town.streets) out.poly(street);
    out.u16(town.roads.length);
    for (const road of town.roads) out.poly(road);
    return out.buffer();
}

class Writer {
    constructor() { this.chunks = []; this.current = Buffer.alloc(1 << 16); this.at = 0; }
    ensure(n) {
        if (this.at + n <= this.current.length) return;
        this.chunks.push(this.current.subarray(0, this.at));
        this.current = Buffer.alloc(Math.max(1 << 16, n));
        this.at = 0;
    }
    u8(v) { this.ensure(1); this.current.writeUInt8(v, this.at); this.at += 1; }
    u16(v) { this.ensure(2); this.current.writeUInt16LE(v, this.at); this.at += 2; }
    i16(v) { this.ensure(2); this.current.writeInt16LE(Math.max(-32768, Math.min(32767, v)), this.at); this.at += 2; }
    f32(v) { this.ensure(4); this.current.writeFloatLE(v, this.at); this.at += 4; }
    // Flat [x0, y0, x1, y1, ...] in generator units, stored as quantized deltas.
    poly(flat) {
        const count = flat.length / 2;
        this.u16(count);
        let px = 0, py = 0;
        for (let i = 0; i < count; i++) {
            const x = Math.round(flat[2 * i] * QUANTUM), y = Math.round(flat[2 * i + 1] * QUANTUM);
            this.i16(x - px); this.i16(y - py);
            px = x; py = y;
        }
    }
    buffer() { this.chunks.push(this.current.subarray(0, this.at)); return Buffer.concat(this.chunks); }
}
