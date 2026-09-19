/*
 * Canvas graph engine, ported from prototype/graph-style-demo.html and wired to real
 * data. The rules it enforces (AC 4.1, 4.2, 4.4, 4.6):
 *  - Tier sets distance from centre: holdings innermost, tier 1, tier 2 outermost,
 *    customers on their own inner-ish ring. Within a tier a force simulation decides
 *    position. A dragged node settles back toward its tier band.
 *  - Node size: suppliers by dependant-holding count (bounded), holdings by weight.
 *    Shape: holdings are rounded squares, suppliers/customers circles.
 *  - Single-source edges are dashed regardless of colour mode.
 *  - Nothing animates on its own. The render loop runs only while the simulation is
 *    settling after a user action, while a focus transition eases, or while the
 *    user-triggered simulation pulse is active; otherwise it stops and redraws only on
 *    demand.
 */
const TrueSightGraph = (() => {
  const css = (() => { const c = {}; return v => c[v] || (c[v] = getComputedStyle(document.documentElement).getPropertyValue(v).trim()); })();
  const hex = (c, a) => { if (!c) return `rgba(128,128,128,${a})`; if (c.startsWith('rgb')) return c.replace(')', `,${a})`).replace('rgb(', 'rgba('); const n = parseInt(c.slice(1), 16); return `rgba(${n >> 16 & 255},${n >> 8 & 255},${n & 255},${a})`; };
  const PAL = ['#8B7CFF', '#FB923C', '#FBBF24', '#34D399', '#38BDF8', '#F472B6', '#A3E635', '#22D3EE', '#FB7185', '#C084FC', '#94A3B8', '#2DD4BF'];
  const RING = { 0: 120, 1: 265, 2: 455, '-1': 200 };
  const SEV_RANK = { UNKNOWN: 0, LOW: 1, MODERATE: 2, ELEVATED: 3, CRITICAL: 4 };

  function create(cv, handlers = {}) {
    const ctx = cv.getContext('2d');
    let W = 0, H = 0, DPR = 1, cam = { x: 0, y: 0, z: 1 };
    let nodes = new Map(), edges = [], arr = [];
    let mode = 'tier', hovered = null, latched = null, isolated = null, drag = null, dragNode = null, dragMoved = false;
    let hoverT = 0, focusT = 0, alpha = 0, rafId = null;
    let filters = { showSuppliers: true, showCustomers: true, maxTier: 2, minSeverity: 'UNKNOWN', hidden: new Set(), showRejected: false };
    let sim = null; // { epicentre:Set, affected:Set, startedAt }
    const cat = {};
    const F = { ring: 0.62, link: 0.45, rep: 0.55, lab: 0.70 };

    function resize() { DPR = Math.min(devicePixelRatio || 1, 2); W = cv.clientWidth; H = cv.clientHeight; cv.width = W * DPR; cv.height = H * DPR; ctx.setTransform(DPR, 0, 0, DPR, 0, 0); requestRender(); }
    new ResizeObserver(resize).observe(cv); resize();

    // ---- data ----
    function setData(gNodes, gEdges) {
      const prev = nodes; nodes = new Map(); edges = [];
      gNodes.forEach((n, i) => {
        const old = prev.get(n.id);
        const tier = n.kind === 'HOLDING' ? 0 : n.kind === 'CUSTOMER' ? -1 : n.tier;
        const t = (i / gNodes.length) * Math.PI * 2, r = RING[tier] || 300;
        nodes.set(n.id, { ...n, tier, holding: n.kind === 'HOLDING', customer: n.kind === 'CUSTOMER', deps: n.dependantHoldingCount, depW: Number(n.dependantWeightPercent || 0), w: Number(n.weightPercent || 0),
          adj: new Set(), x: old ? old.x : Math.cos(t) * r + (Math.random() - 0.5) * 40, y: old ? old.y : Math.sin(t) * r + (Math.random() - 0.5) * 40, vx: 0, vy: 0 });
      });
      gEdges.forEach(e => {
        const p = nodes.get(e.fromCompanyId), q = nodes.get(e.toCompanyId); if (!p || !q) return;
        p.adj.add(q.id); q.adj.add(p.id);
        edges.push({ ...e, from: e.fromCompanyId, to: e.toCompanyId, single: e.singleSource, rejected: e.reviewStatus === 'REJECTED' });
      });
      arr = [...nodes.values()];
      if (prev.size === 0) { cam.z = Math.min(W / 1250, H / 1250, 1) * 1.15 || 0.82; alpha = 1; for (let i = 0; i < 420; i++) tick(); alpha = 0.05; }
      else alpha = Math.max(alpha, 0.35);
      requestRender();
    }

    function radius(n) { if (n.holding) return 15 + Math.sqrt(n.w) * 2.0; if (n.customer) return 8.5; return Math.min(34, 8.5 + Math.sqrt(n.deps) * 6.4); }

    // ---- visibility ----
    function shown(n) {
      if (filters.hidden.has(n.id)) return false;
      if (n.customer && !filters.showCustomers) return false;
      if (!n.holding && !n.customer && (!filters.showSuppliers || n.tier > filters.maxTier)) return false;
      if (isolated) return n.id === isolated || nodes.get(isolated).adj.has(n.id);
      return true;
    }
    function edgeShown(e) {
      if (e.rejected && !filters.showRejected) return false;
      if ((SEV_RANK[e.riskSeverity || 'UNKNOWN'] || 0) < (SEV_RANK[filters.minSeverity] || 0)) return false;
      return shown(nodes.get(e.from)) && shown(nodes.get(e.to));
    }

    // ---- force simulation: tier is a soft radial constraint ----
    function tick() {
      const a = Math.max(alpha, 0.012);
      edges.forEach(e => { const p = nodes.get(e.from), q = nodes.get(e.to); const dx = q.x - p.x, dy = q.y - p.y, d = Math.hypot(dx, dy) || 0.01; const k = (d - 128) / d * F.link * 0.055 * a; p.vx += dx * k; p.vy += dy * k; q.vx -= dx * k; q.vy -= dy * k; });
      for (let i = 0; i < arr.length; i++) for (let j = i + 1; j < arr.length; j++) {
        const p = arr[i], q = arr[j]; const dx = q.x - p.x, dy = q.y - p.y, d2 = dx * dx + dy * dy; if (d2 > 62000 || d2 < 0.01) continue;
        const d = Math.sqrt(d2), min = radius(p) + radius(q) + 30; const f = (d < min ? (min - d) * 0.09 : 780 / d2) * F.rep * a; const ux = dx / d, uy = dy / d;
        p.vx -= ux * f; p.vy -= uy * f; q.vx += ux * f; q.vy += uy * f;
      }
      arr.forEach(n => { if (n.id === dragNode) return; const want = RING[n.tier] ?? 300; const d = Math.hypot(n.x, n.y) || 0.01; const k = (want - d) / d * F.ring * 0.085 * a; n.vx += n.x * k; n.vy += n.y * k; n.vx *= 0.82; n.vy *= 0.82; n.x += n.vx; n.y += n.vy; });
      alpha *= 0.992;
    }

    // ---- colour ----
    function colorOf(n) {
      if (mode === 'tier') { if (n.holding) return css('--holding'); if (n.customer) return css('--customer'); if (n.deps >= 2) return css('--shared'); return n.tier === 2 ? css('--tier2') : css('--tier1'); }
      const k = (mode === 'sector' ? n.sector : n.country) || 'Unknown';
      if (k === 'Unknown') return css('--severity-unknown');
      cat[mode] = cat[mode] || {}; if (!cat[mode][k]) cat[mode][k] = PAL[Object.keys(cat[mode]).length % PAL.length]; return cat[mode][k];
    }
    function legend() {
      if (mode === 'tier') return [['Your holding', css('--holding'), true], ['Shared supplier (2+ holdings)', css('--shared')], ['Tier 1 supplier', css('--tier1')], ['Tier 2 supplier', css('--tier2')], ['Key customer', css('--customer')]].map(([l, c, sq]) => ({ label: l, color: c, square: !!sq }));
      const seen = new Map(); arr.filter(shown).forEach(n => { const k = (mode === 'sector' ? n.sector : n.country) || 'Unknown'; if (!seen.has(k)) seen.set(k, { color: colorOf(n), n: 0 }); seen.get(k).n++; });
      return [...seen].sort((a, b) => b[1].n - a[1].n).map(([k, v]) => ({ label: k, color: v.color, count: v.n }));
    }

    // ---- render ----
    const S = n => ({ x: (n.x - cam.x) * cam.z + W / 2, y: (n.y - cam.y) * cam.z + H / 2 });
    const focusId = () => latched || hovered;
    function lit(id) { const f = focusId(); if (sim) return sim.epicentre.has(id) || sim.affected.has(id); return !f || id === f || nodes.get(f).adj.has(id); }

    function render() {
      rafId = null;
      const f = focusId();
      let animating = false;
      if (alpha > 0.015) { tick(); animating = true; }
      const hoverTarget = f ? 1 : 0, focusTarget = isolated ? 1 : 0;
      if (Math.abs(hoverT - hoverTarget) > 0.005) { hoverT += (hoverTarget - hoverT) * 0.14; animating = true; } else hoverT = hoverTarget;
      if (Math.abs(focusT - focusTarget) > 0.005) { focusT += (focusTarget - focusT) * 0.14; animating = true; } else focusT = focusTarget;
      if (sim) animating = true; // AC 7.5 exception: user-triggered epicentre pulse, stops when cleared

      ctx.clearRect(0, 0, W, H);
      edges.forEach(e => {
        if (!edgeShown(e)) return;
        const p = nodes.get(e.from), q = nodes.get(e.to); const A = S(p), B = S(q);
        const on = sim ? (lit(p.id) && lit(q.id)) : (!f || (lit(p.id) && lit(q.id) && (p.id === f || q.id === f)));
        const al = on ? 0.62 : 0.06 + (1 - hoverT) * 0.34;
        ctx.save();
        ctx.strokeStyle = e.single ? hex(css('--edge-single'), Math.min(1, al * 1.15)) : hex(css('--edge'), al);
        ctx.lineWidth = (e.single ? 1.5 : 1.05) * (e.dependencyPercent ? 0.8 + e.dependencyPercent / 100 : 1) * (on ? 1.55 : 1) * Math.min(cam.z, 1.5);
        ctx.setLineDash(e.single ? [4.5, 3.5] : e.rejected ? [1.5, 3] : []); ctx.lineCap = 'round';
        const mx = (A.x + B.x) / 2, my = (A.y + B.y) / 2, dx = B.x - A.x, dy = B.y - A.y;
        ctx.beginPath(); ctx.moveTo(A.x, A.y); ctx.quadraticCurveTo(mx - dy * 0.055, my + dx * 0.055, B.x, B.y); ctx.stroke();
        // direction arrow at the buyer end (AC 4.4: direction shown by arrow)
        if (on || cam.z > 0.9) { const ang = Math.atan2(B.y - (my + dx * 0.055), B.x - (mx - dy * 0.055)); const rq = radius(q) * cam.z + 2; const tx = B.x - Math.cos(ang) * rq, ty = B.y - Math.sin(ang) * rq; ctx.beginPath(); ctx.moveTo(tx, ty); ctx.lineTo(tx - Math.cos(ang - 0.45) * 6, ty - Math.sin(ang - 0.45) * 6); ctx.lineTo(tx - Math.cos(ang + 0.45) * 6, ty - Math.sin(ang + 0.45) * 6); ctx.closePath(); ctx.fillStyle = ctx.strokeStyle; ctx.fill(); }
        ctx.restore();
      });
      arr.slice().sort((a, b) => radius(a) - radius(b)).forEach(n => {
        if (!shown(n)) return;
        const p = S(n), r = radius(n) * cam.z, on = lit(n.id), col = colorOf(n);
        const al = on ? 1 : 0.11 + (1 - hoverT) * 0.89 * (sim ? 0.2 : 1);
        if (sim && sim.epicentre.has(n.id)) { const ph = ((performance.now() - sim.startedAt) % 1600) / 1600; const rr2 = r + 6 + ph * 22; ctx.beginPath(); ctx.arc(p.x, p.y, rr2, 0, 7); ctx.strokeStyle = hex(css('--status-danger'), (1 - ph) * 0.6); ctx.lineWidth = 2; ctx.stroke(); }
        if (on && (n.id === f || r > 15)) { const spread = n.id === f ? 1.85 : 1.5; const g = ctx.createRadialGradient(p.x, p.y, r * 0.8, p.x, p.y, r * spread); g.addColorStop(0, hex(col, n.id === f ? 0.11 : 0.05)); g.addColorStop(1, hex(col, 0)); ctx.fillStyle = g; ctx.beginPath(); ctx.arc(p.x, p.y, r * spread, 0, 7); ctx.fill(); }
        if (n.id === f) { ctx.beginPath(); ctx.arc(p.x, p.y, r + 7 + hoverT * 2, 0, 7); ctx.strokeStyle = hex(col, 0.32); ctx.lineWidth = 1.2; ctx.stroke(); }
        ctx.beginPath(); if (n.holding) rr(ctx, p.x - r, p.y - r, r * 2, r * 2, r * 0.34); else ctx.arc(p.x, p.y, r, 0, 7);
        ctx.fillStyle = hex(col, al * 0.80); ctx.fill(); ctx.strokeStyle = hex(col, al * 0.92); ctx.lineWidth = n.newlyAdded ? 2.4 : 1.2; ctx.stroke();
        if (n.holding && n.coverageStatus && n.coverageStatus !== 'COVERED') { ctx.fillStyle = hex('#DADADF', al * 0.9); ctx.font = `600 ${Math.max(8, 9 * cam.z)}px ${css('--font-mono')}`; ctx.textAlign = 'center'; ctx.textBaseline = 'middle'; ctx.fillText(n.coverageStatus === 'FAILED' ? '!' : '?', p.x, p.y); }
        const showLab = on && (n.holding || n.deps >= 2 || cam.z > 0.55 + (1 - F.lab) * 0.9 || n.id === f);
        if (showLab) {
          ctx.fillStyle = hex(n.holding ? '#EDEDF2' : '#B8B8C4', al); ctx.font = `${n.holding ? 600 : 400} ${Math.max(9.5, 11.5 * Math.min(cam.z, 1.2))}px ${css('--font-ui')}`; ctx.textAlign = 'center'; ctx.textBaseline = 'top';
          ctx.fillText(n.ticker && n.holding ? n.ticker : (n.name.length > 22 ? n.name.slice(0, 21) + '…' : n.name), p.x, p.y + r + 6);
          if (n.holding && n.w) { ctx.fillStyle = hex('#6B6B78', al); ctx.font = `400 ${Math.max(9, 10 * Math.min(cam.z, 1.15))}px ${css('--font-mono')}`; ctx.fillText(n.w.toFixed(1) + '%', p.x, p.y + r + 20); }
        }
      });
      if (animating) rafId = requestAnimationFrame(render);
    }
    function requestRender() { if (!rafId) rafId = requestAnimationFrame(render); }
    function rr(c, x, y, w, h, r) { c.moveTo(x + r, y); c.arcTo(x + w, y, x + w, y + h, r); c.arcTo(x + w, y + h, x, y + h, r); c.arcTo(x, y + h, x, y, r); c.arcTo(x, y, x + w, y, r); c.closePath(); }

    // ---- interaction ----
    function pick(mx, my) { let best = null, bd = 1e9; arr.forEach(n => { if (!shown(n)) return; const p = S(n), d = Math.hypot(p.x - mx, p.y - my); if (d < radius(n) * cam.z + 7 && d < bd) { bd = d; best = n.id; } }); return best; }
    function pickEdge(mx, my) {
      let best = null, bd = 7;
      edges.forEach(e => { if (!edgeShown(e)) return; const A = S(nodes.get(e.from)), B = S(nodes.get(e.to)); const l2 = (B.x - A.x) ** 2 + (B.y - A.y) ** 2; if (!l2) return; let t = ((mx - A.x) * (B.x - A.x) + (my - A.y) * (B.y - A.y)) / l2; t = Math.max(0.1, Math.min(0.9, t)); const d = Math.hypot(mx - (A.x + t * (B.x - A.x)), my - (A.y + t * (B.y - A.y))); if (d < bd) { bd = d; best = e; } });
      return best;
    }
    const xy = ev => { const b = cv.getBoundingClientRect(); return [ev.clientX - b.left, ev.clientY - b.top]; };
    cv.addEventListener('mousemove', ev => {
      const [mx, my] = xy(ev);
      if (dragNode) { const n = nodes.get(dragNode); n.x = (mx - W / 2) / cam.z + cam.x; n.y = (my - H / 2) / cam.z + cam.y; n.vx = n.vy = 0; alpha = Math.max(alpha, 0.30); dragMoved = true; requestRender(); return; }
      if (drag) { cam.x -= (mx - drag[0]) / cam.z; cam.y -= (my - drag[1]) / cam.z; drag = [mx, my]; requestRender(); return; }
      const h = pick(mx, my);
      if (h !== hovered) { hovered = h; cv.style.cursor = h ? 'pointer' : 'grab'; if (!latched && handlers.onHover) handlers.onHover(h ? nodes.get(h) : null); requestRender(); }
    });
    cv.addEventListener('mousedown', ev => { const [mx, my] = xy(ev), id = pick(mx, my); dragMoved = false; if (id) { dragNode = id; cv.style.cursor = 'grabbing'; } else { drag = [mx, my]; cv.style.cursor = 'grabbing'; } });
    addEventListener('mouseup', () => { if (dragNode) alpha = Math.max(alpha, 0.3); drag = dragNode = null; cv.style.cursor = hovered ? 'pointer' : 'grab'; requestRender(); });
    cv.addEventListener('click', ev => {
      if (dragMoved) return;
      const [mx, my] = xy(ev); const id = pick(mx, my);
      if (id) { latched = (id === latched) ? null : id; if (handlers.onSelect) handlers.onSelect(latched ? nodes.get(latched) : null); }
      else { const e = pickEdge(mx, my); if (e) { if (handlers.onEdge) handlers.onEdge(e); } else { latched = null; if (handlers.onSelect) handlers.onSelect(null); } }
      requestRender();
    });
    cv.addEventListener('dblclick', ev => { const id = pick(...xy(ev)); if (id) isolate(id); });
    cv.addEventListener('wheel', ev => { ev.preventDefault(); const [mx0, my0] = xy(ev), mx = mx0 - W / 2, my = my0 - H / 2; const bx = mx / cam.z + cam.x, by = my / cam.z + cam.y; cam.z = Math.max(0.32, Math.min(3.2, cam.z * (ev.deltaY < 0 ? 1.11 : 1 / 1.11))); cam.x = bx - mx / cam.z; cam.y = by - my / cam.z; requestRender(); }, { passive: false });
    cv.addEventListener('keydown', ev => { if (ev.key === 'Escape') exit(); });

    // ---- public ----
    function isolate(id) { isolated = latched = id; savedCam = { ...cam }; if (handlers.onIsolate) handlers.onIsolate(nodes.get(id)); requestRender(); }
    let savedCam = null;
    function exit() { const was = isolated; isolated = latched = null; if (was && savedCam) { cam = savedCam; savedCam = null; } if (handlers.onIsolate) handlers.onIsolate(null); if (handlers.onSelect) handlers.onSelect(null); requestRender(); }
    function select(id, center) { latched = id; if (center && nodes.get(id)) { const n = nodes.get(id); cam.x = n.x; cam.y = n.y; } if (handlers.onSelect) handlers.onSelect(id ? nodes.get(id) : null); requestRender(); }
    function fit() { const vis = arr.filter(shown); if (!vis.length) return; let minX = 1e9, minY = 1e9, maxX = -1e9, maxY = -1e9; vis.forEach(n => { const r = radius(n) + 24; minX = Math.min(minX, n.x - r); maxX = Math.max(maxX, n.x + r); minY = Math.min(minY, n.y - r); maxY = Math.max(maxY, n.y + r); }); cam.x = (minX + maxX) / 2; cam.y = (minY + maxY) / 2; cam.z = Math.max(0.32, Math.min(3.2, Math.min((W - 380) / (maxX - minX), (H - 80) / (maxY - minY)))); requestRender(); }
    function zoom(f) { cam.z = Math.max(0.32, Math.min(3.2, cam.z * f)); requestRender(); }
    function setColorMode(m) { mode = m; requestRender(); }
    function setFilters(patch) { Object.assign(filters, patch); requestRender(); }
    function setSimulation(s) { sim = s ? { epicentre: new Set(s.epicentre), affected: new Set(s.affected), startedAt: performance.now() } : null; requestRender(); }
    function find(q) { const s = q.trim().toLowerCase(); if (!s) return null; return arr.find(n => (n.ticker || '').toLowerCase() === s) || arr.find(n => n.name.toLowerCase().includes(s)) || null; }
    function node(id) { return nodes.get(id); }
    function edgesOf(id) { return edges.filter(e => e.from === id || e.to === id); }
    function setForce(k, v) { F[k] = v; alpha = Math.max(alpha, 0.22); requestRender(); }
    return { setData, select, isolate, exit, fit, zoom, setColorMode, setFilters, setSimulation, find, node, edgesOf, legend, colorOf, setForce, state: () => ({ latched, isolated, hovered, mode }), requestRender };
  }
  return { create };
})();
