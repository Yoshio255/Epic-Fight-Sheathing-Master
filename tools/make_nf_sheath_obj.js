// Generates the tapered sheaths for efn:nf_shortsword (red lacquer + gold fittings) and
// efn:nf_shortsword_2 (black lacquer + silver fittings):
//   node tools/make_nf_sheath_obj.js
// Writes OBJ + MTL + item model JSON + a 128x128 texture per weapon into
// src/main/resources/assets/epic_fight_sheathing_master/.
//
// Frame of the OBJ = Forge's raw block space (centre of the block = 0.5, 0.5, 0.5):
//   x = thickness of the blade, y = width, +z = towards the sheath mouth, tip = -z.
// MeshWeaponIdleRenderer.SHEATH_FIT puts the centre at the right place in the weapon's
// Tool_R joint space and tilts it (the blade rises ~10.5 degrees towards its tip there),
// so the tilt is NOT part of the OBJ. Measurements (blade width, joint space z) come
// from the EFN meshes.
//
// Texture layout (128x128, ~120 px per block like the EFN weapon textures):
//   x   0..15  lacquer tile of the +x flat side      x  16..31  lacquer tile of the -x flat side
//   x  32..38  strip for the narrow / chamfer faces  x  64..127 metal (collar, bands, tip cap)
//   y   4..123 along the sheath, mouth at the top
const fs = require('fs'), zlib = require('zlib'), path = require('path');
const OUT = path.join(__dirname, '..', 'src/main/resources/assets/epic_fight_sheathing_master');
const NS = 'epic_fight_sheathing_master';
const MARGIN = 0.025;            // clearance on each side of the blade
const THICK_BODY = 0.06, THICK_TIP = 0.05;
const TAPER_START_Z = -1.0;     // below this (joint space z) the thickness tapers to THICK_TIP
const MIN_WIDTH = 0.075;        // the tip is blunt (not pointed): never thinner than this
const CHAMFER = 0.012;          // corner cut of the octagonal cross section
const PX = 120;                 // texture pixels per block along the sheath

// z positions are in the weapon's joint space (mouth = front face of the guard)
const WEAPONS = {
  nf_shortsword: {
    tiltDeg: 10.8, mouthZ: -0.34, endZ: -1.36, style: 'red',
    sections: [-0.34, -0.55, -0.80, -1.00, -1.08, -1.14, -1.20, -1.24, -1.28, -1.32, -1.36],
    // measured blade width by z; constant before the first point / after the last
    blade: [[-1.00, 0.095], [-1.20, 0.049], [-1.28, 0.029], [-1.36, 0.029]],
  },
  nf_shortsword_2: {
    // mouth at the guard's front face (z -0.34); the guard narrows into a thick, wide ricasso
    // up to z -0.47, so the collar is a long, fat band that covers it (no gap, no bare blade root)
    tiltDeg: 10.3, mouthZ: -0.34, endZ: -1.36, style: 'rune', glow: true,
    collar: { a: 0.01, b: -0.12, growX: 0.0225, growY: 0.02 },
    sections: [-0.34, -0.55, -0.80, -1.00, -1.08, -1.14, -1.20, -1.24, -1.28, -1.32, -1.36],
    blade: [[-1.00, 0.100], [-1.20, 0.041], [-1.28, 0.017], [-1.36, 0.017]],
  },
};

const PALETTE = {
  red: {
    lacquer: [138, 18, 24], accent: [214, 176, 64], accentDark: [140, 100, 26], accentLight: [248, 226, 140],
    hl: [220, 110, 100], edge: [74, 8, 12], metal: [214, 176, 64],
  },
  rune: {
    lacquer: [26, 26, 32], accent: [196, 202, 214], accentDark: [108, 114, 130], accentLight: [238, 242, 250],
    hl: [96, 96, 112], edge: [10, 10, 14], metal: [190, 196, 208],
    glyph: [110, 214, 255],         // runes: light blue
  },
};

function interp(pts, z) {
  if (z >= pts[0][0]) return pts[0][1];
  for (let i = 0; i < pts.length - 1; i++) {
    const [z0, w0] = pts[i], [z1, w1] = pts[i + 1];
    if (z <= z0 && z >= z1) return w0 + (w1 - w0) * (z0 - z) / (z0 - z1);
  }
  return pts[pts.length - 1][1];
}
const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const len3 = a => Math.hypot(a[0], a[1], a[2]);
const norm = a => { const l = len3(a) || 1; return a.map(v => v / l); };

