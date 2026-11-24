// Generates the simple 3D sheaths for wom:hollow_longsword (grey, plain straight box like the blade itself):
//   node tools/make_hollow_longsword_sheath_obj.js
// One variant, used in both WAIST and BACK mode: hollow_longsword_sheath_with_band, a sheath with a cord wound round
// it behind the collar, as if it were tied to the belt (a plain one looked bare).
// Writes OBJ + MTL + item model JSON + a 16x16 texture into
// src/main/resources/assets/epic_fight_sheathing_master/.
//
// Frame: the sheath is drawn exactly where the blade is drawn, so the OBJ uses the raw coordinates of Weapons of
// Miracles' own hollow_longsword.obj with the same "thirdperson_righthand" display transform in the item model JSON.
// Boxes are designed in the ITEM-LOCAL frame (after that display transform: x' = vx - 0.5 + 0.469,
// y' = -(vz - 0.5) - 0.531, z' = (vy - 0.5) + 0.531), where the blade runs along +y:
//   blade axis x' -0.031, z' 0.035; the blade is 0.05 thick (x') and 0.15 wide (z'), from y' 0.206 to the tip at 1.818;
//   the guard fills y' 0.100..0.149 (x' -0.069..0.006, z' -0.207..0.277). The collar starts flush against the guard.
// and are converted back to raw OBJ coordinates (a proper rotation: raw = (x' + 0.031, z' - 0.031 ... see toRaw)).
const fs = require('fs'), zlib = require('zlib'), path = require('path');
const OUT = path.join(__dirname, '..', 'src/main/resources/assets/epic_fight_sheathing_master');
const NS = 'epic_fight_sheathing_master';
const TEXTURE = 'hollow_longsword_sheath';

// display of wom:hollow_longsword.json (thirdperson_righthand)
const DISP = { translation: [7.5, -8.5, 8.5], rotation: [90, 0, 0], scale: [1, 1, 1] };
const T = DISP.translation.map(v => v / 16);

// item-local (x', y', z') -> raw OBJ coordinates: x' = vx - 0.5 + Tx, y' = -(vz - 0.5) + Ty, z' = (vy - 0.5) + Tz
const toRaw = ([x, y, z]) => [x - T[0] + 0.5, z - T[2] + 0.5, -(y - T[1]) + 0.5];
// directions: (nx, ny, nz) -> raw = (nx, nz, -ny)
const dirRaw = ([x, y, z]) => [x, z, -y];

// Texture (16x16), four 4-pixel columns: body, fittings, cord, hole. u = centre of each part.
const PARTS = {
  body: { u: 2 / 16, rgba: [92, 95, 102, 255] },     // mid grey steel
  metal: { u: 6 / 16, rgba: [154, 158, 166, 255] },  // light grey fittings
  cord: { u: 10 / 16, rgba: [48, 50, 58, 255] },     // dark charcoal cord
  hole: { u: 14 / 16, rgba: [5, 5, 7, 255] },        // the dark opening of the mouth
};

const CX = -0.031, CZ = 0.035; // blade axis in the item-local frame

function build(withBand) {
  const verts = [], uvs = [], norms = [], faces = [];
  const normIndex = new Map(), uvIndex = new Map();
  const nIdx = n => {
    const k = n.join(',');
    if (!normIndex.has(k)) { norms.push(n); normIndex.set(k, norms.length); }
    return normIndex.get(k);
  };
  const uIdx = part => {
    if (!uvIndex.has(part)) { uvs.push([PARTS[part].u, 0.5]); uvIndex.set(part, uvs.length); }
    return uvIndex.get(part);
  };

  // A closed box along y: y from yA to yB, half sizes hx (x') and hz (z'), centred on the blade axis. Six faces, each
  // with its four corners ordered counter-clockwise seen from outside (checked in the item-local frame against the
  // face normal), so back-face culling keeps every face. toRaw is a proper rotation, so the winding survives.
  function box(yA, yB, hx, hz, part) {
    const uv = uIdx(part);
    const [y0, y1] = [Math.min(yA, yB), Math.max(yA, yB)];
    const c = [];
    for (const z of [CZ - hz, CZ + hz]) for (const y of [y0, y1]) for (const x of [CX - hx, CX + hx]) c.push([x, y, z]);
    const idx = (xs, ys, zs) => (zs ? 4 : 0) + (ys ? 2 : 0) + (xs ? 1 : 0);
    const base = verts.length;
    for (const v of c) verts.push(toRaw(v));
    const faceDefs = [
      { n: [1, 0, 0], q: [idx(1, 0, 0), idx(1, 1, 0), idx(1, 1, 1), idx(1, 0, 1)] },
      { n: [-1, 0, 0], q: [idx(0, 0, 0), idx(0, 0, 1), idx(0, 1, 1), idx(0, 1, 0)] },
      { n: [0, 1, 0], q: [idx(0, 1, 0), idx(0, 1, 1), idx(1, 1, 1), idx(1, 1, 0)] },
      { n: [0, -1, 0], q: [idx(0, 0, 0), idx(1, 0, 0), idx(1, 0, 1), idx(0, 0, 1)] },
      { n: [0, 0, 1], q: [idx(0, 0, 1), idx(1, 0, 1), idx(1, 1, 1), idx(0, 1, 1)] },
      { n: [0, 0, -1], q: [idx(0, 0, 0), idx(0, 1, 0), idx(1, 1, 0), idx(1, 0, 0)] },
    ];
    for (const fd of faceDefs) {
      let q = fd.q.slice();
      const winding = qq => {
        const p = qq.map(i => c[i]);
        const e1 = p[1].map((v, k) => v - p[0][k]), e2 = p[2].map((v, k) => v - p[0][k]);
        const cr = [e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0]];
        return cr[0] * fd.n[0] + cr[1] * fd.n[1] + cr[2] * fd.n[2];
      };
      if (winding(q) < 0) q = [q[0], q[3], q[2], q[1]];
      if (winding(q) <= 0) throw new Error('bad winding');
      faces.push({ v: q.map(i => base + i + 1), uv, n: nIdx(dirRaw(fd.n)) });
    }
  }

  // body: one plain straight box, from inside the collar to just short of the tip cap
  box(0.19, 1.81, 0.030, 0.082, 'body');
  // grey fittings: mouth collar (koiguchi) flush against the guard's top face (y' 0.149), and the tip cap
  box(0.1494, 0.2194, 0.034, 0.088, 'metal');
  box(1.76, 1.85, 0.032, 0.084, 'metal');
  // the opening of the mouth: a black rectangle, a little larger than the blade (0.05 x 0.15), standing 0.0017 proud
  // of the collar's guard-side face so it reads as a hole when the blade is out; the guard (y' up to 0.149) hides it
  // while the blade is in the sheath
  box(0.1477, 0.1672, 0.027, 0.078, 'hole');
  if (withBand) {
    // a cord wound round the sheath above the collar: three thin turns, slightly proud of the body
    for (const y of [0.255, 0.315, 0.375]) box(y, y + 0.03, 0.0335, 0.0855, 'cord');
  }
  return { verts, uvs, norms, faces };
}

