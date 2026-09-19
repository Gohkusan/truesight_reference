/*
 * Shell: hash router, session bootstrap, active-portfolio switcher, and the global
 * status banners (AC 9.2 / 10.1). Pages register themselves with App.page(name, fn);
 * fn(viewElement, params) renders into the view. One layout for every screen.
 */
const App = (() => {
  const pages = {};
  let portfolios = [];
  let statusTimer = null;

  function page(name, render) { pages[name] = render; }

  function route() {
    const hash = location.hash.replace(/^#\/?/, '') || 'dashboard';
    const [name, ...rest] = hash.split('/');
    return { name, params: rest };
  }

  async function render() {
    const { name, params } = route();
    document.querySelectorAll('.nav a[data-route]').forEach(a => {
      if (a.dataset.route === name) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current');
    });
    const view = UI.el('view');
    const fn = pages[name] || pages['dashboard'];
    view.className = name === 'network' ? 'page full' : 'page';
    view.innerHTML = UI.loading();
    try {
      await fn(view, params);
    } catch (e) {
      view.innerHTML = UI.errorBox(e, 'This page');
    }
    document.title = 'TrueSight — ' + name.charAt(0).toUpperCase() + name.slice(1);
  }

  // ---- portfolios (AC 2.7: active portfolio in the header, everything scoped to it) ----
  async function loadPortfolios() {
    portfolios = await Api.portfolios.list();
    if (!portfolios.length) {
      const created = await Api.portfolios.create('My Portfolio');
      portfolios = [created];
    }
    if (!Api.portfolioId() || !portfolios.some(p => p.id === Api.portfolioId())) Api.setPortfolioId(portfolios[0].id);
    const sel = UI.el('portfolioSelect');
    sel.innerHTML = portfolios.map(p => `<option value="${p.id}" ${p.id === Api.portfolioId() ? 'selected' : ''}>${UI.esc(p.name)}</option>`).join('');
  }
  function activePortfolio() { return portfolios.find(p => p.id === Api.portfolioId()); }

  async function managePortfolios() {
    const list = () => portfolios.map(p => `
      <div class="row between" style="padding:6px 0;border-bottom:1px solid var(--line)">
        <span>${UI.esc(p.name)}${p.id === Api.portfolioId() ? ' <span class="badge">active</span>' : ''}</span>
        <span class="row">
          <button class="btn sm" data-rename="${p.id}">Rename</button>
          <button class="btn sm danger" data-del="${p.id}" ${portfolios.length === 1 ? 'disabled title="Keep at least one portfolio"' : ''}>Delete</button>
        </span></div>`).join('');
    await UI.modal({
      title: 'Portfolios', confirmText: '', cancelText: 'Close',
      bodyHtml: `<div id="pfList">${list()}</div>
        <form id="pfCreate" class="row" style="margin-top:var(--space-4)"><input type="text" id="pfName" placeholder="New portfolio name" required style="flex:1"><button class="btn primary" type="submit">Create</button></form>`,
      onOpen: (back) => {
        const refresh = async () => { await loadPortfolios(); back.querySelector('#pfList').innerHTML = list(); bind(); };
        const bind = () => {
          back.querySelectorAll('[data-rename]').forEach(b => b.onclick = async () => {
            const p = portfolios.find(x => x.id === Number(b.dataset.rename));
            const name = prompt('Rename portfolio', p.name);
            if (name && name.trim() && name !== p.name) { await Api.portfolios.rename(p.id, name.trim()); await refresh(); render(); }
          });
          back.querySelectorAll('[data-del]').forEach(b => b.onclick = async () => {
            const p = portfolios.find(x => x.id === Number(b.dataset.del));
            // Design principle 6: deleting a portfolio is irreversible, so the dialog names what goes.
            const ok = await UI.modal({ title: 'Delete "' + p.name + '"?', danger: true, confirmText: 'Delete permanently',
              bodyHtml: `<p>This deletes the portfolio, all of its holdings, its risk history and its alerts. Shared company data used by other portfolios is not affected.</p><p class="muted small" style="margin-top:8px">This cannot be undone.</p>` });
            if (ok) { await Api.portfolios.remove(p.id); if (Api.portfolioId() === p.id) localStorage.removeItem('truesight.portfolioId'); await refresh(); render(); }
          });
        };
        bind();
        back.querySelector('#pfCreate').onsubmit = async (e) => {
          e.preventDefault();
          const name = back.querySelector('#pfName').value.trim(); if (!name) return;
          const created = await Api.portfolios.create(name);
          Api.setPortfolioId(created.id); await refresh(); back.querySelector('#pfName').value = ''; render();
        };
      }
    });
  }

  // ---- status banners: distinct per failure kind (AC 9.2), previous results stay visible ----
  async function refreshStatus() {
    try {
      const s = await Api.status();
      const badge = UI.el('llmStatus');
      const llm = s.llm;
      if (!llm.configured) {
        badge.innerHTML = '<span class="dot" style="background:var(--status-danger)"></span>AI not configured';
        UI.banner('warning', 'AI service is not configured',
          'No Gemini API key is set on the server (GEMINI_API_KEY). Filings can be fetched, but no relationships can be extracted until it is configured. Previous results remain visible.', 'llm');
      } else if (llm.lastFailureKind) {
        const kind = llm.lastFailureKind;
        const titles = {
          RATE_LIMITED: 'AI service rate limit reached',
          BUDGET_EXHAUSTED: 'AI service quota exhausted',
          MODEL_NOT_FOUND: 'Configured AI model is not available',
          TEMPORARILY_UNAVAILABLE: 'AI service temporarily overloaded',
          NOT_CONFIGURED: 'AI service rejected the API key',
          ERROR: 'AI service error',
        };
        const level = kind === 'BUDGET_EXHAUSTED' || kind === 'NOT_CONFIGURED' || kind === 'MODEL_NOT_FOUND' ? 'danger' : 'warning';
        badge.innerHTML = `<span class="dot" style="background:var(--status-${level === 'danger' ? 'danger' : 'warning'})"></span>${UI.esc(titles[kind] || kind)}`;
        UI.banner(level, titles[kind] || kind,
          (llm.lastFailureMessage || '') + ' Last seen ' + UI.when(llm.lastFailureAt) + '. Results already analysed are unaffected; new analyses will fail until this clears.', 'llm');
      } else {
        badge.innerHTML = '<span class="dot" style="background:var(--status-success)"></span>AI ready';
        UI.clearBanner('llm');
      }
      if (!s.secTickerIndexLoaded) {
        UI.banner('danger', 'SEC ticker index unavailable', 'Ticker lookup and CSV upload will not work until the index loads. Check the server log and network access to sec.gov.', 'sec');
      } else UI.clearBanner('sec');
    } catch (e) { /* status is best-effort; the page's own errors will surface */ }
  }

  async function start() {
    if (!Api.token()) { location.href = '/login.html'; return; }
    const u = Api.user();
    UI.el('userLabel').textContent = u ? (u.displayName || u.email) : '';
    UI.el('logout').onclick = () => Api.logout();
    UI.el('managePortfolios').onclick = managePortfolios;
    UI.el('portfolioSelect').onchange = (e) => { Api.setPortfolioId(Number(e.target.value)); render(); };
    try { await loadPortfolios(); } catch (e) { if (e.status !== 401) UI.el('view').innerHTML = UI.errorBox(e, 'Portfolios'); return; }
    window.addEventListener('hashchange', render);
    await refreshStatus();
    statusTimer = setInterval(refreshStatus, 30000);
    render();
  }

  return { page, start, render, activePortfolio, refreshStatus, portfolios: () => portfolios };
})();
