// Headless GL bench: runs the app's real shaders in a GLSL ES 3.00 context and returns raw pixels.
// Usage: node bench.js <dataDir> <jobs.json> <outDir>
const fs = require('fs');
const path = require('path');
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright');

const [dataDir, jobsFile, outDir] = process.argv.slice(2);
const shaderDir = path.resolve(__dirname, '../../app/src/main/assets/shaders');
const shaders = Object.fromEntries(fs.readdirSync(shaderDir).map((f) => [f, fs.readFileSync(path.join(shaderDir, f), 'utf8')]));

async function pageMain(payload) {
  const LUT_SIZE = 41;
  const canvas = document.createElement('canvas');
  const gl = canvas.getContext('webgl2', { preserveDrawingBuffer: false });
  if (!gl) throw new Error('webgl2 unavailable');
  const b64 = (s) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));
  const compile = (type, src) => {
    const s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s);
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s) + '\n' + src);
    return s;
  };
  const S = payload.shaders;
  const header = (defs) => `#version 300 es\nprecision highp float;\nprecision highp int;\n#define SOURCE_SAMPLER highp sampler2D\n${defs || ''}\n`;
  const LOG = '#define LOG_GLOW 1';
  const program = (fragName, defs) => {
    const p = gl.createProgram();
    gl.attachShader(p, compile(gl.VERTEX_SHADER, header() + S['fullscreen.vert']));
    gl.attachShader(p, compile(gl.FRAGMENT_SHADER, header(defs) + S['common.glsl'] + S[fragName]));
    gl.linkProgram(p);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(p));
    const cache = {};
    return { p, u: (n) => (n in cache ? cache[n] : (cache[n] = gl.getUniformLocation(p, n))) };
  };
  const P = {
    pre: program('glow_prefilter.frag'), down: program('glow_downsample.frag'), blur: program('glow_blur.frag'),
    preLog: program('glow_prefilter.frag', LOG), downLog: program('glow_downsample.frag', LOG), blurLog: program('glow_blur.frag', LOG),
    star: program('star.frag'),
    film: program('film.frag'), vf: program('film.frag', '#define VIEWFINDER 1'), yuv: program('yuv_to_rgb.frag'),
  };
  const vbo = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, vbo);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
  gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);

  const tex2d = (w, h, data, filter, wrap, internal = gl.RGBA8, fmt = gl.RGBA) => {
    const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, t);
    gl.pixelStorei(gl.UNPACK_ALIGNMENT, 1);
    gl.texImage2D(gl.TEXTURE_2D, 0, internal, w, h, 0, fmt, gl.UNSIGNED_BYTE, data || null);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, filter); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, wrap); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, wrap);
    return t;
  };
  const target = (w, h) => {
    const t = tex2d(w, h, null, gl.LINEAR, gl.CLAMP_TO_EDGE); const f = gl.createFramebuffer();
    gl.bindFramebuffer(gl.FRAMEBUFFER, f); gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, t, 0);
    return { t, f, w, h };
  };
  const bindTex = (unit, type, t) => { gl.activeTexture(gl.TEXTURE0 + unit); gl.bindTexture(type, t); };
  const IDENT = new Float32Array([1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]);
  const FLIPV = new Float32Array([1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 0, 1, 0, 1]);
  const begin = (prog, tgt, matrix = IDENT, flip = 1) => {
    gl.useProgram(prog.p); gl.bindFramebuffer(gl.FRAMEBUFFER, tgt.f); gl.viewport(0, 0, tgt.w, tgt.h);
    gl.uniformMatrix4fv(prog.u('uSourceMatrix'), false, matrix); gl.uniform1f(prog.u('uFlipY'), flip);
  };
  const draw = () => gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);

  const grain = tex2d(512, 512, b64(payload.grain), gl.LINEAR_MIPMAP_LINEAR, gl.REPEAT);
  gl.bindTexture(gl.TEXTURE_2D, grain); gl.generateMipmap(gl.TEXTURE_2D);

  const luts = {};
  const lutTexture = (id) => {
    if (luts[id]) return luts[id];
    const t = gl.createTexture(); gl.bindTexture(gl.TEXTURE_3D, t);
    const bytes = b64(payload.luts[id]);
    gl.texImage3D(gl.TEXTURE_3D, 0, gl.RGB10_A2, LUT_SIZE, LUT_SIZE, LUT_SIZE, 0, gl.RGBA, gl.UNSIGNED_INT_2_10_10_10_REV, new Uint32Array(bytes.buffer));
    for (const [k, v] of [[gl.TEXTURE_MIN_FILTER, gl.LINEAR], [gl.TEXTURE_MAG_FILTER, gl.LINEAR], [gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE], [gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE], [gl.TEXTURE_WRAP_R, gl.CLAMP_TO_EDGE]]) gl.texParameteri(gl.TEXTURE_3D, k, v);
    return (luts[id] = t);
  };

  const dims = (s, aspect) => (aspect >= 1 ? [Math.round(s * aspect), s] : [s, Math.round(s / aspect)]);

  const lens = (prog, u, aspect, up) => {
    const a = (up + u.gradTilt) * Math.PI / 180;
    gl.uniform3fv(prog.u('uGradTint'), u.gradTint); gl.uniform1f(prog.u('uGradStrength'), u.gradStrength);
    gl.uniform2f(prog.u('uGradDir'), -Math.sin(a), Math.cos(a)); gl.uniform1f(prog.u('uGradPos'), u.gradPos);
    gl.uniform1f(prog.u('uGradSoft'), u.gradSoft); gl.uniform2f(prog.u('uFrameAspect'), aspect, 1); gl.uniform1f(prog.u('uPolariser'), u.polariser);
  };

  function glow(src, sw, sh, u, outAspect) {
    const short = Math.min(sw, sh); const NEAR = 270; const FAR = 90; const DIFF = 135;
    const levels = []; let s = short;
    while (Math.floor(s / 2) >= NEAR * 2) { s = Math.floor(s / 2); levels.push(s); }
    levels.push(NEAR);
    const rts = levels.map((l) => { const [w, h] = dims(l, outAspect); return target(w, h); });
    const down = (from, to, log) => {
      const p = log ? P.downLog : P.down;
      begin(p, to); bindTex(0, gl.TEXTURE_2D, from.t); gl.uniform1i(p.u('uInput'), 0);
      const r = from.h / to.h; gl.uniform2f(p.u('uTexel'), r * 0.5 / from.w, r * 0.5 / from.h); draw();
    };
    const blur2 = (src2, sigma, log) => {
      const p = log ? P.blurLog : P.blur;
      const a = target(src2.w, src2.h); const b = target(src2.w, src2.h);
      begin(p, a); bindTex(0, gl.TEXTURE_2D, src2.t); gl.uniform1i(p.u('uInput'), 0); gl.uniform1f(p.u('uSigma'), sigma); gl.uniform2f(p.u('uDirection'), 1 / src2.w, 0); draw();
      begin(p, b); bindTex(0, gl.TEXTURE_2D, a.t); gl.uniform1i(p.u('uInput'), 0); gl.uniform1f(p.u('uSigma'), sigma); gl.uniform2f(p.u('uDirection'), 0, 1 / src2.h); draw();
      return b;
    };
    const prefilter = (log) => {
      const p = log ? P.preLog : P.pre;
      begin(p, rts[0], FLIPV); bindTex(0, gl.TEXTURE_2D, src); gl.uniform1i(p.u('uSource'), 0);
      gl.uniform3fv(p.u('uInputGain'), u.gain); lens(p, u, outAspect, 0);
      const r0 = short / levels[0]; gl.uniform2f(p.u('uSrcTexel'), r0 * 0.5 / sw, r0 * 0.5 / sh); draw();
      for (let i = 1; i < rts.length; i++) down(rts[i - 1], rts[i], log);
    };
    let diffuse = null;
    if (u.diffusion > 0) {
      prefilter(true);
      const [dw, dh] = dims(DIFF, outAspect); const ds = target(dw, dh); down(rts[rts.length - 1], ds, true);
      diffuse = blur2(ds, u.diffusionSigma, true);
    }
    prefilter(false);
    const nearSrc = rts[rts.length - 1];
    const near = blur2(nearSrc, u.nearSigma);
    const [mw, mh] = dims(135, outAspect); const mid = target(mw, mh); down(nearSrc, mid);
    const [fw, fh] = dims(FAR, outAspect); const farSrc = target(fw, fh); down(mid, farSrc);
    const far = blur2(farSrc, u.farSigma);
    let star = null;
    if (u.starPoints > 0) {
      star = target(nearSrc.w, nearSrc.h); const p = P.star; begin(p, star);
      bindTex(0, gl.TEXTURE_2D, nearSrc.t); gl.uniform1i(p.u('uInput'), 0); gl.uniform2f(p.u('uTexel'), 1 / nearSrc.w, 1 / nearSrc.h);
      const n = Math.min(4, Math.max(1, u.starPoints / 2)); const ax = new Array(8).fill(0);
      for (let k = 0; k < n; k++) { const a = k * 2 * Math.PI / u.starPoints; ax[k * 2] = Math.cos(a); ax[k * 2 + 1] = Math.sin(a); }
      gl.uniform4f(p.u('uAxesA'), ax[0], ax[1], ax[2], ax[3]); gl.uniform4f(p.u('uAxesB'), ax[4], ax[5], ax[6], ax[7]);
      gl.uniform1f(p.u('uAxisCount'), n); gl.uniform1f(p.u('uDecay'), Math.max(u.starDecay, 1)); draw();
    }
    return { near, far, diffuse, star };
  }

  function renderFilm(job, srcTex, sw, sh) {
    const u = job.uniforms; const out = target(job.outW, job.outH);
    const { near, far, diffuse, star } = glow(srcTex, sw, sh, u, job.outW / job.outH);
    const p = P.film; begin(p, out, FLIPV, -1);
    bindTex(0, gl.TEXTURE_2D, srcTex); bindTex(1, gl.TEXTURE_3D, lutTexture(job.lut)); bindTex(2, gl.TEXTURE_2D, grain); bindTex(3, gl.TEXTURE_2D, near.t); bindTex(4, gl.TEXTURE_2D, far.t);
    bindTex(5, gl.TEXTURE_2D, (diffuse || near).t); bindTex(6, gl.TEXTURE_2D, (star || near).t);
    [['uSource', 0], ['uLut', 1], ['uGrain', 2], ['uGlowNear', 3], ['uGlowFar', 4], ['uDiffuse', 5], ['uStar', 6]].forEach(([n, i]) => gl.uniform1i(p.u(n), i));
    lens(p, u, job.outW / job.outH, 0);
    gl.uniform1f(p.u('uDiffusion'), u.diffusion); gl.uniform1f(p.u('uDiffusionSpot'), u.diffusionSpot); gl.uniform1f(p.u('uPastel'), u.pastel);
    gl.uniform1f(p.u('uStarStrength'), u.starPoints > 0 ? u.starStrength : 0);
    gl.uniform3fv(p.u('uInputGain'), u.gain); gl.uniform1f(p.u('uLutSize'), LUT_SIZE);
    gl.uniform1f(p.u('uGrainAmount'), job.noGrain ? 0 : u.grainAmount); gl.uniform1f(p.u('uGrainChroma'), u.grainChroma); gl.uniform2fv(p.u('uGrainDensity'), u.grainDensity);
    gl.uniform2fv(p.u('uGrainUvScale'), u.grainUvScale); gl.uniform2f(p.u('uGrainOffset'), job.seedX || 0, job.seedY || 0); gl.uniform1f(p.u('uGrainLod'), u.grainLod);
    gl.uniform1f(p.u('uHalation'), u.halation); gl.uniform3fv(p.u('uHalationTint'), u.halationTint); gl.uniform1f(p.u('uBloom'), u.bloom);
    gl.uniform1f(p.u('uVignette'), u.vignette); gl.uniform1f(p.u('uClarity'), u.clarity); gl.uniform1f(p.u('uAcutance'), u.acutance);
    gl.uniform2f(p.u('uSrcStepX'), u.acutanceRadius / job.outW, 0); gl.uniform2f(p.u('uSrcStepY'), 0, u.acutanceRadius / job.outH);
    draw();
    const pixels = new Uint8Array(job.outW * job.outH * 4);
    gl.bindFramebuffer(gl.FRAMEBUFFER, out.f); gl.readPixels(0, 0, job.outW, job.outH, gl.RGBA, gl.UNSIGNED_BYTE, pixels);
    return pixels;
  }

  const results = [];
  const sources = {};
  for (const job of payload.jobs) {
    if (!sources[job.source]) { const s = payload.sources[job.source]; sources[job.source] = { t: tex2d(s.w, s.h, b64(s.data), gl.LINEAR, gl.CLAMP_TO_EDGE), w: s.w, h: s.h }; }
    const src = sources[job.source];
    const px = renderFilm(job, src.t, src.w, src.h);
    let bin = ''; const chunk = 0x8000;
    for (let i = 0; i < px.length; i += chunk) bin += String.fromCharCode.apply(null, px.subarray(i, i + chunk));
    results.push({ name: job.name, w: job.outW, h: job.outH, data: btoa(bin) });
    const err = gl.getError(); if (err) throw new Error('GL error ' + err + ' in ' + job.name);
  }
  return { renderer: gl.getParameter(gl.VERSION), results };
}