function write(name, withBand) {
  const { verts, uvs, norms, faces } = build(withBand);
  let obj = `# ${name}, generated by tools/make_hollow_longsword_sheath_obj.js (do not edit by hand)\n`;
  obj += "# same frame as wom:hollow_longsword's own hollow_longsword.obj (tip = +y in the item-local frame)\n";
  obj += `mtllib ${NS}:models/item/${TEXTURE}.mtl\n`;
  for (const v of verts) obj += `v ${v[0].toFixed(5)} ${v[1].toFixed(5)} ${v[2].toFixed(5)}\n`;
  for (const t of uvs) obj += `vt ${t[0].toFixed(5)} ${t[1]}\n`;
  for (const n of norms) obj += `vn ${n[0]} ${n[1]} ${n[2]}\n`;
  obj += 'usemtl sheath\n';
  for (const f of faces) obj += 'f ' + f.v.map(i => `${i}/${f.uv}/${f.n}`).join(' ') + '\n';
  const model = {
    loader: 'forge:obj',
    model: `${NS}:models/item/${name}.obj`,
    automatic_culling: false,
    flip_v: false,
    textures: { particle: `${NS}:item/${TEXTURE}` },
    display: { thirdperson_righthand: DISP },
  };
  fs.writeFileSync(path.join(OUT, `models/item/${name}.obj`), obj);
  fs.writeFileSync(path.join(OUT, `models/item/${name}.json`), JSON.stringify(model, null, 2) + '\n');
  console.log('written', name, verts.length, 'vertices', faces.length, 'faces');
}

function png(w, h, pixel) {
  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) {
    raw[y * (w * 4 + 1)] = 0;
    for (let x = 0; x < w; x++) {
      const [r, g, b, a] = pixel(x, y);
      const o = y * (w * 4 + 1) + 1 + x * 4;
      raw[o] = r; raw[o + 1] = g; raw[o + 2] = b; raw[o + 3] = a;
    }
  }
  const crcTable = Array.from({ length: 256 }, (_, n) => {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    return c >>> 0;
  });
  const crc = buf => {
    let c = 0xffffffff;
    for (const b of buf) c = crcTable[(c ^ b) & 0xff] ^ (c >>> 8);
    return (c ^ 0xffffffff) >>> 0;
  };
  const chunk = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
    const td = Buffer.concat([Buffer.from(type), data]);
    const c = Buffer.alloc(4); c.writeUInt32BE(crc(td));
    return Buffer.concat([len, td, c]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]);
}

fs.mkdirSync(path.join(OUT, 'models/item'), { recursive: true });
fs.mkdirSync(path.join(OUT, 'textures/item'), { recursive: true });
write('hollow_longsword_sheath_with_band', true);
fs.writeFileSync(path.join(OUT, `models/item/${TEXTURE}.mtl`),
  `# materials of the hollow_longsword sheaths\nnewmtl sheath\nKd 1.0 1.0 1.0\nmap_Kd ${NS}:item/${TEXTURE}\n`);
fs.writeFileSync(path.join(OUT, `textures/item/${TEXTURE}.png`),
  png(16, 16, (x) => (x < 4 ? PARTS.body.rgba : x < 8 ? PARTS.metal.rgba : x < 12 ? PARTS.cord.rgba : PARTS.hole.rgba)));