// octagon corners (counter-clockwise from the bottom-left of the bottom edge)
function octagon(hx, hy, z) {
  const c = Math.min(CHAMFER, hx * 0.45, hy * 0.45);
  return [[-hx + c, -hy, z], [hx - c, -hy, z], [hx, -hy + c, z], [hx, hy - c, z],
          [hx - c, hy, z], [-hx + c, hy, z], [-hx, hy - c, z], [-hx, -hy + c, z]];
}
// edge i -> i+1 of the octagon: narrow, chamfer, +x flat side, chamfer, narrow, chamfer, -x flat side, chamfer
const EDGE_KIND = ['narrow', 'chamfer', 'A', 'chamfer', 'narrow', 'chamfer', 'B', 'chamfer'];

function build(name, cfg) {
  const cosT = Math.cos(cfg.tiltDeg * Math.PI / 180);
  const L = (cfg.mouthZ - cfg.endZ) / cosT;              // length along the sheath axis
  const lzOf = z => L / 2 - (cfg.mouthZ - z) / cosT;     // joint-space z -> model axial coordinate
  const dimsAt = z => {
    const w = Math.max(MIN_WIDTH, interp(cfg.blade, z) + 2 * MARGIN);
    const k = Math.min(1, Math.max(0, (TAPER_START_Z - z) / (TAPER_START_Z - cfg.endZ)));
    return { hx: (THICK_BODY + (THICK_TIP - THICK_BODY) * k) / 2, hy: w / 2 };
  };
  const rings = cfg.sections.map(z => ({ z, lz: lzOf(z), ...dimsAt(z) }));

  const verts = [], uvs = [], normals = [], lines = [];
  const C = 0.5;                                          // block centre
  const addV = p => { verts.push(p); return verts.length; };
  const addUV = (u, v) => { uvs.push([u, v]); return uvs.length; };
  const addN = n => { normals.push(n); return normals.length; };

  // quad from 4 corner positions forming a loop; `inside` is a point inside the solid,
  // the quad is wound so that its normal points away from it
  function quad(p, inside, uvQuad) {
    let n = norm(cross(sub(p[1], p[0]), sub(p[2], p[0])));
    const centre = [0, 1, 2].map(i => (p[0][i] + p[1][i] + p[2][i] + p[3][i]) / 4);
    if (dot(n, sub(centre, inside)) < 0) {
      p = [p[0], p[3], p[2], p[1]]; uvQuad = [uvQuad[0], uvQuad[3], uvQuad[2], uvQuad[1]]; n = n.map(v => -v);
    }
    const ni = addN(n);
    const idx = p.map((pt, i) => `${addV([pt[0] + C, pt[1] + C, pt[2] + C])}/${addUV(...uvQuad[i])}/${ni}`);
    lines.push('f ' + idx.join(' '));
  }
  // metal pieces use a patch of the metal area sized like the face (same pixel density as the body)
  // (black = the black collar patch at x 41..62, like the black mouth of EFN's own scabbards)
  function metalUV(p, black) {
    const du = len3(sub(p[1], p[0])) * PX / 128, dv = len3(sub(p[2], p[1])) * PX / 128;
    const u0 = black ? 42 / 128 : 0.52, v0 = 0.06, maxU = black ? 20 / 128 : 0.44;
    return [[u0, v0], [u0 + Math.min(du, maxU), v0], [u0 + Math.min(du, maxU), v0 + Math.min(dv, 0.88)], [u0, v0 + Math.min(dv, 0.88)]];
  }
  // closed octagonal metal band from zA (towards the mouth) to zB, as big as the sheath there + grow
  function band(zA, zB, growX, growY, dimsZ, black) {
    const d = dimsAt(dimsZ === undefined ? (zA + zB) / 2 : dimsZ);
    const a = octagon(d.hx + growX, d.hy + growY, lzOf(zA)), b = octagon(d.hx + growX, d.hy + growY, lzOf(zB));
    const mid = (lzOf(zA) + lzOf(zB)) / 2;
    for (let i = 0; i < 8; i++) {
      const j = (i + 1) % 8, p = [a[i], a[j], b[j], b[i]];
      quad(p, [0, 0, mid], metalUV(p, black));
    }
    for (const [ring, other] of [[a, lzOf(zB)], [b, lzOf(zA)]]) {
      const centre = [0, 0, ring[0][2]];
      for (let j = 0; j < 4; j++) {
        const p = [centre, ring[2 * j], ring[2 * j + 1], ring[(2 * j + 2) % 8]];
        quad(p, [0, 0, other], metalUV(p, black));
      }
    }
  }

  // ---- lacquered body ----
  lines.push('g body');
  const vOf = lz => (4 + 120 * (L / 2 - lz) / L) / 128;    // along the sheath, mouth at the top (rows 4..124)
  const TILE = { A: [0 / 128, 16 / 128], B: [16 / 128, 32 / 128], narrow: [32 / 128, 38 / 128], chamfer: [32 / 128, 38 / 128] };
  for (let r = 0; r < rings.length - 1; r++) {
    const a = octagon(rings[r].hx, rings[r].hy, rings[r].lz), b = octagon(rings[r + 1].hx, rings[r + 1].hy, rings[r + 1].lz);
    const v0 = vOf(rings[r].lz), v1 = vOf(rings[r + 1].lz);
    const inside = [0, 0, (rings[r].lz + rings[r + 1].lz) / 2];
    for (let i = 0; i < 8; i++) {
      const j = (i + 1) % 8, [u0, u1] = TILE[EDGE_KIND[i]];
      quad([a[i], a[j], b[j], b[i]], inside, [[u0, v0], [u1, v0], [u1, v1], [u0, v1]]);
    }
  }
  // flat end faces of the body (hidden under the collar / tip cap)
  for (const [ring, off] of [[rings[rings.length - 1], 0.1], [rings[0], -0.1]]) {
    const o = octagon(ring.hx, ring.hy, ring.lz);
    for (let j = 0; j < 4; j++) {
      quad([[0, 0, ring.lz], o[2 * j], o[2 * j + 1], o[(2 * j + 2) % 8]], [0, 0, ring.lz + off],
        [[0.25, 0.1], [0.3, 0.1], [0.3, 0.15], [0.25, 0.15]]);
    }
  }

  // ---- metal fittings ----
  lines.push('g fittings');
  const m = cfg.mouthZ, e = cfg.endZ, mid = m - 0.45 * (m - e);
  const col = cfg.collar || { a: 0.02, b: -0.05, growX: 0.0075, growY: 0.010 };
  band(m + col.a, m + col.b, col.growX, col.growY, m, true);    // koiguchi: the collar at the mouth (black)
  band(m + col.b - 0.025, m + col.b - 0.045, 0.004, 0.006, m + col.b - 0.035, true); // thin second ring behind it (black)
  band(mid + 0.009, mid - 0.009, 0.005, 0.007);           // decorative band in the middle
  band(e + 0.075, e - 0.004, 0.006, 0.006, e + 0.04);     // kojiri: the cap at the tip

  // ---- glowing runes: thin overlay on the two flat sides, own material (Ka 1 = full bright) ----
  if (cfg.glow) {
    lines.push('g glow');
    lines.push('usemtl glow');
    const OFF = 0.002;
    for (let r = 0; r < rings.length - 1; r++) {
      const a = octagon(rings[r].hx, rings[r].hy, rings[r].lz), b = octagon(rings[r + 1].hx, rings[r + 1].hy, rings[r + 1].lz);
      const v0 = vOf(rings[r].lz), v1 = vOf(rings[r + 1].lz);
      const inside = [0, 0, (rings[r].lz + rings[r + 1].lz) / 2];
      for (const i of [2, 6]) {                       // the +x and -x flat sides
        const j = (i + 1) % 8, [u0, u1] = TILE[EDGE_KIND[i]];
        let p = [a[i], a[j], b[j], b[i]];
        const n = norm(cross(sub(p[1], p[0]), sub(p[2], p[0])));
        const c0 = [0, 1, 2].map(k => (p[0][k] + p[1][k] + p[2][k] + p[3][k]) / 4);
        const s = dot(n, sub(c0, inside)) < 0 ? -OFF : OFF;      // outwards
        p = p.map(pt => [pt[0] + n[0] * s, pt[1] + n[1] * s, pt[2] + n[2] * s]);
        quad(p, inside, [[u0, v0], [u1, v0], [u1, v1], [u0, v1]]);
      }
    }
  }

  const obj = [
    `# ${name}: tapered sheath, generated by tools/make_nf_sheath_obj.js (do not edit by hand)`,
    `# axial length ${L.toFixed(4)}, ${rings.length} body sections; centre of the model = (0.5, 0.5, 0.5); +z = mouth`,
    `mtllib ${NS}:models/item/${name}_sheath.mtl`,
    ...verts.map(v => `v ${v.map(x => x.toFixed(5)).join(' ')}`),
    ...uvs.map(t => `vt ${t[0].toFixed(5)} ${t[1].toFixed(5)}`),
    ...normals.map(n => `vn ${n.map(x => x.toFixed(5)).join(' ')}`),
    'usemtl sheath',
    ...lines, '',
  ].join('\n');
  fs.writeFileSync(path.join(OUT, `models/item/${name}_sheath.obj`), obj);
  fs.writeFileSync(path.join(OUT, `models/item/${name}_sheath.mtl`),
    `# materials of ${name}_sheath.obj\nnewmtl sheath\nKd 1.0 1.0 1.0\nmap_Kd ${NS}:item/${name}_sheath\n` +
    // Ka > 0 + "emissive_ambient" in the model json = drawn full bright (the glowing runes)
    (cfg.glow ? `\nnewmtl glow\nKd 1.0 1.0 1.0\nKa 1.0 1.0 1.0\nmap_Kd ${NS}:item/${name}_sheath_glow\n` : ''));
  fs.writeFileSync(path.join(OUT, `models/item/${name}_sheath.json`), JSON.stringify({
    loader: 'forge:obj',
    model: `${NS}:models/item/${name}_sheath.obj`,
    automatic_culling: false,
    flip_v: false,
    emissive_ambient: true,        // materials with Ka > 0 (the runes) are drawn full bright
    textures: cfg.glow
      ? { particle: `${NS}:item/${name}_sheath`, glow: `${NS}:item/${name}_sheath_glow` }
      : { particle: `${NS}:item/${name}_sheath` },
  }, null, 2) + '\n');
  fs.writeFileSync(path.join(OUT, `textures/item/${name}_sheath.png`), texture(cfg.style));
  if (cfg.glow) fs.writeFileSync(path.join(OUT, `textures/item/${name}_sheath_glow.png`), texture(cfg.style, true));
  console.log(name, 'axial length', L.toFixed(3), 'faces', lines.filter(l => l.startsWith('f ')).length,
    'widths', rings.map(r => (2 * r.hy).toFixed(3)).join(' '));
}

