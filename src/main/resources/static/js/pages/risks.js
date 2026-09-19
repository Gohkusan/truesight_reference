/* Epic 6: ranked risk list with factors, trend, affected holdings; concentration view (AC 4.9). Route param opens a row: h<holdingId> or r<relationshipId>. */
App.page('risks', async (view, params) => {
  const { esc } = UI;
  const [summary, items, conc, holdings] = await Promise.all([
    Api.portfolio.riskSummary(), Api.portfolio.risks(), Api.portfolio.concentration(), Api.portfolio.holdings()]);
  let minSev = 'UNKNOWN', holdingFilter = '', kindFilter = 'ALL';
  const rank = { UNKNOWN: 0, LOW: 1, MODERATE: 2, ELEVATED: 3, CRITICAL: 4 };

  const filtered = () => items.filter(i => rank[i.severity] >= rank[minSev])
    .filter(i => !holdingFilter || i.affectedHoldings.some(h => String(h.holdingId) === holdingFilter))
    .filter(i => kindFilter === 'ALL' || i.kind === kindFilter);

  const rowHtml = (i) => `<tr class="clickable" data-open="${i.kind === 'RELATIONSHIP' ? 'r' + i.relationshipId : 'h' + i.holdingId}">
    <td>${UI.sev(i.severity)}</td>
    <td>${esc(i.title)}<div class="xs faint">${i.kind === 'RELATIONSHIP' ? 'dependency' : 'holding'}${i.changeReason ? ' · ' + esc(i.changeReason) : ''}</div></td>
    <td class="num">${UI.scorebar(i.score, i.severity)}</td>
    <td>${UI.trend(i.trend, i.previousScore, i.score)}</td>
    <td>${i.affectedHoldings.map(h => `<span class="mono">${esc(h.ticker)}</span> ${UI.pct(h.weightPercent)}`).join(', ')}</td>
    <td class="num">${UI.pct(i.totalWeightExposed)}</td>
    <td class="timestamp">${UI.when(i.assessedAt)}</td></tr>`;

  function drawList() {
    const rows = filtered();
    UI.el('riskRows').innerHTML = rows.length ? rows.map(rowHtml).join('')
      : `<tr><td colspan="7">${UI.empty('Nothing matches', items.length ? 'Relax the filters to see more.' : 'No risks have been scored. Analyse holdings on the Portfolio page first.')}</td></tr>`;
    view.querySelectorAll('[data-open]').forEach(tr => tr.onclick = () => openDetail(tr.dataset.open));
  }

  async function openDetail(key) {
    if (key.startsWith('r')) {
      const id = Number(key.slice(1));
      let d; try { d = await Api.portfolio.relationship(id); } catch (e) { UI.banner('danger', 'Relationship not found', e.message); return; }
      await UI.modal({ title: `${d.fromCompanyName} → ${d.toCompanyName}`, confirmText: '', cancelText: 'Close', wide: true, bodyHtml: RelationshipPanel.html(d),
        onOpen: (back) => RelationshipPanel.bind(back, d, async () => { location.reload(); }) });
    } else {
      const id = Number(key.slice(1));
      const i = items.find(x => x.holdingId === id); if (!i) return;
      const deps = items.filter(x => x.kind === 'RELATIONSHIP' && x.affectedHoldings.some(h => h.holdingId === id));
      await UI.modal({ title: i.title, confirmText: '', cancelText: 'Close', wide: true, bodyHtml: `
        <div class="row" style="margin-bottom:10px">${UI.sev(i.severity)} ${UI.scorebar(i.score, i.severity)} ${UI.trend(i.trend, i.previousScore, i.score)}</div>
        ${i.score === null ? `<div class="note">${esc(i.factors[0] || 'Unknown')}. Unknown is not Low: no score is shown because there is no verified data to score.</div>` : ''}
        <h3 style="margin:12px 0 6px">Factors</h3>${UI.factors(i.factors)}
        ${i.changeReason ? `<h3 style="margin:12px 0 6px">Last change</h3><p class="small muted">${esc(i.changeReason)} · ${UI.fullTime(i.assessedAt)}</p>` : ''}
        <h3 style="margin:12px 0 6px">Dependencies (${deps.length})</h3>
        ${deps.length ? `<div class="table-wrap"><table><tbody>${deps.map(dd => `<tr class="clickable" data-open2="r${dd.relationshipId}"><td>${UI.sev(dd.severity)}</td><td>${esc(dd.title)}</td><td class="num">${UI.scorebar(dd.score, dd.severity)}</td></tr>`).join('')}</tbody></table></div>` : '<p class="muted small">No verified dependencies.</p>'}`,
        onOpen: (back) => back.querySelectorAll('[data-open2]').forEach(tr => tr.onclick = () => { back.querySelector('[data-cancel]').click(); openDetail(tr.dataset.open2); }) });
    }
  }

  const slice = (s) => `<div class="stat"><span>${esc(s.key)}${s.warning ? ' <span class="badge sev sev-ELEVATED">≥35%</span>' : ''}</span><b>${UI.pct(s.weightPercent)} <span class="faint">(${Math.round(s.share * 100)}%)</span></b></div>`;

  view.innerHTML = `
    <div class="page-head"><div><h1>Risks</h1><div class="sub">Most severe first. Unknown means no verified data, not low risk.</div></div>
      <div class="actions"><button class="btn" id="expCsv">Export CSV</button><button class="btn" id="expPdf">Export PDF</button></div></div>
    <div class="grid cols-3" style="margin-bottom:var(--space-4)">
      <div class="panel"><div class="bd tooltip" data-tip="${esc(summary.howComputed)}" tabindex="0"><div class="kpi"><div class="value">${summary.compositeScore === null ? '<span style="color:var(--severity-unknown)">Unknown</span>' : summary.compositeScore}</div><div class="label">${UI.sev(summary.band)} Composite · ${summary.scoredHoldings} scored, ${summary.unknownHoldings} unknown</div></div></div><div class="ft timestamp">Analysis run ${UI.when(summary.lastAnalysedAt)}</div></div>
      <div class="panel"><div class="hd"><h3>Exposure by holding sector</h3></div><div class="bd">${conc.bySector.length ? conc.bySector.map(slice).join('') : '<span class="muted small">No sector data yet (assigned from filings).</span>'}</div></div>
      <div class="panel"><div class="hd"><h3>Exposure by supplier country</h3></div><div class="bd">${conc.bySupplierCountry.length ? conc.bySupplierCountry.map(slice).join('') : '<span class="muted small">No supplier country data yet.</span>'}<div class="xs faint" style="margin-top:6px">Warning at ≥35%. "Unknown" is never guessed.</div></div></div>
    </div>
    <div class="row wrap" style="margin-bottom:var(--space-3)">
      <div class="seg" id="kindSeg"><button aria-pressed="true" data-k="ALL">All</button><button data-k="HOLDING">Holdings</button><button data-k="RELATIONSHIP">Dependencies</button></div>
      <label class="small muted">Min severity <select id="minSev" style="width:auto">${['UNKNOWN', 'LOW', 'MODERATE', 'ELEVATED', 'CRITICAL'].map(s => `<option value="${s}">${UI.SEV_LABEL[s]}</option>`).join('')}</select></label>
      <label class="small muted">Holding <select id="hold" style="width:auto"><option value="">All</option>${holdings.map(h => `<option value="${h.id}">${esc(h.ticker)}</option>`).join('')}</select></label>
    </div>
    <div class="table-wrap"><table><thead><tr><th>Severity</th><th>Risk</th><th class="num">Score</th><th>Trend</th><th>Affected holdings (weight)</th><th class="num">Exposed</th><th>Assessed</th></tr></thead><tbody id="riskRows"></tbody></table></div>
    <div class="disclaimer">Scores are additive factor counts from verified filing relationships and open alerts; hover the composite for the formula. Research support only, not advice.</div>`;
  UI.el('minSev').onchange = (e) => { minSev = e.target.value; drawList(); };
  UI.el('hold').onchange = (e) => { holdingFilter = e.target.value; drawList(); };
  UI.el('kindSeg').onclick = (e) => { const b = e.target.closest('button'); if (!b) return; kindFilter = b.dataset.k; [...e.currentTarget.children].forEach(x => x.setAttribute('aria-pressed', x === b)); drawList(); };
  // Exports need the bearer token, which a plain <a href> cannot send: fetch, then save the blob.
  const download = async (kind, name) => {
    const res = await Api.get(Api.portfolio.exportUrl(kind), { raw: true });
    if (!res.ok) { UI.banner('danger', 'Export failed', 'HTTP ' + res.status, 'export'); return; }
    const a = document.createElement('a'); a.href = URL.createObjectURL(await res.blob()); a.download = name; a.click(); URL.revokeObjectURL(a.href);
  };
  UI.el('expCsv').onclick = () => download('risks.csv', 'truesight-risks.csv');
  UI.el('expPdf').onclick = () => download('report.pdf', 'truesight-report.pdf');
  drawList();
  if (params[0]) openDetail(params[0]);
});

