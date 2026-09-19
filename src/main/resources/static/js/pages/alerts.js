/* Epic 7: alerts feed with filters, review, dismiss-with-reason (undoable), sources. */
App.page('alerts', async (view) => {
  const { esc } = UI;
  const holdings = await Api.portfolio.holdings();
  let filters = { minSeverity: '', holdingId: '', since: '', includeDismissed: false };
  let alerts = [];

  const query = () => {
    const q = new URLSearchParams();
    if (filters.minSeverity) q.set('minSeverity', filters.minSeverity);
    if (filters.holdingId) q.set('holdingId', filters.holdingId);
    if (filters.since) q.set('since', new Date(filters.since).toISOString());
    if (filters.includeDismissed) q.set('includeDismissed', 'true');
    const s = q.toString(); return s ? '?' + s : '';
  };

  const card = (a) => `<div class="panel" data-alert="${a.id}" style="${a.status === 'DISMISSED' || a.status === 'ARCHIVED' ? 'opacity:.6' : ''}">
    <div class="bd">
      <div class="row between" style="align-items:flex-start;gap:12px">
        <div style="flex:1;min-width:0">
          <div class="row wrap" style="margin-bottom:4px">${UI.sev(a.severity)}<span class="badge">${esc(a.status.toLowerCase())}${a.dismissReason ? ': ' + esc(a.dismissReason.toLowerCase().replace('_', ' ')) : ''}</span>
            <span class="badge">${a.sourceIsHolding ? 'holding' : 'supplier'}: ${esc(a.sourceCompanyName)}</span></div>
          <h3 style="margin-bottom:4px">${esc(a.headline)}</h3>
          <p class="small muted">${esc(a.summary || '')}</p>
          <div class="small" style="margin-top:8px"><b>Why it matters:</b> affects ${a.affectedHoldings.length ? a.affectedHoldings.map(h => `<span class="mono">${esc(h.ticker)}</span> (${UI.pct(h.weightPercent)})`).join(', ') : 'no current holding directly'}${a.affectedHoldings.length ? ` · <b>${UI.pct(a.totalWeightExposed)}</b> of portfolio weight` : ''}</div>
          <div class="xs faint" style="margin-top:6px">Event ${UI.fullTime(a.eventAt)} · detected ${UI.when(a.detectedAt)} · ${a.sources.length} source(s):
            ${a.sources.map(s => `<a href="${esc(s.url)}" target="_blank" rel="noopener">${esc(s.outlet || 'link')}</a>`).join(' · ')}</div>
        </div>
        <div class="row" style="flex-direction:column;align-items:stretch;gap:6px">
          ${a.status === 'NEW' ? `<button class="btn sm" data-review="${a.id}">Mark reviewed</button>` : ''}
          ${a.status === 'NEW' || a.status === 'REVIEWED' ? `<button class="btn sm ghost" data-dismiss="${a.id}">Dismiss…</button>` : `<button class="btn sm ghost" data-reopen="${a.id}">Reopen</button>`}
        </div>
      </div>
    </div></div>`;

  async function load() {
    const host = UI.el('alertList'); host.innerHTML = UI.loading();
    try { alerts = await Api.portfolio.alerts(query()); } catch (e) { host.innerHTML = UI.errorBox(e, 'Alerts'); return; }
    host.innerHTML = alerts.length ? `<div class="stack">${alerts.map(card).join('')}</div>`
      : UI.empty('No alerts', filters.includeDismissed || filters.minSeverity || filters.holdingId || filters.since
        ? 'Nothing matches these filters.'
        : 'No open alerts. News is fetched for each holding and tier-1 supplier on refresh; articles become alerts only when relevance ≥ 0.25 and sentiment ≤ −0.25.',
        '<button class="btn" id="refreshNews">Fetch news now</button>');
    const rn = UI.el('refreshNews'); if (rn) rn.onclick = refreshNews;
    host.querySelectorAll('[data-review]').forEach(b => b.onclick = async () => { await Api.portfolio.alertReview(Number(b.dataset.review)); await load(); });
    host.querySelectorAll('[data-reopen]').forEach(b => b.onclick = async () => { await Api.portfolio.alertReopen(Number(b.dataset.reopen)); await load(); });
    host.querySelectorAll('[data-dismiss]').forEach(b => b.onclick = () => dismiss(Number(b.dataset.dismiss)));
  }

  async function dismiss(id) {
    const a = alerts.find(x => x.id === id);
    let reason = 'NOT_RELEVANT';
    const ok = await UI.modal({ title: 'Dismiss this alert', confirmText: 'Dismiss',
      bodyHtml: `<p class="small muted" style="margin-bottom:10px">${esc(a.headline)}</p>
        <div class="field"><label>Reason (stored to tune future alerts)</label>
        ${[['NOT_RELEVANT', 'Not relevant to my holdings'], ['DUPLICATE', 'Duplicate of another alert'], ['WRONG_COMPANY', 'About a different company']]
          .map(([v, l], i) => `<label class="row small"><input type="radio" name="reason" value="${v}" ${i === 0 ? 'checked' : ''}> ${l}</label>`).join('')}</div>`,
      onConfirm: (back) => { reason = back.querySelector('input[name=reason]:checked').value; return true; } });
    if (!ok) return;
    await Api.portfolio.alertDismiss(id, reason); await load();
    UI.toast('Alert dismissed.', { undo: async () => { await Api.portfolio.alertReopen(id); await load(); } });
  }

  async function refreshNews() {
    const b = UI.el('newsBtn'); b.disabled = true; b.innerHTML = '<span class="spinner"></span> Fetching…';
    try {
      const o = await Api.portfolio.newsRefresh();
      UI.toast(`${o.companiesChecked} companies checked, ${o.articlesAnalysed} articles analysed, ${o.alertsCreated} new alert(s)${o.failures.length ? `, ${o.failures.length} failure(s)` : ''}.`);
      if (o.failures.length) UI.banner('warning', 'Some news checks failed', o.failures.slice(0, 3).join(' · '), 'news');
      App.refreshStatus();
    } catch (e) { UI.banner('danger', 'News refresh failed', e.message, 'news'); }
    b.disabled = false; b.textContent = 'Fetch news now';
    await load();
  }

  view.innerHTML = `
    <div class="page-head"><div><h1>Alerts</h1><div class="sub">Events affecting holdings and their tier-1 suppliers. Dismissed alerts are hidden by default.</div></div>
      <div class="actions"><button class="btn" id="newsBtn">Fetch news now</button></div></div>
    <div class="row wrap" style="margin-bottom:var(--space-3)">
      <label class="small muted">Min severity <select id="fSev" style="width:auto"><option value="">Your setting</option>${['LOW', 'MODERATE', 'ELEVATED', 'CRITICAL'].map(s => `<option value="${s}">${UI.SEV_LABEL[s]}</option>`).join('')}</select></label>
      <label class="small muted">Holding <select id="fHold" style="width:auto"><option value="">All</option>${holdings.map(h => `<option value="${h.id}">${esc(h.ticker)}</option>`).join('')}</select></label>
      <label class="small muted">Since <input type="date" id="fSince" style="width:auto"></label>
      <label class="small muted row"><input type="checkbox" id="fDismissed"> Show dismissed and archived</label>
    </div>
    <div id="alertList"></div>`;
  UI.el('newsBtn').onclick = refreshNews;
  UI.el('fSev').onchange = (e) => { filters.minSeverity = e.target.value; load(); };
  UI.el('fHold').onchange = (e) => { filters.holdingId = e.target.value; load(); };
  UI.el('fSince').onchange = (e) => { filters.since = e.target.value; load(); };
  UI.el('fDismissed').onchange = (e) => { filters.includeDismissed = e.target.checked; load(); };
  await load();
});
