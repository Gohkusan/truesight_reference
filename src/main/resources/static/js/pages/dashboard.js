/* Epic 3: composite score, top risks, recent alerts, coverage, since-last-visit. Each section carries its own timestamp (AC 3.3). */
App.page('dashboard', async (view) => {
  const d = await Api.portfolio.dashboard();
  const { esc } = UI;

  if (d.coverage.total === 0) {
    view.innerHTML = `<div class="page-head"><div><h1>${esc(d.portfolioName)}</h1><div class="sub">Dashboard</div></div></div>` +
      UI.empty('No holdings yet',
        'Upload a CSV of your holdings, or add a company by ticker. TrueSight then fetches each company\'s latest SEC filing and extracts its suppliers and customers.',
        '<a class="btn primary" href="#/portfolio">Go to Portfolio</a>');
    return;
  }

  const r = d.risk;
  const scoreCard = r.compositeScore === null
    ? `<div class="kpi"><div class="value" style="color:var(--severity-unknown)">Unknown</div><div class="label">Composite risk</div></div>
       <p class="small muted" style="margin-top:var(--space-2)">${d.coverage.covered === 0
        ? 'No holding has been analysed successfully yet, so there is no score to show. Check coverage below for why.'
        : 'Holdings were analysed but no verified supplier relationships were found, so nothing contributes to a score.'}</p>`
    : `<div class="kpi"><div class="value">${r.compositeScore}<span class="muted" style="font-size:var(--text-md)"> /100</span></div>
       <div class="label">${UI.sev(r.band)} Composite risk</div></div>
       <p class="small muted" style="margin-top:var(--space-2)">${r.scoredHoldings} of ${r.totalHoldings} holdings scored${r.unknownHoldings ? `, ${r.unknownHoldings} unknown (excluded)` : ''}.</p>`;

  const topRisks = d.topRisks.length
    ? `<div class="table-wrap"><table><thead><tr><th>Risk</th><th class="num">Score</th><th>Trend</th><th class="num">Weight exposed</th></tr></thead><tbody>
       ${d.topRisks.map(i => `<tr class="clickable" onclick="location.hash='#/risks/${i.relationshipId ? 'r' + i.relationshipId : 'h' + i.holdingId}'">
         <td>${esc(i.title)}<div class="xs faint">${esc(i.factors[0] || '')}</div></td>
         <td class="num">${UI.scorebar(i.score, i.severity)}</td><td>${UI.trend(i.trend, i.previousScore, i.score)}</td>
         <td class="num">${UI.pct(i.totalWeightExposed)}</td></tr>`).join('')}</tbody></table></div>`
    : UI.empty('No scored risks', d.coverage.covered ? 'Analysed filings produced no verified supplier relationships to score.' : 'Nothing has been analysed yet.');

  const alerts = d.recentAlerts.length
    ? d.recentAlerts.map(a => `<div class="row between" style="padding:8px 0;border-bottom:1px solid var(--line)">
        <div><a href="#/alerts">${esc(a.headline)}</a><div class="xs faint">${esc(a.sourceCompanyName)} · event ${UI.dateOnly(a.eventAt)} · ${a.affectedHoldings.length} holding(s)</div></div>${UI.sev(a.severity)}</div>`).join('')
    : `<p class="muted small">No open alerts. News is checked on refresh; run one from Settings or wait for the daily job.</p>`;

  const cov = d.coverage;
  const sinceHtml = d.sinceLastVisit.previousLoginAt
    ? `<div class="stat"><span>Since ${UI.when(d.sinceLastVisit.previousLoginAt)}</span><b></b></div>
       <div class="stat"><span>New alerts</span><b>${d.sinceLastVisit.newAlerts}</b></div>
       <div class="stat"><span>Changed risk scores</span><b>${d.sinceLastVisit.changedRiskScores}</b></div>
       <div class="stat"><span>New or updated relationships</span><b>${d.sinceLastVisit.newRelationships}</b></div>`
    : `<p class="muted small">This is your first visit; the summary appears from your next login.</p>`;

  view.innerHTML = `
    <div class="page-head"><div><h1>${esc(d.portfolioName)}</h1><div class="sub">Where is this portfolio exposed?</div></div>
      <div class="actions"><a class="btn" href="#/network">Open network</a><a class="btn" href="#/risks">All risks</a></div></div>
    <div class="grid cols-4" style="margin-bottom:var(--space-4)">
      <div class="panel"><div class="bd tooltip" data-tip="${esc(r.howComputed)}" tabindex="0" aria-label="How the composite score is computed">${scoreCard}</div>
        <div class="ft"><span class="timestamp">Analysis run ${UI.when(r.lastAnalysedAt)}</span> · <a href="#/risks">details</a></div></div>
      <div class="panel"><div class="bd"><div class="kpi"><div class="value">${cov.covered}<span class="muted" style="font-size:var(--text-md)"> of ${cov.total}</span></div><div class="label">Holdings covered</div></div>
        <div class="small muted" style="margin-top:var(--space-2)">${cov.pending ? cov.pending + ' pending · ' : ''}${cov.failed ? `<span style="color:var(--status-danger)">${cov.failed} failed</span> · ` : ''}${cov.noSecFilings ? cov.noSecFilings + ' no SEC filings' : ''}</div></div>
        <div class="ft"><a href="#/portfolio">holdings</a></div></div>
      <div class="panel"><div class="bd">${sinceHtml}</div><div class="ft"><span class="timestamp">Now ${UI.fullTime(d.generatedAt)}</span></div></div>
      <div class="panel"><div class="bd"><div class="kpi"><div class="value">${d.recentAlerts.length}</div><div class="label">Open alerts (recent)</div></div></div><div class="ft"><a href="#/alerts">alerts</a></div></div>
    </div>
    ${cov.failed ? `<div class="banner warning" style="margin-bottom:var(--space-4)"><span class="glyph">!</span><span class="msg"><b>Analysis incomplete</b>${cov.failed} holding(s) failed. Their reasons are listed on the Portfolio page. Scores exclude them.</span></div>` : ''}
    <div class="grid cols-2">
      <div class="panel"><div class="hd"><h3>Top risks</h3><span class="timestamp">${UI.when(r.lastAnalysedAt)}</span></div><div class="bd" style="padding:0">${topRisks}</div></div>
      <div class="panel"><div class="hd"><h3>Recent alerts</h3><span class="timestamp">article dates shown per alert</span></div><div class="bd">${alerts}</div></div>
    </div>
    <div class="disclaimer">TrueSight provides research support, not investment advice. Findings are areas to investigate, derived from SEC filings and public news with verbatim-excerpt verification.</div>`;
});
