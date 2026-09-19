/* Epic 11: areas to investigate, each linked to the risk/evidence it came from. Disclaimer on every render. */
App.page('recommendations', async (view) => {
  const { esc } = UI;
  const r = await Api.portfolio.recommendations();
  const link = (rec) => rec.relationshipId ? `#/risks/r${rec.relationshipId}` : rec.holdingId ? `#/risks/h${rec.holdingId}` : rec.companyId ? `#/network/c${rec.companyId}` : rec.kind === 'REVIEW_PENDING' ? '#/network' : rec.kind === 'ALERT' ? '#/alerts' : '#/risks';
  const card = (rec) => `<div class="panel"><div class="bd">
    <div class="row wrap" style="margin-bottom:4px">${rec.severity ? UI.sev(rec.severity) : ''}<span class="badge">${esc(rec.kind.toLowerCase().replace(/_/g, ' '))}</span></div>
    <h3 style="margin-bottom:4px"><a href="${link(rec)}">${esc(rec.title)}</a></h3>
    <p class="small muted">${esc(rec.reasoning)}</p>
    <details style="margin-top:6px"><summary>Evidence (${rec.evidence.length})</summary>${UI.factors(rec.evidence)}</details>
  </div></div>`;
  view.innerHTML = `
    <div class="page-head"><div><h1>To investigate</h1><div class="sub">Derived from scores, shared suppliers, pending reviews and alerts. Each item links to its evidence.</div></div></div>
    <div class="banner info" style="margin-bottom:var(--space-4)"><span class="glyph">i</span><span class="msg">${esc(r.disclaimer)}</span></div>
    <div class="grid cols-2">
      <div class="stack"><h2>Top risks</h2>${r.topRisks.length ? r.topRisks.map(card).join('') : UI.empty('No scored risks yet', 'Analyse holdings first; scored dependencies appear here ranked by severity.')}</div>
      <div class="stack"><h2>Look into next</h2>${r.lookIntoNext.length ? r.lookIntoNext.map(card).join('') : UI.empty('Nothing pending', 'No shared suppliers, unreviewed relationships, stale disclosures or open alerts right now.')}</div>
    </div>
    <div class="timestamp" style="margin-top:var(--space-4)">Generated ${UI.fullTime(r.generatedAt)}</div>`;
});
