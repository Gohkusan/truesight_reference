/*
 * Shared UI helpers: the components every page composes from. Rendering is plain
 * template strings + innerHTML, with esc() on every value that came from the server
 * or the user. No framework — the brief forbids a build step, and this app's screens
 * are lists, tables and panels, which plain DOM handles fine.
 */
const UI = (() => {
  function esc(v) {
    if (v === null || v === undefined) return '';
    return String(v).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  }
  const el = (id) => document.getElementById(id);
  const h = (html) => { const t = document.createElement('template'); t.innerHTML = html.trim(); return t.content.firstElementChild; };

  // ---- formatting ----
  function pct(v, digits = 1) { return v === null || v === undefined ? '—' : Number(v).toFixed(digits) + '%'; }
  function numOrDash(v) { return v === null || v === undefined ? '—' : String(v); }
  function when(iso) {
    if (!iso) return 'never';
    const d = new Date(iso), diff = (Date.now() - d.getTime()) / 1000;
    if (diff < 60) return 'just now';
    if (diff < 3600) return Math.floor(diff / 60) + ' min ago';
    if (diff < 86400) return Math.floor(diff / 3600) + ' h ago';
    if (diff < 7 * 86400) return Math.floor(diff / 86400) + ' d ago';
    return d.toISOString().slice(0, 10);
  }
  function dateOnly(iso) { return iso ? String(iso).slice(0, 10) : '—'; }
  function fullTime(iso) { return iso ? new Date(iso).toLocaleString() : '—'; }
  const SEV_LABEL = { UNKNOWN: 'Unknown', LOW: 'Low', MODERATE: 'Moderate', ELEVATED: 'Elevated', CRITICAL: 'Critical' };
  const STATUS_LABEL = { COVERED: 'Covered', PENDING: 'Pending', FAILED: 'Failed', NO_SEC_FILINGS: 'No SEC filings' };
  const STATUS_TIP = {
    NO_SEC_FILINGS: 'TrueSight uses SEC EDGAR filings. Non-US companies without US filings are not covered.',
    PENDING: 'Queued or being analysed.',
    FAILED: 'Analysis did not complete. The reason is shown next to the status.',
    COVERED: 'Latest primary filing fetched and analysed.',
  };

  // ---- components ----
  const sev = (s) => `<span class="badge sev sev-${esc(s || 'UNKNOWN')}">${esc(SEV_LABEL[s] || s || 'Unknown')}</span>`;
  const conf = (score, band) => score === null || score === undefined
    ? `<span class="badge">No confidence yet</span>`
    : `<span class="badge conf-${esc(band)}">${esc(band === 'HIGH' ? 'High' : band === 'MEDIUM' ? 'Medium' : 'Low')} <span class="mono">${esc(score)}</span></span>`;
  const coverage = (s, reason) => `<span class="badge status-${esc(s)} tooltip" data-tip="${esc(STATUS_TIP[s] || '')}${reason ? ' ' + esc(reason) : ''}">${esc(STATUS_LABEL[s] || s)}</span>`;
  const scorebar = (score, severity) => score === null || score === undefined
    ? `<span class="scorebar sev-UNKNOWN"><span class="n">?</span><span class="track"><span class="fill" style="width:0"></span></span></span>`
    : `<span class="scorebar sev-${esc(severity)}"><span class="n">${esc(score)}</span><span class="track"><span class="fill" style="width:${Number(score)}%"></span></span></span>`;
  const trend = (t, prev, now) => {
    const glyph = { RISING: '↑', FALLING: '↓', NEW: 'new', STABLE: '→', UNKNOWN: '' }[t] || '';
    const tip = t === 'NEW' ? 'First assessment' : prev !== null && prev !== undefined ? `Previous ${prev} → now ${now}` : '';
    return `<span class="trend ${esc(t)}" title="${esc(tip)}">${glyph}</span>`;
  };
  const empty = (title, text, actionHtml = '') => `<div class="empty"><h3>${esc(title)}</h3><p>${esc(text)}</p>${actionHtml}</div>`;
  const loading = (text = 'Loading…') => `<div class="loading"><span class="spinner"></span>${esc(text)}</div>`;
  const errorBox = (e, what = 'This section') => `<div class="banner danger"><span class="glyph">!</span><span class="msg"><b>${esc(what)} could not load</b>${esc(e && e.message ? e.message : e)}</span></div>`;
  const factors = (list) => list && list.length ? `<ul class="plain factors">${list.map(f => `<li>${esc(f)}</li>`).join('')}</ul>` : '<span class="faint">No factors recorded.</span>';

  // ---- banners (global, top of content) ----
  function banner(kind, title, text, id) {
    const host = el('banners'); if (!host) return;
    if (id && host.querySelector(`[data-id="${id}"]`)) host.querySelector(`[data-id="${id}"]`).remove();
    const glyph = { info: 'i', warning: '!', danger: '!', success: '✓' }[kind] || 'i';
    host.appendChild(h(`<div class="banner ${kind}" data-id="${esc(id || '')}" role="status"><span class="glyph">${glyph}</span><span class="msg"><b>${esc(title)}</b>${esc(text)}</span><button class="btn ghost sm" aria-label="Dismiss">×</button></div>`));
    host.lastElementChild.querySelector('button').onclick = (ev) => ev.target.closest('.banner').remove();
  }
  function clearBanner(id) { const host = el('banners'); const b = host && host.querySelector(`[data-id="${id}"]`); if (b) b.remove(); }

  // ---- toast with undo (AC 2.4, 10.4: reversible actions are one click with undo) ----
  function toast(text, { undo, timeoutMs = 8000 } = {}) {
    let host = el('toasts'); if (!host) { host = h('<div id="toasts"></div>'); document.body.appendChild(host); }
    const t = h(`<div class="toast" role="status">${esc(text)}${undo ? '<button type="button">Undo</button>' : ''}</div>`);
    host.appendChild(t);
    const timer = setTimeout(() => t.remove(), timeoutMs);
    if (undo) t.querySelector('button').onclick = async () => { clearTimeout(timer); t.remove(); await undo(); };
  }

  // ---- modal (confirmation names what will be lost: design principle 6) ----
  function modal({ title, bodyHtml, confirmText = 'Confirm', cancelText = 'Cancel', danger = false, wide = false, onConfirm, onOpen }) {
    return new Promise(resolve => {
      const back = h(`<div class="modal-backdrop" role="dialog" aria-modal="true" aria-label="${esc(title)}">
        <div class="modal${wide ? ' wide' : ''}"><div class="hd"><h3>${esc(title)}</h3><button class="btn ghost sm" data-x aria-label="Close">×</button></div>
        <div class="bd">${bodyHtml}</div>
        <div class="ft"><button class="btn" data-cancel>${esc(cancelText)}</button>${confirmText ? `<button class="btn ${danger ? 'danger' : 'primary'}" data-ok>${esc(confirmText)}</button>` : ''}</div></div></div>`);
      document.body.appendChild(back);
      const close = (v) => { back.remove(); document.removeEventListener('keydown', onKey); resolve(v); };
      const onKey = (e) => { if (e.key === 'Escape') close(false); };
      document.addEventListener('keydown', onKey);
      back.querySelector('[data-x]').onclick = () => close(false);
      back.querySelector('[data-cancel]').onclick = () => close(false);
      const ok = back.querySelector('[data-ok]');
      if (ok) ok.onclick = async () => { if (onConfirm) { const r = await onConfirm(back); if (r === false) return; close(r ?? true); } else close(true); };
      (ok || back.querySelector('[data-cancel]')).focus();
      if (onOpen) onOpen(back);
    });
  }

  // ---- sortable tables: click a header to sort; state kept per table ----
  function makeSortable(table, rows, render, initialKey) {
    let key = initialKey, dir = -1;
    const tbody = table.querySelector('tbody');
    const draw = () => {
      const sorted = [...rows].sort((a, b) => {
        const va = a[key], vb = b[key];
        if (va === vb) return 0;
        if (va === null || va === undefined) return 1;
        if (vb === null || vb === undefined) return -1;
        return (va < vb ? -1 : 1) * dir;
      });
      tbody.innerHTML = sorted.map(render).join('');
      table.querySelectorAll('th.sortable').forEach(th => th.setAttribute('aria-sort', th.dataset.key === key ? (dir > 0 ? 'ascending' : 'descending') : 'none'));
    };
    table.querySelectorAll('th.sortable').forEach(th => th.onclick = () => { if (key === th.dataset.key) dir = -dir; else { key = th.dataset.key; dir = -1; } draw(); });
    draw();
    return draw;
  }

  return { esc, el, h, pct, numOrDash, when, dateOnly, fullTime, sev, conf, coverage, scorebar, trend, empty, loading, errorBox, factors, banner, clearBanner, toast, modal, makeSortable, SEV_LABEL, STATUS_LABEL };
})();
