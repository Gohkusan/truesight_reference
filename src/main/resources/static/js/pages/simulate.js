/* Epic 12: scenario form, results with paths and traceable explanation, compare two scenarios, hand-off to the network canvas. */
App.page('simulate', async (view, params) => {
  const { esc } = UI;
  const graph = await Api.portfolio.graph(false);
  const companies = graph.nodes.filter(n => n.kind !== 'CUSTOMER').sort((a, b) => a.name.localeCompare(b.name));
  const sectors = [...new Set(graph.nodes.map(n => n.sector).filter(Boolean))].sort();
  let lastA = null, lastB = null;

  const form = (id, preset) => `
    <div class="panel"><div class="hd"><h3>Scenario ${id}</h3></div><div class="bd stack">
      <div class="field"><label>Epicentre</label>
        <div class="seg" data-mode="${id}"><button aria-pressed="true" data-m="companies">Companies</button><button aria-pressed="false" data-m="sector">Sector</button></div></div>
      <div class="field" data-companies="${id}"><select multiple size="6" id="comp${id}">${companies.map(c => `<option value="${c.id}" ${preset && String(preset) === String(c.id) ? 'selected' : ''}>${esc(c.name)}${c.ticker ? ' (' + esc(c.ticker) + ')' : ''} · ${c.kind === 'HOLDING' ? 'holding' : 'tier ' + c.tier}</option>`).join('')}</select><span class="help">Ctrl/Cmd-click for several.</span></div>
      <div class="field" data-sector="${id}" style="display:none"><select id="sect${id}">${sectors.length ? sectors.map(s => `<option>${esc(s)}</option>`).join('') : '<option value="">No sectors assigned yet</option>'}</select></div>
      <div class="field"><label>Capacity lost: <b class="mono" id="sevVal${id}">80</b>%</label><input type="range" id="sev${id}" min="20" max="100" value="80"></div>
      <div class="field"><label>Duration: <b class="mono" id="durVal${id}">90</b> days</label><input type="range" id="dur${id}" min="30" max="365" step="5" value="90"></div>
      <div class="field"><label for="label${id}">Label (optional)</label><input type="text" id="label${id}" placeholder="e.g. Taiwan Strait closure"></div>
    </div></div>`;

  const read = (id) => {
    const mode = view.querySelector(`.seg[data-mode="${id}"] [aria-pressed="true"]`).dataset.m;
    return {
      epicentreCompanyIds: mode === 'companies' ? [...UI.el('comp' + id).selectedOptions].map(o => Number(o.value)) : [],
      epicentreSector: mode === 'sector' ? UI.el('sect' + id).value : null,
      severityPercent: Number(UI.el('sev' + id).value), durationDays: Number(UI.el('dur' + id).value),
      label: UI.el('label' + id).value.trim() || null,
    };
  };

  const result = (r) => `<div class="panel"><div class="hd"><h3>${esc(r.label)}</h3><span class="small muted">${r.severityPercent}% for ${r.durationDays} days</span></div><div class="bd">
    <div class="kpi" style="margin-bottom:10px"><div class="value">${UI.pct(r.totalWeightExposed)}</div><div class="label">of portfolio weight reached · ${r.affectedHoldings.length} holding(s), ${r.affectedNodes.length} node(s) in the blast radius</div></div>
    ${r.affectedHoldings.length ? `<div class="table-wrap"><table><thead><tr><th>Holding</th><th class="num">Weight</th><th class="num">Impact</th><th class="num">Weight × impact</th><th>Path</th></tr></thead><tbody>
      ${r.affectedHoldings.map(h => `<tr><td><span class="mono">${esc(h.ticker)}</span> ${esc(h.name)}</td><td class="num">${UI.pct(h.weightPercent)}</td><td class="num">${Math.round(h.impactFraction * 100)}%</td><td class="num">${h.weightAtImpact === null ? '—' : UI.pct(h.weightAtImpact)}</td><td class="xs muted">${h.path.map(esc).join(' → ')}</td></tr>`).join('')}</tbody></table></div>`
      : '<p class="muted small">No holding is downstream of this epicentre through verified relationships.</p>'}
    <details style="margin-top:10px"><summary>How each number was derived (${r.explanation.length} steps)</summary>${UI.factors(r.explanation)}</details>
    <div class="row" style="margin-top:10px"><a class="btn sm" href="#/network/sim${encodeURIComponent(JSON.stringify({ ids: r.epicentreCompanyIds, nodes: r.affectedNodes.map(n => n.companyId) }))}">Show on network</a></div>
  </div></div>`;

  view.innerHTML = `
    <div class="page-head"><div><h1>Simulate a disruption</h1><div class="sub">Propagates downstream through verified supplier relationships. Impact factors are model assumptions, shown with every result.</div></div>
      <div class="actions"><label class="row small"><input type="checkbox" id="compareToggle"> Compare two scenarios</label></div></div>
    ${companies.length ? '' : UI.empty('No graph to simulate on', 'Analyse holdings first so there are relationships for a disruption to travel along.')}
    <div class="grid cols-2" id="forms">${form('A', params[0] && params[0].startsWith('c') ? params[0].slice(1) : null)}<div id="formB" style="display:none">${form('B')}</div></div>
    <div class="row" style="margin:var(--space-4) 0"><button class="btn primary" id="run">Run</button><span class="small muted" id="runMsg"></span></div>
    <div class="grid cols-2" id="results"></div>
    <div class="disclaimer">Scenario output is a research aid computed from disclosed relationships and stated criticality. It is not a forecast and not investment advice.</div>`;

  ['A', 'B'].forEach(id => {
    UI.el('sev' + id).oninput = (e) => UI.el('sevVal' + id).textContent = e.target.value;
    UI.el('dur' + id).oninput = (e) => UI.el('durVal' + id).textContent = e.target.value;
    view.querySelector(`.seg[data-mode="${id}"]`).onclick = (e) => { const b = e.target.closest('button'); if (!b) return;
      [...e.currentTarget.children].forEach(x => x.setAttribute('aria-pressed', x === b));
      view.querySelector(`[data-companies="${id}"]`).style.display = b.dataset.m === 'companies' ? '' : 'none';
      view.querySelector(`[data-sector="${id}"]`).style.display = b.dataset.m === 'sector' ? '' : 'none'; };
  });
  UI.el('compareToggle').onchange = (e) => UI.el('formB').style.display = e.target.checked ? '' : 'none';
  UI.el('run').onclick = async () => {
    const b = UI.el('run'); b.disabled = true; UI.el('runMsg').textContent = '';
    try {
      if (UI.el('compareToggle').checked) {
        const c = await Api.portfolio.compare(read('A'), read('B'));
        UI.el('results').innerHTML = result(c.a) + result(c.b) +
          `<div class="panel" style="grid-column:1/-1"><div class="bd"><b>${c.moreSevere === 'EQUAL' ? 'Both scenarios reach the same portfolio weight.' : 'Scenario ' + c.moreSevere + ' reaches more portfolio weight'}</b> — difference ${UI.pct(c.exposureDifference)}.</div></div>`;
      } else {
        const r = await Api.portfolio.simulate(read('A'));
        UI.el('results').innerHTML = result(r);
      }
    } catch (e) { UI.el('runMsg').textContent = e.message; }
    b.disabled = false;
  };
});
