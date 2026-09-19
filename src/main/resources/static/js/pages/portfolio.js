/* Epic 2: holdings table, CSV upload with preview/confirm/diff, add by ticker, remove with undo, progress with cancel. */
App.page('portfolio', async (view) => {
  const { esc } = UI;
  let holdings = await Api.portfolio.holdings();
  let progressTimer = null;

  const weightSum = () => holdings.reduce((s, h) => s + (h.weightPercent === null ? 0 : Number(h.weightPercent)), 0);
  const anyWeights = () => holdings.some(h => h.weightPercent !== null);

  function table() {
    if (!holdings.length) {
      return UI.empty('No holdings in this portfolio',
        'Upload a CSV (columns like Ticker, Name, Weight, Shares in any order) or add a company by ticker. Analysis starts after you confirm the preview.',
        '<button class="btn primary" id="uploadBtn2">Upload CSV</button>');
    }
    const sum = weightSum();
    const warn = anyWeights() && Math.abs(sum - 100) > 1
      ? `<div class="banner warning" style="margin-bottom:var(--space-3)"><span class="glyph">!</span><span class="msg"><b>Weights sum to ${sum.toFixed(1)}%, not 100%</b>Percent allocations feed every exposure figure. <button class="btn sm" id="normalise">Normalise to 100%</button> (shown, not saved — re-upload to change stored weights)</span></div>` : '';
    return warn + `<div class="table-wrap"><table id="holdingsTable"><thead><tr>
      <th class="sortable" data-key="ticker">Ticker</th><th class="sortable" data-key="companyName">Name</th>
      <th class="sortable num" data-key="weightPercent">Weight %</th><th class="num">Shares</th>
      <th class="sortable" data-key="coverageStatus">Coverage</th><th>Last analysed</th><th></th></tr></thead><tbody></tbody></table></div>`;
  }
  const row = (h) => `<tr class="${h.newlyAdded ? 'highlight' : ''}" data-id="${h.id}">
    <td class="mono">${esc(h.ticker || '—')}${h.newlyAdded ? ' <span class="badge new">new</span>' : ''}</td>
    <td>${esc(h.companyName)}</td>
    <td class="num">${h.weightPercent === null ? '<span class="faint">blank</span>' : Number(h.weightPercent).toFixed(2)}</td>
    <td class="num">${h.shares === null ? '<span class="faint">blank</span>' : Number(h.shares).toLocaleString()}</td>
    <td>${UI.coverage(h.coverageStatus, h.failureReason)}${h.failureReason ? `<div class="xs" style="color:var(--status-danger);max-width:320px">${esc(h.failureReason)}</div>` : ''}</td>
    <td class="timestamp">${UI.when(h.lastAnalysedAt)}</td>
    <td><span class="row"><a class="btn ghost sm" href="#/network/${h.id}">Graph</a><button class="btn ghost sm danger" data-remove="${h.id}">Remove</button></span></td></tr>`;

  function draw() {
    UI.el('holdingsHost').innerHTML = table();
    const t = UI.el('holdingsTable');
    if (t) UI.makeSortable(t, holdings, row, 'weightPercent');
    view.querySelectorAll('[data-remove]').forEach(b => b.onclick = () => removeHolding(Number(b.dataset.remove)));
    const up2 = UI.el('uploadBtn2'); if (up2) up2.onclick = openUpload;
    const norm = UI.el('normalise'); if (norm) norm.onclick = () => {
      const sum = weightSum();
      holdings = holdings.map(h => ({ ...h, weightPercent: h.weightPercent === null ? null : Number(h.weightPercent) * 100 / sum }));
      draw(); UI.toast('Displayed weights normalised to 100%. Stored weights unchanged.');
    };
  }

  async function reload() { holdings = await Api.portfolio.holdings(); draw(); }

  // ---- remove with confirmation and undo (AC 2.4) ----
  async function removeHolding(id) {
    const h = holdings.find(x => x.id === id);
    const ok = await UI.modal({ title: 'Remove ' + h.companyName + '?', confirmText: 'Remove', danger: true,
      bodyHtml: `<p>The holding leaves this portfolio and the graph, along with any supplier connected only to it. Its alerts are archived, not deleted.</p><p class="muted small" style="margin-top:8px">You can undo this for the rest of your session.</p>` });
    if (!ok) return;
    await Api.portfolio.removeHolding(id);
    await reload();
    UI.toast(`Removed ${h.ticker}.`, { undo: async () => { await Api.portfolio.undoRemove(id); await reload(); } });
  }

  // ---- progress (AC 2.5) ----
  async function watchProgress() {
    const host = UI.el('progressHost');
    const tick = async () => {
      const p = await Api.portfolio.progress();
      const done = p.completed + p.failed;
      host.innerHTML = p.total === 0 || p.done && done === 0 ? '' : `
        <div class="panel" style="margin-bottom:var(--space-4)"><div class="bd">
          <div class="row between"><span>${p.done ? (p.cancelled ? 'Analysis cancelled' : 'Analysis complete') : 'Analysing…'} <b class="mono">${done} of ${p.total}</b> holdings${p.failed ? ` · <span style="color:var(--status-danger)">${p.failed} failed</span>` : ''}</span>
            ${p.done ? '' : '<button class="btn sm" id="cancelBtn">Cancel</button>'}</div>
          <div class="progress" style="margin-top:8px"><div class="fill ${p.failed && p.done ? 'failed' : ''}" style="width:${p.total ? Math.round(done / p.total * 100) : 0}%"></div></div>
          ${p.cancelled ? '<div class="xs muted" style="margin-top:6px">Analysis incomplete: holdings not reached stay Pending and remain usable once analysed.</div>' : ''}
        </div></div>`;
      const c = UI.el('cancelBtn'); if (c) c.onclick = async () => { await Api.portfolio.cancel(); UI.toast('Cancelling after the current holding…'); };
      if (p.done) { clearInterval(progressTimer); progressTimer = null; await reload(); }
    };
    await tick();
    progressTimer = setInterval(tick, 1500);
  }

  // ---- upload: file → preview → (diff) → confirm (AC 2.1, 2.6) ----
  async function openUpload() {
    let file = null, preview = null, diff = null, excluded = new Set(), removeMissing = false;
    await UI.modal({
      title: 'Upload holdings CSV', confirmText: 'Confirm and analyse', wide: true,
      bodyHtml: `
        <div id="upStep1">
          <label class="dropzone" id="drop"><input type="file" id="file" accept=".csv,text/csv"><div><b>Choose a CSV</b> or drop it here</div>
            <div class="xs muted" style="margin-top:6px">Columns in any order: Ticker/Symbol, Name, Weight, Shares, MarketValue. Max 5 MB. JSON is not accepted.</div></label>
          <div id="upErr" class="banner danger" style="display:none;margin-top:12px"><span class="glyph">!</span><span class="msg"></span></div>
        </div>
        <div id="upStep2" style="display:none"></div>`,
      onOpen: (back) => {
        const drop = back.querySelector('#drop'), input = back.querySelector('#file');
        const okBtn = back.querySelector('[data-ok]'); okBtn.disabled = true;
        const showErr = (m) => { const e = back.querySelector('#upErr'); e.style.display = ''; e.querySelector('.msg').textContent = m; };
        const handle = async (f) => {
          if (!f) return;
          if (!/\.csv$/i.test(f.name)) { showErr('Only CSV files are accepted (' + f.name + ').'); return; }
          if (f.size > 5 * 1024 * 1024) { showErr('File exceeds the 5 MB limit.'); return; }
          file = f;
          try { preview = await Api.portfolio.previewUpload(f); } catch (e) { showErr(e.message); return; }
          excluded = new Set(preview.unrecognisedTickers);
          if (holdings.length) { try { diff = await Api.portfolio.diffUpload(preview.csvContentToEchoBackOnConfirm, [...excluded]); } catch { diff = null; } }
          renderPreview(back); okBtn.disabled = preview.rows.filter(r => !excluded.has(r.ticker)).length === 0;
        };
        input.onchange = () => handle(input.files[0]);
        drop.ondragover = (e) => { e.preventDefault(); drop.classList.add('over'); };
        drop.ondragleave = () => drop.classList.remove('over');
        drop.ondrop = (e) => { e.preventDefault(); drop.classList.remove('over'); handle(e.dataTransfer.files[0]); };
      },
      onConfirm: async (back) => {
        if (!preview) return false;
        const ok = back.querySelector('[data-ok]'); ok.disabled = true; ok.innerHTML = '<span class="spinner"></span> Analysing…';
        // The request runs the whole analysis (sequential per holding); progress is polled meanwhile.
        const p = Api.portfolio.confirmUpload(preview.csvContentToEchoBackOnConfirm, [...excluded], removeMissing);
        setTimeout(watchProgress, 400);
        try { await p; } catch (e) { UI.banner('danger', 'Upload failed', e.message, 'upload'); }
        await reload();
        return true;
      }
    });

    function renderPreview(back) {
      back.querySelector('#upStep1').style.display = 'none';
      const s2 = back.querySelector('#upStep2'); s2.style.display = '';
      const rows = preview.rows;
      const dups = preview.duplicateWarnings, skipped = preview.skippedNonEquityRows, unrec = preview.unrecognisedTickers;
      s2.innerHTML = `
        <p class="small muted" style="margin-bottom:10px">${esc(file.name)} · ${rows.length} equity row(s) parsed. Review, then confirm to start analysis. Nothing is saved until you confirm.</p>
        ${unrec.length ? `<div class="banner warning" style="margin-bottom:10px"><span class="glyph">!</span><span class="msg"><b>${unrec.length} ticker(s) not found in the SEC index</b>${unrec.map(esc).join(', ')}. They are excluded by default; tick a row to include it anyway (it will show as "No SEC filings"). To correct a ticker, edit the CSV and re-upload.</span></div>` : ''}
        ${dups.length ? `<div class="banner info" style="margin-bottom:10px"><span class="glyph">i</span><span class="msg"><b>Duplicate tickers merged</b>${dups.map(d => `${esc(d.ticker)} (rows ${d.rowNumbers.join(', ')})`).join('; ')}. Weights, shares and values were summed.</span></div>` : ''}
        ${skipped.length ? `<div class="banner info" style="margin-bottom:10px"><span class="glyph">i</span><span class="msg"><b>${skipped.length} non-equity row(s) skipped</b>${skipped.map(s => `${esc(s.ticker)}: ${esc(s.reason)}`).join('; ')}</span></div>` : ''}
        <div class="table-wrap" style="max-height:300px"><table><thead><tr><th>Include</th><th>Row</th><th>Ticker</th><th>Name (from CSV)</th><th>SEC name</th><th class="num">Weight %</th><th class="num">Shares</th></tr></thead><tbody>
          ${rows.map(r => `<tr class="${excluded.has(r.ticker) ? 'removed' : ''}"><td><input type="checkbox" data-t="${esc(r.ticker)}" ${excluded.has(r.ticker) ? '' : 'checked'} aria-label="Include ${esc(r.ticker)}"></td>
            <td class="num">${r.rowNumber}</td><td class="mono">${esc(r.ticker)}</td><td>${esc(r.companyName || '')}</td>
            <td>${r.recognisedBySec ? esc(r.secCompanyName) : '<span class="badge status-NO_SEC_FILINGS">not in SEC index</span>'}</td>
            <td class="num">${r.weightPercent === null ? '<span class="faint">blank</span>' : r.weightPercent}</td>
            <td class="num">${r.shares === null ? '<span class="faint">blank</span>' : r.shares}</td></tr>`).join('')}
        </tbody></table></div>
        ${diff ? `<div class="divider"></div><h3 style="margin-bottom:6px">Changes versus current holdings</h3>
          <div class="grid cols-3 small"><div><b>Added (${diff.added.length})</b><div class="muted">${diff.added.map(d => esc(d.ticker)).join(', ') || '—'}</div></div>
          <div><b>Removed (${diff.removed.length})</b><div class="muted">${diff.removed.map(d => esc(d.ticker)).join(', ') || '—'}</div></div>
          <div><b>Weight changed (${diff.weightChanged.length})</b><div class="muted">${diff.weightChanged.map(d => `${esc(d.ticker)} ${d.currentWeight}→${d.newWeight}`).join(', ') || '—'}</div></div></div>
          <label class="row small" style="margin-top:10px"><input type="checkbox" id="removeMissing"> Remove the ${diff.removed.length} holding(s) not in this file (undoable). Reviews, dismissed alerts and overrides on remaining holdings are kept either way.</label>` : ''}`;
      s2.querySelectorAll('input[data-t]').forEach(cb => cb.onchange = () => {
        if (cb.checked) excluded.delete(cb.dataset.t); else excluded.add(cb.dataset.t);
        cb.closest('tr').classList.toggle('removed', !cb.checked);
        back.querySelector('[data-ok]').disabled = rows.filter(r => !excluded.has(r.ticker)).length === 0;
      });
      const rm = s2.querySelector('#removeMissing'); if (rm) rm.onchange = () => removeMissing = rm.checked;
    }
  }

  // ---- add by ticker with search-as-you-type (AC 2.3) ----
  async function openAdd() {
    await UI.modal({
      title: 'Add a holding by ticker', confirmText: 'Add and analyse',
      bodyHtml: `<div class="field combo"><label for="tq">Ticker or company name</label><input type="search" id="tq" autocomplete="off" placeholder="e.g. TSM or Taiwan Semiconductor"><div class="options" id="topts" style="display:none"></div><span class="help" id="thelp">Searches the SEC ticker index.</span></div>`,
      onOpen: (back) => {
        const q = back.querySelector('#tq'), opts = back.querySelector('#topts'), ok = back.querySelector('[data-ok]');
        ok.disabled = true; let chosen = null, timer = null; q.focus();
        q.oninput = () => { chosen = null; ok.disabled = true; clearTimeout(timer); timer = setTimeout(async () => {
          const v = q.value.trim(); if (v.length < 1) { opts.style.display = 'none'; return; }
          const res = await Api.companies.search(v);
          if (!res.length) { opts.innerHTML = `<div class="muted">Not found in SEC index</div>`; opts.style.display = ''; return; }
          opts.innerHTML = res.map(r => `<div data-t="${esc(r.ticker)}"><span class="t">${esc(r.ticker)}</span><span>${esc(r.companyName)}</span></div>`).join('');
          opts.style.display = '';
          opts.querySelectorAll('[data-t]').forEach(d => d.onclick = () => { chosen = d.dataset.t; q.value = chosen; opts.style.display = 'none'; ok.disabled = false; });
        }, 180); };
        back.dataset.get = '1'; back._chosen = () => chosen || q.value.trim().toUpperCase();
      },
      onConfirm: async (back) => {
        const t = back._chosen(); if (!t) return false;
        const ok = back.querySelector('[data-ok]'); ok.disabled = true; ok.innerHTML = '<span class="spinner"></span> Analysing…';
        try { await Api.portfolio.addByTicker(t); }
        catch (e) { back.querySelector('#thelp').textContent = e.message; back.querySelector('#thelp').className = 'error'; ok.disabled = false; ok.textContent = 'Add and analyse'; return false; }
        await reload(); UI.toast(`Added ${t}. It stays highlighted until your next session.`); return true;
      }
    });
  }

  view.innerHTML = `
    <div class="page-head"><div><h1>Portfolio</h1><div class="sub">${holdings.length} holding(s) · ${holdings.filter(h => h.coverageStatus === 'COVERED').length} covered</div></div>
      <div class="actions"><button class="btn" id="addBtn">Add by ticker</button><button class="btn primary" id="uploadBtn">Upload CSV</button></div></div>
    <div id="progressHost"></div>
    <div id="holdingsHost"></div>
    <p class="xs faint" style="margin-top:var(--space-3)">Coverage: <b>Covered</b> = latest primary filing analysed · <b>Pending</b> = queued · <b>Failed</b> = shows the reason · <b>No SEC filings</b> = not on EDGAR (non-US companies without US filings are not covered).</p>`;
  UI.el('uploadBtn').onclick = openUpload;
  UI.el('addBtn').onclick = openAdd;
  draw();
  const p = await Api.portfolio.progress(); if (!p.done) watchProgress();
});
