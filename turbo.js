/* ============================================================================
   Onelly Edit · 🚀 Turbo Export
   Its own module: it does not use Fast Export, Standard Export or the old "native GPU B4" flow.
   It only reads the project (clips, overlays) and talks to the phone's GPU engine (FastRender.turbo).

   Idea: every overlay (text, sticker, photo) becomes ONE picture (a few for typing / word effects) plus a small
   motion track (position, size, rotation, transparency over time). The phone's GPU moves it. So a 3 hour video with
   hundreds of moving overlays is as cheap to render as a video without overlays.

   To add something new later (video overlay, filter, transition, new format) add one more kind of "layer" here
   and in TurboRender (Java); nothing else has to change.
   ============================================================================ */
(function () {
  'use strict';
  var apk = function () { return !!(window.Capacitor && Capacitor.isNativePlatform && Capacitor.isNativePlatform()); };
  var getFR = function () { return Capacitor.Plugins && Capacitor.Plugins.FastRender; };
  var sleep = function (ms) { return new Promise(function (r) { setTimeout(r, ms); }); };
  var RND = Math.round;

  /* ---- can this project run in Turbo right now? '' = yes, otherwise a short reason ---- */
  function why() {
    try {
      if (!apk()) return 'not in the APK';
      if (!getFR()) return 'plugin missing';
      if (!clips.length) return 'no video';
      if (bgm) return 'background';
      if (clips.length > 1) return 'several clips';
      if (aud.length) return 'music / extra audio';
      if (window.FADE && (FADE.i || FADE.o)) return 'fade (needs sound mix)';
      var none = JSON.stringify(PRESETS.none),
        base = { url: 1, dur: 1, in: 1, out: 1, speed: 1, rot: 1, flip: 1, vol: 1, f: 1, name: 1, id: 1 },
        emp = function (o) { return !o || (typeof o === 'object' && !Object.keys(o).length); },
        c = clips[0];
      if (!(c.speed === 1 && !c.rot && !c.flip && c.vol === 1 && JSON.stringify(c.f) === none &&
        !Object.keys(c).some(function (k) { return !base[k] && c[k] && !emp(c[k]); })))
        return 'clip edit (speed / rotate / flip / volume / filter / effects)';
      for (var i = 0; i < ovs.length; i++) {
        var o = ovs[i];
        if (o.kind === 'vid') return 'video overlay';
        if (o.bm) return 'blend mode';
        if (o.ck && o.ck.on) return 'chroma key';
        var el = OE[o.id];
        if (!el) return 'overlay missing';
        if (o.kind !== 'text' && o.kind !== 'emoji' && !el.querySelector('img')) return 'overlay kind ' + o.kind;
        var ts = o.ts || {};
        if (ts.ai === 'type' && ts.wd) return 'typing + word effect together';
      }
      return '';
    } catch (e) { return 'check error: ' + (e && e.message || e); }
  }

  /* ---- the video goes to the app's storage once (kept for the next exports of the same video) ---- */
  async function copySource(FR, url, onp) {
    var b = await (await fetch(url)).blob(), key = url + '|' + b.size;
    window.__tbSrc = window.__tbSrc || {};
    var old = window.__tbSrc[key];
    if (old) { try { var s0 = await FR.stat({ path: old }); if (s0 && s0.exists && s0.size === b.size) return old; } catch (_) { } }
    try { var prev = localStorage.getItem('onelly_tb_src'); if (prev) await FR.remove({ path: prev }).catch(function () { }); } catch (_) { }
    var nm = 'turbo-src-' + Date.now() + '.mp4', u = await FR.upInfo();
    if (!u || !u.port) throw new Error('no local pipe');
    await new Promise(function (res, rej) {
      var x = new XMLHttpRequest();
      x.open('POST', 'http://127.0.0.1:' + u.port + '/put?name=' + encodeURIComponent(nm) + '&t=' + u.token);
      x.upload.onprogress = function (e) { if (e.lengthComputable && onp) onp(e.loaded / e.total); };
      x.onload = function () { x.status === 200 ? res() : rej(new Error('up ' + x.status)); };
      x.onerror = x.onabort = x.ontimeout = function () { rej(new Error('up-net')); };
      x.send(b);
    });
    var st = await FR.stat({ path: nm });
    if (!st || !st.exists || st.size !== b.size) throw new Error('copy size mismatch');
    window.__tbSrc[key] = nm;
    try { localStorage.setItem('onelly_tb_src', nm); } catch (_) { }
    return nm;
  }

  /* ---- where the overlay is and how it looks at time tt: [a,b,c,d,e,f,opacity] (canvas matrix of the picture) ---- */
  function sampleAt(o, tt, W, H, x, wref, img) {
    var s = ovState(o, tt), w = W * s.sc, ts = o.ts || {}, op = 1;
    x.setTransform(1, 0, 0, 1, 0, 0); x.globalAlpha = 1;
    x.translate(W * s.x / 100, H * s.y / 100); x.rotate(s.rot * Math.PI / 180);
    if (!img) { /* text / emoji: same steps as txDraw */
      var an = ((ts.ai && ts.ai !== 'none') || (ts.ao && ts.ao !== 'none')) ? txAnim(o, tt) : { op: 1, sc: 1, dy: 0, n: 1 };
      if (ts.mv) {
        x.font = (o.kind === 'text' ? '700 ' : '') + (ts.it ? 'italic ' : '') + w + 'px ' + (FONTS[ts.font] || 'sans-serif');
        x.textBaseline = 'middle'; x.textAlign = 'left';
        var fw = x.measureText(o.val).width, M0 = x.getTransform(),
          mv = txMove(o, tt - o.from, Math.max(.1, o.to - o.from), W, H, fw + .8 * w, 1.4 * w, M0.e, M0.f);
        if (mv) { var ag = Math.atan2(M0.b, M0.a), cs = Math.cos(ag), sn = Math.sin(ag); x.translate(mv[0] * cs + mv[1] * sn, -mv[0] * sn + mv[1] * cs); }
      }
      op = an.op; x.translate(0, an.dy * w); x.scale(an.sc, an.sc);
    } else { /* photo / sticker: same steps as the normal export */
      txMoveCv(x, o, tt, w, w * img.ih / img.iw);
      var a = o.ts ? txAnim(o, tt) : null;
      if (a) { op = a.op; x.translate(0, a.dy * w); x.scale(a.sc, a.sc); }
    }
    var M = x.getTransform().scale(w / wref);
    return [M.a, M.b, M.c, M.d, M.e, M.f, op];
  }

  /* only the moments where something changes are measured (a still text = 2 samples; a scrolling text = 2 per round) */
  function sampleTimes(o, from, to) {
    var S = {}, add = function (t, wrap) { t = +Math.max(from, Math.min(to, t)).toFixed(4); if (!(t in S) || wrap) S[t] = !!wrap || !!S[t]; },
      range = function (a, b, step) { for (var t = a; t < b; t += step) add(t); add(b); },
      d = Math.max(.1, o.to - o.from), dn = Math.min(.6, d / 3), ts = o.ts || {};
    add(from); add(to);
    if (ts.ai && ts.ai !== 'none') range(from, Math.min(to, o.from + dn + .05), 1 / 30);
    if (ts.ao && ts.ao !== 'none') range(Math.max(from, o.to - dn - .05), to, 1 / 30);
    if (o.kf && o.kf.length) range(Math.max(from, o.from + o.kf[0].t), Math.min(to, o.from + o.kf[o.kf.length - 1].t), 1 / 20);
    if (ts.mv) {
      var P = d / (ts.mvs || 1), k = 0;
      for (; k < 100000; k++) {
        var a = o.from + k * P;
        if (a >= to) break;
        if (a + P > from) { add(a + 1e-3, k > 0); add(a + P - 1e-3); }
      }
    }
    return Object.keys(S).map(function (k2) { return { t: +k2, wrap: S[k2] }; }).sort(function (p, q) { return p.t - q.t; });
  }

  var TOL = [5e-4, 5e-4, 5e-4, 5e-4, .3, .3, .003];
  function compress(S, W, H) {
    var n = S.length, jump = [false], i, j, k;
    for (i = 1; i < n; i++) {
      var p = S[i - 1].v, q = S[i].v;
      jump.push(S[i].wrap || Math.hypot(q[4] - p[4], q[5] - p[5]) > .35 * Math.max(W, H));
    }
    var out = [0], brk = [0], a0 = 0;
    for (i = 1; i < n; i++) {
      var ok = !jump[i];
      for (j = a0 + 1; ok && j < i; j++) {
        var u = (S[j].t - S[a0].t) / Math.max(1e-6, S[i].t - S[a0].t);
        for (k = 0; k < 7; k++) { if (Math.abs(S[a0].v[k] + (S[i].v[k] - S[a0].v[k]) * u - S[j].v[k]) > TOL[k]) { ok = false; break; } }
      }
      if (!ok) {
        if (i - 1 > a0) { out.push(i - 1); brk.push(0); }
        a0 = i - 1;
        if (jump[i]) { out.push(i); brk.push(1); a0 = i; }
      }
    }
    if (out[out.length - 1] !== n - 1) { out.push(n - 1); brk.push(0); }
    var ts = [], m = [];
    out.forEach(function (ix) { ts.push(RND(S[ix].t * 1000)); for (var z = 0; z < 7; z++) m.push(+S[ix].v[z].toFixed(4)); });
    return { ts: ts, m: m, brk: brk };
  }

  function b64(canvas) { var u = canvas.toDataURL('image/png'); return u.slice(u.indexOf(',') + 1); }

  /* one overlay -> {v:[pictures with time ranges], ts, m, brk} */
  async function overlayLayer(FR, o, idx, W, H, T, x) {
    var from = Math.max(0, o.from), to = Math.min(T, o.to);
    if (to - from < .02) return null;
    var ts = o.ts || {}, isTxt = (o.kind === 'text' || o.kind === 'emoji'), variants = [], wref, img = null, i, nm;
    if (isTxt) {
      var scs = [o.sc].concat((o.kf || []).map(function (k) { return k.sc; })).filter(function (v) { return v > 0; });
      var wr = Math.min(1300, Math.max(12, RND(W * Math.max.apply(null, scs))));
      var fontOf = function (w) { return (o.kind === 'text' ? '700 ' : '') + (ts.it ? 'italic ' : '') + w + 'px ' + (FONTS[ts.font] || 'sans-serif'); };
      x.font = fontOf(wr);
      var fw = x.measureText(o.val).width, cw = Math.ceil(fw + 2.4 * wr), chh = Math.ceil(3 * wr);
      if (cw > 4000) { wr = Math.max(10, Math.floor(wr * 4000 / cw)); x.font = fontOf(wr); fw = x.measureText(o.val).width; cw = Math.ceil(fw + 2.4 * wr); chh = Math.ceil(3 * wr); }
      wref = wr;
      var D = Math.max(.1, o.to - o.from), plan = [];
      if (ts.ai === 'type') {
        var len = String(o.val).length, Tn = Math.min(2.5, D * .6);
        if (len > 150) throw new Error('typing text is too long for Turbo');
        for (i = 1; i <= len; i++) plan.push({ t0: o.from + (i - 1) * Tn / len, t1: i === len ? o.to : o.from + i * Tn / len, time: o.from + (i - .5) * Tn / len, ai: 'type' });
      } else if (ts.wd) {
        var nw = String(o.val).split(' ').length, DD = Math.max(.1, D * .92);
        for (i = 0; i < nw; i++) plan.push({ t0: o.from + i / nw * DD, t1: i === nw - 1 ? o.to : o.from + (i + 1) / nw * DD, time: o.from + (i + .5) / nw * DD, ai: 'none' });
      } else plan.push({ t0: o.from, t1: o.to, time: (o.from + o.to) / 2, ai: 'none' });
      for (i = 0; i < plan.length; i++) {
        var pl = plan[i], a = Math.max(from, pl.t0), b = Math.min(to, pl.t1);
        if (b - a < .005) continue;
        var c2 = document.createElement('canvas'); c2.width = cw; c2.height = chh;
        var g = c2.getContext('2d', { willReadFrequently: true });
        g.translate(cw / 2, chh / 2);
        txDraw(g, Object.assign({}, o, { ts: Object.assign({}, ts, { ai: pl.ai, ao: 'none', mv: null }) }), wr, pl.time);
        var d = g.getImageData(0, 0, cw, chh).data, x0 = cw, y0 = chh, x1 = -1, y1 = -1, xx, yy;
        for (yy = 0; yy < chh; yy++) { var r = yy * cw * 4; for (xx = 0; xx < cw; xx++) { if (d[r + xx * 4 + 3]) { if (xx < x0) x0 = xx; if (xx > x1) x1 = xx; if (yy < y0) y0 = yy; y1 = yy; } } }
        if (x1 < 0) continue;
        var oc = document.createElement('canvas'); oc.width = x1 - x0 + 1; oc.height = y1 - y0 + 1;
        oc.getContext('2d').drawImage(c2, x0, y0, oc.width, oc.height, 0, 0, oc.width, oc.height);
        nm = 'tb-' + idx + '-' + i + '.png';
        await FR.write({ path: nm, data: b64(oc) });
        variants.push({ t0: RND(a * 1000), t1: RND(b * 1000), path: nm, ox: cw / 2 - x0, oy: chh / 2 - y0 });
      }
    } else {
      var m = OE[o.id].querySelector('img'), iw = m.naturalWidth || m.width || 1, ih = m.naturalHeight || m.height || 1, k2 = Math.min(1, 2048 / Math.max(iw, ih)),
        cw2 = Math.max(1, RND(iw * k2)), ch2 = Math.max(1, RND(ih * k2)), c3 = document.createElement('canvas');
      c3.width = cw2; c3.height = ch2; c3.getContext('2d').drawImage(m, 0, 0, cw2, ch2);
      wref = cw2; img = { iw: iw, ih: ih };
      nm = 'tb-' + idx + '-0.png';
      await FR.write({ path: nm, data: b64(c3) });
      variants.push({ t0: RND(from * 1000), t1: RND(to * 1000), path: nm, ox: cw2 / 2, oy: ch2 / 2 });
    }
    if (!variants.length) return null;
    var S = sampleTimes(o, from, to).map(function (p) { return { t: p.t, wrap: p.wrap, v: sampleAt(o, p.t, W, H, x, wref, img) }; });
    var tr = compress(S, W, H);
    return { v: variants, ts: tr.ts, m: tr.m, brk: tr.brk };
  }

  async function run(FR, cfg) {
    var W = cfg.W, H = cfg.H, c = clips[0], T = len(c), info = W + '×' + H + ' · 🚀 Turbo Export';
    var st = function (s) { var e = $('#xst'); if (e) e.textContent = s; };
    var wait = async function () { while (XO.paused && !XO.cancel) await sleep(200); if (XO.cancel) throw new Error('cancelled'); };
    XO.mode = 'bal'; XO.gif = []; XO.cancel = false; xoShow(info);
    XO.rec = {
      state: 'recording',
      pause: function () { this.state = 'paused'; FR.setPaused({ paused: true }).catch(function () { }); },
      resume: function () { this.state = 'recording'; FR.setPaused({ paused: false }).catch(function () { }); },
      stop: function () { XO.cancel = true; try { XO.paused = false; } catch (_) { } FR.cancel().catch(function () { }); }
    };
    XO.off = true; try { gameToggle(false); } catch (_) { }
    var t0 = performance.now(), sub = null;
    try {
      st('📥 …'); xoSet(0);
      var src = await copySource(FR, c.url, function (f) { xoSet(.15 * f); st('📥 ' + RND(f * 100) + '%'); });
      var tCopy = performance.now();
      await wait();
      /* overlays -> pictures + motion tracks */
      st('🖼 …'); xoSet(.15);
      var cv = document.createElement('canvas'); cv.width = W; cv.height = H;
      var x = cv.getContext('2d'), layers = [];
      for (var i = 0; i < ovs.length; i++) {
        await wait();
        var lay = await overlayLayer(FR, ovs[i], i, W, H, T, x);
        if (lay) layers.push(lay);
        xoSet(.15 + .1 * (i + 1) / Math.max(1, ovs.length));
        if ((i & 3) === 3) await sleep(0);
      }
      var tScene = performance.now();
      await wait();
      var rStart = performance.now();
      sub = await FR.addListener('progress', function (e) {
        var p = (e && e.p) || 0;
        xoSet(.25 + .75 * p / 100);
        if (!XO.paused) st('🚀 ' + RND(p) + '%' + (e && e.fps ? ' · ' + RND(e.fps) + ' fps' : ''));
        if (!XO.paused && p > 2) { var q = p / 100, el = $('#xeta'); if (el) el.textContent = fmt((performance.now() - rStart) / 1000 * (1 - q) / q) + ' ' + L('x_left'); }
      });
      var res = await FR.turbo({
        out: 'turbo-out.mp4', width: W, height: H, bitrate: cfg.br,
        clips: [{ path: src, inMs: RND(c.in * 1000), outMs: RND(c.out * 1000) }], overlays: layers
      });
      var tRend = performance.now();
      var dg = String(res.ovDiag || '').split(' ').filter(Boolean);
      if (layers.length && dg.length && dg.every(function (z) { return !(parseInt((z.split('/')[1] || '0'), 10) > 0); })) {
        try { await FR.remove({ path: res.path }); } catch (_) { }
        throw new Error('overlays were not drawn (' + dg.join(' ').slice(0, 60) + ')');
      }
      /* stored inside the app; Play / Save to phone are the person's choice */
      var fin = expName({ kind: 'turbo', res: Math.min(W, H) + 'p' }), kept = null;
      try { kept = await FR.keepExport({ path: res.path, name: fin }); } catch (_) { kept = null; }
      var sv = null;
      if (!kept) sv = await FR.saveToGallery({ path: res.path, name: fin });
      if (kept) expAdd({ abs: kept.abs, name: fin, mb: res.sizeMB, ts: Date.now(), kind: 'turbo', res: Math.min(W, H) + 'p', dur: RND(T) });
      try { xoRelease(); } catch (_) { } try { gameToggle(false); } catch (_) { }
      XO.shown = 1; xoSet(1); $('#xo').className = 'done'; $('#xpct').textContent = '✓'; $('#xtip').textContent = ''; $('#xeta').textContent = ''; st(L('x_done'));
      var s1 = function (a, b) { return Math.round((b - a) / 100) / 10 + 's'; };
      var xi = $('#xinfo'), xa = $('#xact');
      if (xi) xi.textContent = res.sizeMB + ' MB · ' + (kept ? NL('inapp') : sv.where) + ' · ' + NL('k_turbo') + ' · enc ' + (res.enc || '?') + ' · ' + (res.fps || '?') + ' fps · copy ' + s1(t0, tCopy) + ' · overlays ' + s1(tCopy, tScene) + ' (' + layers.length + ') · render ' + s1(rStart, tRend) + ' · hits ' + dg.join(' ').slice(0, 80);
      if (xa) {
        if (kept) {
          var it = { abs: kept.abs, name: fin, mb: res.sizeMB };
          xa.innerHTML = '<button class="xb main" id="xplay">' + NL('play') + '</button><button class="xb" id="xsave">' + NL('tophone') + '</button><button class="xb" id="xclose">' + L('x_close') + '</button>';
          $('#xplay').onclick = function () { playExport(it); };
          $('#xsave').onclick = function () { saveExportToPhone(it, $('#xsave')); };
          $('#xclose').onclick = xoHide;
        } else {
          xa.innerHTML = '<button class="xb main" id="xsave">' + L('x_save') + '</button><button class="xb" id="xclose">' + L('x_close') + '</button>';
          $('#xsave').onclick = async function () { var b = $('#xsave'); try { if (sv.uri) await FR.shareVideo({ uri: sv.uri }); else b.textContent = '✓ ' + sv.where; } catch (e) { b.textContent = '⚠ ' + (e && e.message || e); } };
          $('#xclose').onclick = xoHide;
        }
      }
    } finally {
      try { if (sub) sub.remove(); } catch (_) { }
    }
  }

  window.turboExport = async function (cfg) {
    var r = why();
    if (r) { try { msg('🚀 Turbo: ' + r + ' → Standard Export'); } catch (_) { } return; }
    var FR = getFR(), caps = {};
    try { caps = (await FR.caps()) || {}; } catch (_) { }
    if (!caps.turbo) { try { msg('🚀 Turbo needs the new APK'); } catch (_) { } return; }
    try { await run(FR, cfg); }
    catch (e) {
      try { xoHide(); } catch (_) { }
      if (XO.cancel || /cancel/i.test(String(e && e.message || e))) return;
      console.warn('turbo', e);
      try { msg('🚀 Turbo: ' + (e && e.message || e)); } catch (_) { }
    }
  };
  window.turboWhy = why;
})();