/* Shared relationship evidence panel: used by Risks (modal) and Network (inspector). AC 4.3, 5.1-5.5. */
const RelationshipPanel = (() => {
  const { esc } = UI;
  function html(d) {
    const rev = d.reviewStatus;
    return `
      <div class="row wrap" style="margin-bottom:10px">
        <span class="badge ${d.singleSource ? 'single' : ''}">${d.singleSource ? 'Single-source' : d.criticality === 'DUAL_SOURCE' ? 'Dual-source' : 'Diversified'}</span>
        <span class="badge">supplier → buyer · tier ${d.tier}</span>
        ${d.dependencyPercent !== null ? `<span class="badge">dependency <span class="mono">${d.dependencyPercent}%</span></span>` : ''}
        ${UI.conf(d.confidenceScore, d.confidenceBand)}
        ${d.sourcesConflict ? '<span class="badge sev sev-MODERATE">sources conflict</span>' : ''}
        ${d.noLongerDisclosed ? '<span class="badge stale">no longer disclosed</span>' : ''}
        ${rev === 'CONFIRMED' ? '<span class="badge confirmed">confirmed by you</span>' : rev === 'REJECTED' ? '<span class="badge rejected">rejected by you</span>' : '<span class="badge">unreviewed</span>'}
      </div>
      ${d.disruptionReason ? `<div class="note" style="margin-bottom:10px">${esc(d.disruptionReason)}</div>` : ''}
      ${d.noLongerDisclosed ? `<div class="banner warning" style="margin-bottom:10px"><span class="glyph">!</span><span class="msg"><b>Not mentioned in the newest filing</b>Excluded from risk scores unless you confirm it still holds.</span></div>` : ''}
      <details style="margin-bottom:10px"><summary>How the confidence score (${d.confidenceScore ?? '—'}) was computed</summary>${UI.factors(d.confidenceFactors)}</details>
      <h3 style="margin:8px 0 6px">Sources (${d.evidence.length}) · last verified ${UI.dateOnly(d.lastVerified)}</h3>
      ${d.evidence.length ? d.evidence.map(e => `<div style="margin-bottom:10px">
        <div class="row small muted" style="margin-bottom:4px"><span class="badge">${esc(e.sourceType.replace('FORM_', '')).replace('10K', '10-K').replace('20F', '20-F').replace('10Q', '10-Q').replace('8K', '8-K').replace('6K', '6-K')}</span><span>${UI.dateOnly(e.sourceDate)}</span>${e.accessionNumber ? `<span class="mono xs">${esc(e.accessionNumber)}</span>` : ''}${e.sourceUrl ? `<a href="${esc(e.sourceUrl)}" target="_blank" rel="noopener">open filing</a>` : ''}</div>
        <div class="excerpt">${esc(e.excerpt)}</div></div>`).join('')
        : '<p class="muted small">No sources. This should not happen: relationships without a verified excerpt are never stored.</p>'}
      <div class="divider"></div>
      <h3 style="margin-bottom:6px">Your judgement</h3>
      <div class="row wrap">
        <button class="btn ${rev === 'CONFIRMED' ? 'primary' : ''}" data-rv="CONFIRMED">Confirm</button>
        <button class="btn ${rev === 'REJECTED' ? 'danger' : ''}" data-rv="REJECTED">Reject</button>
        ${rev !== 'PENDING' ? '<button class="btn ghost" data-rv="PENDING">Clear judgement</button>' : ''}
        <input type="text" id="rvNote" placeholder="Optional note" value="${esc(d.reviewNote || '')}" style="flex:1;min-width:160px">
      </div>
      ${d.reviewedAt ? `<div class="xs faint" style="margin-top:6px">Last reviewed ${UI.fullTime(d.reviewedAt)}</div>` : ''}
      ${d.riskHistory.length ? `<details style="margin-top:12px"><summary>Risk history (${d.riskHistory.length} assessment${d.riskHistory.length > 1 ? 's' : ''}, real dates only)</summary>
        <table><tbody>${d.riskHistory.map(p => `<tr><td class="timestamp">${UI.fullTime(p.assessedAt)}</td><td class="num">${p.score ?? '?'}</td><td>${UI.sev(p.severity)}</td><td class="small muted">${esc(p.changeReason || '')}</td></tr>`).join('')}</tbody></table></details>` : ''}`;
  }
  function bind(root, d, onChanged) {
    root.querySelectorAll('[data-rv]').forEach(b => b.onclick = async () => {
      const status = b.dataset.rv, note = root.querySelector('#rvNote').value.trim() || null;
      b.disabled = true;
      try {
        const updated = await Api.portfolio.review(d.id, status, note);
        UI.toast(status === 'PENDING' ? 'Judgement cleared.' : `Relationship ${status.toLowerCase()}. Scores recomputed.`, {
          undo: async () => { await Api.portfolio.review(d.id, d.reviewStatus, d.reviewNote); if (onChanged) onChanged(); } });
        if (onChanged) onChanged(updated);
      } catch (e) { UI.banner('danger', 'Could not save judgement', e.message); b.disabled = false; }
    });
  }
  return { html, bind };
})();
