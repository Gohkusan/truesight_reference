/* AC 9.3: every AI action, filterable, exportable. */
App.page('audit', async (view) => {
  const { esc } = UI;
  let type = '';
  const TYPES = ['SEC_FILING_EXTRACTION', 'NEWS_SENTIMENT_ANALYSIS', 'SIMULATION_PROPAGATION', 'GRAPH_QUESTION_ANSWERING'];
  const label = (t) => ({ SEC_FILING_EXTRACTION: 'Filing extraction', NEWS_SENTIMENT_ANALYSIS: 'News analysis', SIMULATION_PROPAGATION: 'Simulation', GRAPH_QUESTION_ANSWERING: 'Question' }[t] || t);

  async function load() {
    const host = UI.el('auditRows'); host.innerHTML = `<tr><td colspan="7">${UI.loading()}</td></tr>`;
    const rows = await Api.audit.list(type ? '?type=' + type : '');
    const ok = rows.filter(r => r.success).length;
    UI.el('auditStats').textContent = `${rows.length} entries · ${ok} succeeded · ${rows.length - ok} failed · avg latency ${rows.length ? Math.round(rows.reduce((s, r) => s + (r.latencyMs || 0), 0) / rows.length) : 0} ms`;
    host.innerHTML = rows.length ? rows.map(r => `<tr>
      <td class="timestamp">${UI.fullTime(r.createdAt)}</td><td>${esc(label(r.actionType))}</td><td>${esc(r.targetEntity)}</td>
      <td class="mono xs">${esc(r.modelName)}</td><td class="small muted">${esc(r.inputReference || '')}</td>
      <td class="small">${r.success ? esc(r.outputSummary || '') : `<span style="color:var(--status-danger)">${esc(r.errorMessage || 'failed')}</span>`}</td>
      <td class="num mono">${r.latencyMs ?? '—'}</td></tr>`).join('')
      : `<tr><td colspan="7">${UI.empty('No AI actions recorded', 'Every filing extraction, news analysis, simulation and question is logged here as it happens.')}</td></tr>`;
  }

  view.innerHTML = `
    <div class="page-head"><div><h1>AI audit trail</h1><div class="sub" id="auditStats"></div></div>
      <div class="actions"><a class="btn" href="/api/audit/export.json" id="expJson">Export JSON</a><a class="btn" href="/api/audit/export.csv" id="expCsv">Export CSV</a></div></div>
    <div class="chips" style="margin-bottom:var(--space-3)"><button class="chip" data-t="" aria-pressed="true">All</button>${TYPES.map(t => `<button class="chip" data-t="${t}" aria-pressed="false">${esc(label(t))}</button>`).join('')}</div>
    <div class="table-wrap"><table><thead><tr><th>When</th><th>Action</th><th>Target</th><th>Model</th><th>Input</th><th>Outcome</th><th class="num">ms</th></tr></thead><tbody id="auditRows"></tbody></table></div>`;
  // Exports need the bearer token, which a plain <a href> cannot send: fetch then save.
  const download = async (url, name) => {
    const res = await Api.get(url, { raw: true }); const blob = await res.blob();
    const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = name; a.click(); URL.revokeObjectURL(a.href);
  };
  UI.el('expJson').onclick = (e) => { e.preventDefault(); download('/api/audit/export.json', 'truesight-audit.json'); };
  UI.el('expCsv').onclick = (e) => { e.preventDefault(); download('/api/audit/export.csv', 'truesight-audit.csv'); };
  view.querySelector('.chips').onclick = (e) => { const c = e.target.closest('.chip'); if (!c) return; type = c.dataset.t; view.querySelectorAll('.chip').forEach(x => x.setAttribute('aria-pressed', x === c)); load(); };
  await load();
});
