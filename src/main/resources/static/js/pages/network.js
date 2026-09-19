/* Epic 4: the network screen. Wires TrueSightGraph to /graph, with inspector, legend, colour-by, filters, shared suppliers, focus mode, search, tier-2 expansion, simulation overlay. */
App.page('network', async (view, params) => {
  const { esc } = UI;
  let data = null, showRejected = false, engine = null, currentSelection = null;

  view.innerHTML = `<div id="stage">
    <canvas id="c" tabindex="0" aria-label="Supply-chain network graph. Use search to select a company."></canvas>
    <div id="graphEmpty" style="display:none"></div>
    <div id="leftList" class="float" style="display:none"></div>
    <div id="inspector" class="float" aria-live="polite"></div>
    <div id="toolbar" class="float">
      <input type="search" id="gsearch" placeholder="Search company or ticker" aria-label="Search company">
      <button class="btn sm" id="fit" title="Fit whole graph">Fit</button>
      <button class="btn sm" id="zin" aria-label="Zoom in">+</button><button class="btn sm" id="zout" aria-label="Zoom out">−</button>
      <button class="btn sm" id="listToggle" title="Holdings list">List</button>
    </div>
    <div id="controls" class="float">
      <details class="grp" open><summary>Colour by</summary><div class="gbody">
        <div class="seg" id="colorSeg"><button data-mode="tier" aria-pressed="true">Tier</button><button data-mode="sector" aria-pressed="false">Sector</button><button data-mode="country" aria-pressed="false">Country</button></div>
        <div id="legend" style="margin-top:9px"></div></div></details>
      <details class="grp" open><summary>Always encoded</summary><div class="gbody" style="font-size:12px;color:var(--text-2);line-height:1.75">
        <div class="item"><span class="dot sq" style="background:var(--holding)"></span>Square — your holding (size = weight)</div>
        <div class="item"><span class="dot" style="background:var(--muted)"></span>Circle — supplier (size = holdings depending)</div>
        <div class="item"><span style="width:9px;flex:none;text-align:center;color:var(--edge-single);letter-spacing:-1px">┄</span>Dashed — single-source</div>
        <div class="item"><span style="width:9px;flex:none;text-align:center;color:var(--muted)">→</span>Arrow — supplier to buyer</div>
        <div class="item"><span style="width:9px;flex:none;text-align:center;color:var(--muted)">━</span>Thickness — disclosed dependency %</div>
        <div class="item"><span style="width:9px;flex:none;text-align:center;color:var(--muted)">↔</span>Distance — tier depth</div>
        <div class="item"><span style="width:9px;flex:none;text-align:center;color:var(--muted)">!</span>Mark — holding not covered</div></div></details>
      <details class="grp"><summary>Filters</summary><div class="gbody">
        <div class="item"><label><input type="checkbox" id="fSup" checked> Suppliers</label></div>
        <div class="item"><label><input type="checkbox" id="fCust" checked> Customers</label></div>
        <div class="item"><label><input type="checkbox" id="fT2" checked> Tier 2</label></div>
        <div class="item"><label>Min risk <select id="fSev" style="width:auto;padding:2px 6px;font-size:11px">${['UNKNOWN', 'LOW', 'MODERATE', 'ELEVATED', 'CRITICAL'].map(s => `<option value="${s}">${UI.SEV_LABEL[s]}</option>`).join('')}</select></label></div>
        <div class="item"><label><input type="checkbox" id="fRej"> Show rejected</label></div>
        <div class="item"><button class="btn sm ghost" id="unhide">Unhide all</button></div></div></details>
      <details class="grp"><summary>Forces</summary><div class="gbody">
        ${[['ring', 'Tier pull', 62], ['link', 'Link', 45], ['rep', 'Repel', 55], ['lab', 'Labels', 70]].map(([k, l, v]) => `<div class="item"><label style="width:54px">${l}</label><input type="range" data-f="${k}" min="0" max="100" value="${v}"></div>`).join('')}</div></details>
    </div>
    <div id="shared" class="float"><div class="shd"><span class="dot" style="background:var(--shared)"></span><b>Shared suppliers</b><span class="key" id="sharedCount"></span></div><div id="sharedRows"></div></div>
    <div id="simBanner" class="banner danger"><span class="glyph">!</span><span class="msg"><b>Simulation view</b><span id="simText"></span> <button class="btn sm ghost" id="simClear">Clear</button></span></div>
    <button id="esc" class="btn">Exit focus <span class="kbd">Esc</span></button>
    <div id="hint">Click to focus · double-click to isolate · drag nodes · scroll to zoom</div>
  </div>`;

  const cv = UI.el('c');
  engine = TrueSightGraph.create(cv, { onHover: paintInspector, onSelect: (n) => { currentSelection = n; paintInspector(n); }, onEdge: openEdge,
    onIsolate: (n) => { UI.el('esc').style.display = n ? 'block' : 'none'; } });

  async function load() {
    data = await Api.portfolio.graph(showRejected);
    if (!data.nodes.length) {
      UI.el('graphEmpty').style.display = ''; UI.el('graphEmpty').innerHTML = UI.empty('No graph yet', 'Add holdings and run analysis. Each holding\'s suppliers and customers appear here once extracted and verified from its filing.', '<a class="btn primary" href="#/portfolio">Go to Portfolio</a>');
    } else UI.el('graphEmpty').style.display = 'none';
    engine.setData(data.nodes, data.edges);
    paintLegend(); paintShared(); paintList();
    const covered = data.nodes.filter(n => n.kind === 'HOLDING' && n.coverageStatus === 'COVERED').length, total = data.nodes.filter(n => n.kind === 'HOLDING').length;
    if (total && covered < total) UI.banner('warning', 'Analysis incomplete', `${total - covered} of ${total} holdings are not covered; their dependencies are not on the graph. Marked with ! or ? on the canvas.`, 'graph-incomplete'); else UI.clearBanner('graph-incomplete');
  }

  // ---- inspector: node ----
  function paintInspector(n) {
    const el = UI.el('inspector');
    if (!n) { if (currentSelection) n = currentSelection; else { el.style.display = 'none'; return; } }
    const edges = engine.edgesOf(n.id);
    const ups = edges.filter(e => e.to === n.id).map(e => ({ e, other: engine.node(e.from) }));
    const dns = edges.filter(e => e.from === n.id).map(e => ({ e, other: engine.node(e.to) }));
    const kind = n.holding ? 'Your holding' : n.customer ? 'Key customer' : `Tier ${n.tier} supplier`;
    el.style.display = 'block';
    el.innerHTML = `<div class="ihd"><h1>${esc(n.name)}</h1>
      <div class="row wrap"><span class="badge"><span class="dot${n.holding ? ' sq' : ''}" style="background:${engine.colorOf(n)}"></span>${kind}</span>
        ${n.ticker ? `<span class="badge mono">${esc(n.ticker)}</span>` : ''}${n.isPrivate ? '<span class="badge">private / no SEC filings</span>' : ''}
        ${n.holding && n.coverageStatus !== 'COVERED' ? UI.coverage(n.coverageStatus) : ''}${n.newlyAdded ? '<span class="badge new">new</span>' : ''}</div></div>
      <div class="ibd">
      <div class="stat"><span>Sector</span><b>${esc(n.sector || 'Unknown')} ${n.holding || !n.customer ? `<button class="btn ghost sm" data-sector="${n.id}" title="Override sector">edit</button>` : ''}</b></div>
      <div class="stat"><span>Country</span><b>${esc(n.country || 'Unknown')}</b></div>
      ${n.holding ? `<div class="stat"><span>Portfolio weight</span><b>${UI.pct(n.w)}</b></div>` : ''}
      ${!n.holding && !n.customer ? `<div class="stat"><span>Holdings depending</span><b class="${n.deps >= 2 ? 'warn' : ''}" style="${n.deps >= 2 ? 'color:var(--shared)' : ''}">${n.deps}</b></div>
        <div class="stat"><span>Weight exposed</span><b>${UI.pct(n.depW)}</b></div>
        <div class="note">${n.deps === 1 ? '1 of your holdings depends on this company.' : n.deps + ' of your holdings depend on this company.'}${n.deps >= 2 ? ' This is a shared supplier: the same size that ranks it in the shared-supplier table.' : ''}</div>` : ''}
      ${n.alsoSupplies && n.alsoSupplies.length ? `<div class="note">Also supplies ${n.alsoSupplies.map(esc).join(', ')}</div>` : ''}
      ${n.noRelationshipsFound ? `<div class="note">No relationships found in this holding's analysed filing${n.coverageStatus !== 'COVERED' ? ' (not covered — see Portfolio for the reason)' : ''}.</div>` : ''}
      ${dns.length ? `<div class="sub">Supplies</div>${dns.map(({ e, other }) => link(e, other, e.single ? 'single-source' : 'edge')).join('')}` : ''}
      ${ups.length ? `<div class="sub">Depends on</div>${ups.map(({ e, other }) => link(e, other, 'tier ' + other.tier)).join('')}` : ''}
      <div class="row wrap" style="margin-top:12px">
        <button class="btn sm" data-focus="${n.id}">Focus</button>
        ${!n.holding && !n.customer && !n.isPrivate ? `<button class="btn sm" data-expand="${n.id}" title="Analyse this supplier's own filing to reveal its suppliers (tier 2). Triggers SEC and AI calls; cached afterwards.">Expand tier 2…</button>` : ''}
        <button class="btn sm ghost" data-hide="${n.id}">Hide</button>
        <a class="btn sm ghost" href="#/simulate/c${n.id}">Simulate outage</a>
        ${n.holding ? `<a class="btn sm ghost" href="#/risks/h${n.holdingId || ''}">Risks</a>` : ''}
      </div></div>`;
    el.querySelectorAll('[data-go]').forEach(d => d.onclick = () => engine.select(Number(d.dataset.go), false));
    el.querySelectorAll('[data-edge]').forEach(d => d.onclick = (ev) => { ev.stopPropagation(); openEdge(engine.edgesOf(n.id).find(e => e.id === Number(d.dataset.edge))); });
    el.querySelector('[data-focus]').onclick = () => engine.isolate(n.id);
    el.querySelector('[data-hide]').onclick = () => { hidden.add(n.id); engine.setFilters({ hidden }); engine.exit(); };
    const ex = el.querySelector('[data-expand]'); if (ex) ex.onclick = () => expand(n);
    const sec = el.querySelector('[data-sector]'); if (sec) sec.onclick = async () => { const v = prompt('Sector for ' + n.name, n.sector || ''); if (v !== null) { await Api.companies.setSector(n.id, v.trim()); await load(); engine.select(n.id, false); } };
  }
  const link = (e, other, tag) => `<div class="link" data-go="${other.id}">${esc(other.name)}<span class="tag ${e.single ? 's' : ''} ${e.rejected ? 'rej' : ''}">${esc(tag)}${e.riskSeverity && e.riskSeverity !== 'UNKNOWN' ? ' · ' + esc(UI.SEV_LABEL[e.riskSeverity]) : ''}</span><button class="btn ghost sm" data-edge="${e.id}" title="Evidence">evidence</button></div>`;

  // ---- inspector: edge (AC 4.3, Epic 5) ----
  async function openEdge(e) {
    if (!e) return;
    const el = UI.el('inspector'); el.style.display = 'block'; el.innerHTML = `<div class="ibd">${UI.loading('Loading evidence…')}</div>`;
    let d; try { d = await Api.portfolio.relationship(e.id); } catch (err) { el.innerHTML = `<div class="ibd">${UI.errorBox(err, 'Relationship')}</div>`; return; }
    el.innerHTML = `<div class="ihd"><h1 style="font-size:14px">${esc(d.fromCompanyName)} → ${esc(d.toCompanyName)}</h1><div class="row"><button class="btn ghost sm" id="backToNode">← back</button></div></div><div class="ibd">${RelationshipPanel.html(d)}</div>`;
    el.querySelector('#backToNode').onclick = () => paintInspector(currentSelection || engine.node(e.to));
    RelationshipPanel.bind(el, d, async () => { await load(); openEdge(e); });
  }

  // ---- AC 4.8: expand a supplier (warns; cached second time) ----
  async function expand(n) {
    const ok = await UI.modal({ title: 'Expand ' + n.name + '?', confirmText: 'Analyse and expand',
      bodyHtml: `<p>This fetches ${esc(n.name)}'s own latest SEC filing and runs AI extraction on it to reveal its suppliers (tier 2). It uses SEC and AI calls; results are cached, so expanding again is instant. Expansion is capped at tier 2.</p>` });
    if (!ok) return;
    try { const r = await Api.portfolio.expand(n.id); UI.toast(r.message || 'Expanded.'); await load(); engine.select(n.id, false); } catch (e) { UI.banner('danger', 'Expansion failed', e.message, 'expand'); App.refreshStatus(); }
  }

  // ---- legend / shared / list ----
  function paintLegend() {
    UI.el('legend').innerHTML = engine.legend().map(l => `<div class="item"><span class="dot${l.square ? ' sq' : ''}" style="background:${l.color}"></span>${esc(l.label)}${l.count !== undefined ? `<span class="key">${l.count}</span>` : ''}</div>`).join('') +
      (engine.state().mode !== 'tier' ? '<div class="xs faint" style="margin-top:6px">"Unknown" is never guessed. Size, shape and dashes are unchanged by colour mode.</div>' : '');
  }
  function paintShared() {
    const rows = data.sharedSuppliers; UI.el('sharedCount').textContent = rows.length ? rows.length : '';
    UI.el('sharedRows').innerHTML = rows.length ? rows.map(s => `<div class="srow" data-id="${s.companyId}" title="${esc(s.holdingTickers.join(', '))}"><span class="nm">${esc(s.name)}</span><span class="ct">${s.holdingCount}</span><span class="wt">${UI.pct(s.combinedWeightPercent)}</span></div>`).join('')
      : '<div class="srow none">None: no supplier is connected to two or more of your holdings.</div>';
    UI.el('sharedRows').querySelectorAll('[data-id]').forEach(r => r.onclick = () => engine.select(Number(r.dataset.id), true));
  }
  function paintList() {
    const holdings = data.nodes.filter(n => n.kind === 'HOLDING');
    UI.el('leftList').innerHTML = `<div class="shd" style="padding:10px 12px;border-bottom:1px solid var(--line);font-size:12.5px"><b>Holdings</b></div>` + holdings.map(h => {
      const linked = data.edges.filter(e => e.toCompanyId === h.id || e.fromCompanyId === h.id).map(e => data.nodes.find(n => n.id === (e.toCompanyId === h.id ? e.fromCompanyId : e.toCompanyId))).filter(Boolean);
      return `<details><summary class="lrow"><span class="mono">${esc(h.ticker)}</span> ${esc(h.name)} <span class="sub">${UI.pct(h.weightPercent)} · ${linked.length} linked</span></summary>
        <div class="lrow" data-go="${h.id}"><span class="sub">focus holding</span></div>${linked.map(c => `<div class="lrow" data-go="${c.id}">${esc(c.name)}<div class="sub">${c.kind === 'CUSTOMER' ? 'customer' : 'tier ' + c.tier}</div></div>`).join('')}</details>`;
    }).join('');
    UI.el('leftList').querySelectorAll('[data-go]').forEach(d => d.onclick = () => { engine.select(Number(d.dataset.go), true); });
  }

  // ---- controls ----
  const hidden = new Set();
  UI.el('colorSeg').onclick = (ev) => { const b = ev.target.closest('button'); if (!b) return; [...ev.currentTarget.children].forEach(x => x.setAttribute('aria-pressed', x === b)); engine.setColorMode(b.dataset.mode); paintLegend(); if (currentSelection) paintInspector(currentSelection); };
  UI.el('fSup').onchange = (e) => engine.setFilters({ showSuppliers: e.target.checked });
  UI.el('fCust').onchange = (e) => engine.setFilters({ showCustomers: e.target.checked });
  UI.el('fT2').onchange = (e) => engine.setFilters({ maxTier: e.target.checked ? 2 : 1 });
  UI.el('fSev').onchange = (e) => engine.setFilters({ minSeverity: e.target.value });
  UI.el('fRej').onchange = async (e) => { showRejected = e.target.checked; engine.setFilters({ showRejected }); await load(); };
  UI.el('unhide').onclick = () => { hidden.clear(); engine.setFilters({ hidden }); };
  view.querySelectorAll('[data-f]').forEach(r => r.oninput = (e) => engine.setForce(r.dataset.f, e.target.value / 100));
  UI.el('fit').onclick = () => engine.fit();
  UI.el('zin').onclick = () => engine.zoom(1.25); UI.el('zout').onclick = () => engine.zoom(0.8);
  UI.el('esc').onclick = () => engine.exit();
  UI.el('listToggle').onclick = () => { const l = UI.el('leftList'); const on = l.style.display === 'none'; l.style.display = on ? '' : 'none'; UI.el('inspector').style.display = on ? 'none' : (currentSelection ? 'block' : 'none'); };
  UI.el('gsearch').onchange = (e) => { const n = engine.find(e.target.value); if (n) { UI.el('leftList').style.display = 'none'; engine.select(n.id, true); } else if (e.target.value) UI.toast('No company matches "' + e.target.value + '".'); };
  UI.el('simClear').onclick = () => { engine.setSimulation(null); UI.el('simBanner').style.display = 'none'; };
  const onKey = (ev) => { if (ev.key === 'Escape') engine.exit(); }; document.addEventListener('keydown', onKey);
  cv.addEventListener('click', () => { UI.el('hint').style.opacity = 0; }, { once: true });

  await load();
  // Route params: holding id (from Portfolio), c<companyId>, or sim<json> (from Simulate).
  const p = params[0];
  if (p && p.startsWith('sim')) {
    try { const s = JSON.parse(decodeURIComponent(p.slice(3))); engine.setSimulation({ epicentre: s.ids, affected: s.nodes }); UI.el('simText').textContent = ` ${s.nodes.length} node(s) in the blast radius; others dimmed. Epicentres pulse.`; UI.el('simBanner').style.display = ''; } catch { }
  } else if (p && p.startsWith('c')) { engine.select(Number(p.slice(1)), true); }
  else if (p) { const h = data.nodes.find(n => n.kind === 'HOLDING' && String(n.holdingId) === p); if (h) engine.select(h.id, true); }
});