(async () => {
  const spec = JSON.parse(fs.readFileSync(jobsFile, 'utf8'));
  const stocks = JSON.parse(fs.readFileSync(path.join(dataDir, 'stocks.json'), 'utf8'));
  const filterFile = path.join(dataDir, 'filters.json');
  if (fs.existsSync(filterFile)) Object.assign(stocks, JSON.parse(fs.readFileSync(filterFile, 'utf8')));
  const lutOf = (key) => stocks[key].lut || key;
  const luts = {};
  for (const id of new Set(spec.jobs.map((j) => lutOf(j.stock)))) luts[id] = fs.readFileSync(path.join(dataDir, `${id}.lut`)).toString('base64');
  const sources = {};
  for (const [k, v] of Object.entries(spec.sources)) sources[k] = { w: v.w, h: v.h, data: fs.readFileSync(v.file).toString('base64') };
  const jobs = spec.jobs.map((j) => ({ ...j, lut: lutOf(j.stock), uniforms: stocks[j.stock].sizes[j.size] }));
  const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
  const page = await browser.newPage();
  page.on('pageerror', (e) => console.error('pageerror', e.message));
  const res = await page.evaluate(pageMain, { shaders, grain: fs.readFileSync(path.join(dataDir, 'grain.bin')).toString('base64'), luts, sources, jobs });
  console.log(res.renderer);
  fs.mkdirSync(outDir, { recursive: true });
  for (const r of res.results) { fs.writeFileSync(path.join(outDir, `${r.name}.raw`), Buffer.from(r.data, 'base64')); fs.writeFileSync(path.join(outDir, `${r.name}.json`), JSON.stringify({ w: r.w, h: r.h })); }
  await browser.close();
})().catch((e) => { console.error(e.message.slice(0, 3000)); process.exit(1); });
