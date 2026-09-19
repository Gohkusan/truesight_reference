/* AC 9.4: questions answered only from this portfolio's graph; citations verified server-side. */
App.page('ask', async (view) => {
  const { esc } = UI;
  const history = [];
  const render = () => UI.el('answers').innerHTML = history.length ? history.map(h => `<div class="panel"><div class="bd">
      <div class="small muted" style="margin-bottom:6px">You asked</div><p style="margin-bottom:10px">${esc(h.q)}</p>
      ${h.error ? `<div class="banner danger"><span class="glyph">!</span><span class="msg">${esc(h.error)}</span></div>` : `
      <div class="small muted" style="margin-bottom:6px">${h.a.answered ? 'Answer (from your graph only)' : 'Declined'}</div>
      <p ${h.a.answered ? '' : 'class="muted"'}>${esc(h.a.answer)}</p>
      ${h.a.citedCompanies.length ? `<div class="row wrap" style="margin-top:8px"><span class="xs faint">Cites</span>${h.a.citedCompanies.map(c => `<span class="badge">${esc(c)}</span>`).join('')}</div>` : ''}
      ${h.a.citedExcerpts.length ? h.a.citedExcerpts.map(e => `<div class="excerpt" style="margin-top:8px">${esc(e)}</div>`).join('') : ''}
      ${h.a.unverifiedCitations.length ? `<div class="banner warning" style="margin-top:8px"><span class="glyph">!</span><span class="msg"><b>Unverified citations</b>The model referred to ${h.a.unverifiedCitations.map(esc).join('; ')} — not found in your graph's nodes or excerpts. Treat with caution.</span></div>` : ''}`}
    </div></div>`).join('') : UI.empty('Ask about your graph', 'For example: "Which supplier do most of my holdings depend on?" or "Which relationships are single-source?" Questions outside this portfolio\'s data are declined.');
  view.innerHTML = `
    <div class="page-head"><div><h1>Ask</h1><div class="sub">Plain-language questions about this portfolio's supply-chain graph. Answers cite verified companies and excerpts.</div></div></div>
    <form id="askForm" class="row" style="margin-bottom:var(--space-4)"><input type="text" id="q" maxlength="500" placeholder="Ask a question about your graph…" style="flex:1" autocomplete="off"><button class="btn primary" id="askBtn" type="submit">Ask</button></form>
    <div class="stack" id="answers"></div>
    <div class="disclaimer">The model sees only this portfolio's nodes, relationships and verbatim excerpts. It gives no buy, sell or hedging advice.</div>`;
  render();
  UI.el('askForm').onsubmit = async (e) => {
    e.preventDefault(); const q = UI.el('q').value.trim(); if (!q) return;
    const b = UI.el('askBtn'); b.disabled = true; b.innerHTML = '<span class="spinner"></span>';
    try { history.unshift({ q, a: await Api.portfolio.ask(q) }); }
    catch (err) { history.unshift({ q, error: err.message }); App.refreshStatus(); }
    b.disabled = false; b.textContent = 'Ask'; UI.el('q').value = ''; render();
  };
});