// ---------------------------------------------------------------- texture
function rng(seed) { let a = seed >>> 0; return () => { a = (a + 0x6D2B79F5) >>> 0; let t = a; t = Math.imul(t ^ (t >>> 15), t | 1); t ^= t + Math.imul(t ^ (t >>> 7), t | 61); return ((t ^ (t >>> 14)) >>> 0) / 4294967296; }; }
const GLYPHS = [
  ['#....', '#.#..', '#..#.', '#...#', '#..#.', '#.#..', '#....'],
  ['..#..', '.##..', '#.#..', '..###', '..#..', '..#..', '..#..'],
  ['#...#', '.#.#.', '..#..', '.#.#.', '#...#', '..#..', '..#..'],
  ['###..', '#..#.', '#...#', '#..#.', '###..', '#....', '#....'],
  ['..#..', '..#..', '###..', '..#.#', '..###', '..#..', '..#..'],
  ['#.#.#', '.###.', '..#..', '..#..', '..#..', '.###.', '#.#.#'],
];

function texture(style, glowOnly) {
  const P = PALETTE[style], W = 128, H = 128, rnd = rng(style === 'red' ? 7 : 13);
  const px = new Float32Array(W * H * 3);
  const glowMask = new Uint8Array(W * H);
  const set = (x, y, c, a = 1) => {
    if (x < 0 || y < 0 || x >= W || y >= H) return;
    const o = (y * W + x) * 3;
    for (let k = 0; k < 3; k++) px[o + k] = px[o + k] * (1 - a) + c[k] * a;
  };
  const scale = (c, f) => c.map(v => Math.max(0, Math.min(255, v * f)));
  // lacquer tiles (cylindrical shading + soft highlight streak + grain)
  for (let y = 0; y < H; y++) {
    for (let x = 0; x < 64; x++) {
      const tile = x < 16 ? 0 : x < 32 ? 1 : x < 39 ? 2 : 3;
      const lx = tile === 0 ? x : tile === 1 ? x - 16 : 0;
      let col;
      if (tile <= 1) {
        const t = (lx + 0.5) / 16;
        const cyl = 0.80 + 0.30 * Math.sin(Math.PI * t);
        const streak = Math.exp(-Math.pow((t - (tile === 0 ? 0.30 : 0.70)) / 0.08, 2)) * 0.30;
        col = scale(P.lacquer, cyl + (rnd() - 0.5) * 0.06).map((v, k) => v * (1 - streak) + P.hl[k] * streak);
      } else if (tile === 2) col = scale(P.lacquer, 0.55 + (rnd() - 0.5) * 0.05);
      else col = scale(P.lacquer, 0.9);
      set(x, y, col);
    }
  }
  for (const ox of [0, 16]) {             // both flat sides get the same decoration
    for (let y = 4; y < 124; y++) {
      set(ox + 0, y, P.edge); set(ox + 15, y, P.edge);
      if (style === 'red') { set(ox + 1, y, P.accentDark); set(ox + 14, y, P.accentDark); set(ox + 2, y, P.accent, 0.9); set(ox + 13, y, P.accent, 0.9); }
      else { set(ox + 2, y, P.accent, 0.35); set(ox + 13, y, P.accent, 0.35); }
    }
    if (style === 'red') {
      // crest near the mouth: a gold ring with a dot and four petals
      const cx = ox + 7.5, cy = 30;
      for (let y = 0; y < H; y++) for (let x = ox; x < ox + 16; x++) {
        const d = Math.hypot(x + 0.5 - cx - 0.5, y + 0.5 - cy);
        if (d > 4.6 && d < 6.0) set(x, y, P.accent, 0.95);
        if (d < 1.8) set(x, y, P.accentLight, 0.95);
      }
      for (const [dx, dy] of [[0, -3], [0, 3], [-3, 0], [3, 0]]) set(Math.round(cx + dx), cy + dy, P.accentLight, 0.9);
      // thin gold chevrons along the length
      for (let k = 0; k < 5; k++) { const yy = 52 + k * 13; for (let i = 0; i < 6; i++) { set(ox + 5 + i, yy + Math.abs(i - 2.5) | 0, P.accent, 0.8); } }
    } else {
      // silver rune inlay down the middle
      for (let y = 8; y < 120; y++) set(ox + 7, y, P.accent, 0.35);
      for (let i = 0; i < 6; i++) {
        const g = GLYPHS[(i + (ox ? 3 : 0)) % GLYPHS.length], gy = 18 + i * 17;
        for (let r = 0; r < 7; r++) for (let c = 0; c < 5; c++) if (g[r][c] === '#') { set(ox + 5 + c, gy + r - 3, P.glyph, 1.0); glowMask[(gy + r - 3) * W + ox + 5 + c] = 1; }
      }
    }
  }
  // black collar patch (the mouth): dark metal with a faint brushed sheen
  for (let y = 0; y < H; y++) for (let x = 41; x < 63; x++) {
    const f = 1.0 + 0.35 * Math.sin(y * 0.8 + (x % 3)) + (rnd() - 0.5) * 0.15;
    set(x, y, scale([22, 22, 27], f));
  }
  // metal area: brushed texture + engraved diamonds (or fine rings for the silver)
  for (let y = 0; y < H; y++) for (let x = 64; x < W; x++) {
    let f = 0.92 + 0.10 * Math.sin(y * 0.9 + (x % 5)) + (rnd() - 0.5) * 0.05;
    let col = scale(P.metal, f);
    const lx = (x - 64) % 8, ly = y % 8;
    if (style === 'red') { if (Math.abs(lx - 3.5) + Math.abs(ly - 3.5) > 3.0 && Math.abs(lx - 3.5) + Math.abs(ly - 3.5) < 4.2) col = scale(P.accentDark, 1.0); }
    else if (y % 6 === 0) col = scale(P.accentDark, 1.0);
    if (x === 64 || x === 127 || y === 0 || y === 127) col = scale(P.accentDark, 0.8);
    set(x, y, col);
  }
  // PNG
  const raw = Buffer.alloc(H * (1 + W * 4));
  for (let y = 0; y < H; y++) {
    raw[y * (1 + W * 4)] = 0;
    for (let x = 0; x < W; x++) {
      const o = y * (1 + W * 4) + 1 + x * 4, s = (y * W + x) * 3;
      raw[o] = Math.round(px[s]); raw[o + 1] = Math.round(px[s + 1]); raw[o + 2] = Math.round(px[s + 2]);
      raw[o + 3] = glowOnly ? (glowMask[y * W + x] ? 255 : 0) : 255;
    }
  }
  const ihdr = Buffer.alloc(13); ihdr.writeUInt32BE(W, 0); ihdr.writeUInt32BE(H, 4); ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]);
}
function crc32(buf) {
  let c, crc = 0xFFFFFFFF;
  for (let n = 0; n < buf.length; n++) {
    c = (crc ^ buf[n]) & 0xFF;
    for (let k = 0; k < 8; k++) c = c & 1 ? (c >>> 1) ^ 0xEDB88320 : c >>> 1;
    crc = (crc >>> 8) ^ c;
  }
  return (crc ^ 0xFFFFFFFF) >>> 0;
}
function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const t = Buffer.from(type, 'ascii');
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(Buffer.concat([t, data])));
  return Buffer.concat([len, t, data, crc]);
}

fs.mkdirSync(path.join(OUT, 'models/item'), { recursive: true });
fs.mkdirSync(path.join(OUT, 'textures/item'), { recursive: true });
for (const [name, cfg] of Object.entries(WEAPONS)) build(name, cfg);
