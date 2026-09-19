/*
 * The one place the frontend talks to the backend. Every request carries the JWT
 * (AC 1.2: "token stored and sent on every request"); a 401 means the token is
 * missing or expired, so the user is sent to login (AC 1.2: "expired token
 * redirects to login"). Errors are normalised to {status, message, fieldErrors} —
 * the shape GlobalExceptionHandler returns — so every screen renders failures the
 * same way (AC 10.1).
 */
const Api = (() => {
  const TOKEN_KEY = 'truesight.token';
  const USER_KEY = 'truesight.user';
  const PORTFOLIO_KEY = 'truesight.portfolioId';

  function token() { return localStorage.getItem(TOKEN_KEY); }
  function setSession(auth) {
    localStorage.setItem(TOKEN_KEY, auth.token);
    localStorage.setItem(USER_KEY, JSON.stringify({ id: auth.userId, email: auth.email, displayName: auth.displayName }));
  }
  function user() { try { return JSON.parse(localStorage.getItem(USER_KEY)); } catch { return null; } }
  function logout() {
    // Stateless JWT: logging out is discarding the token (see AuthController).
    localStorage.removeItem(TOKEN_KEY); localStorage.removeItem(USER_KEY); localStorage.removeItem(PORTFOLIO_KEY);
    location.href = '/login.html';
  }
  function portfolioId() { const v = localStorage.getItem(PORTFOLIO_KEY); return v ? Number(v) : null; }
  function setPortfolioId(id) { localStorage.setItem(PORTFOLIO_KEY, String(id)); }

  async function request(method, path, body, opts = {}) {
    const headers = {};
    if (token()) headers['Authorization'] = 'Bearer ' + token();
    let payload = body;
    if (body && !(body instanceof FormData)) { headers['Content-Type'] = 'application/json'; payload = JSON.stringify(body); }
    let res;
    try {
      res = await fetch(path, { method, headers, body: payload });
    } catch (e) {
      throw { status: 0, message: 'Cannot reach the server. Is TrueSight running on port 8085?' };
    }
    if (res.status === 401 && !opts.noRedirect) { logout(); throw { status: 401, message: 'Session expired' }; }
    if (res.status === 204) return null;
    const ct = res.headers.get('content-type') || '';
    if (opts.raw) return res;
    const data = ct.includes('application/json') ? await res.json() : await res.text();
    if (!res.ok) {
      const err = typeof data === 'object' ? data : { message: String(data) };
      throw { status: res.status, message: err.message || res.statusText, fieldErrors: err.fieldErrors || null };
    }
    return data;
  }

  const P = () => '/api/portfolios/' + portfolioId();

  return {
    token, user, setSession, logout, portfolioId, setPortfolioId,
    get: (p, o) => request('GET', p, null, o),
    post: (p, b, o) => request('POST', p, b, o),
    put: (p, b, o) => request('PUT', p, b, o),
    del: (p, o) => request('DELETE', p, null, o),
    // Convenience wrappers for the active portfolio
    portfolio: {
      dashboard: () => request('GET', P() + '/dashboard'),
      holdings: () => request('GET', P() + '/holdings'),
      previewUpload: (file) => { const f = new FormData(); f.append('file', file); return request('POST', P() + '/holdings/upload/preview', f); },
      diffUpload: (csvContent, excludedTickers) => request('POST', P() + '/holdings/upload/diff', { csvContent, excludedTickers }),
      confirmUpload: (csvContent, excludedTickers, removeMissing) => request('POST', P() + '/holdings/upload/confirm', { csvContent, excludedTickers, removeMissing }),
      addByTicker: (ticker) => request('POST', P() + '/holdings/add-by-ticker', { ticker }),
      removeHolding: (id) => request('DELETE', P() + '/holdings/' + id),
      undoRemove: (id) => request('PUT', P() + '/holdings/' + id + '/undo-remove'),
      progress: () => request('GET', P() + '/holdings/analysis/progress'),
      cancel: () => request('POST', P() + '/holdings/analysis/cancel'),
      graph: (includeRejected) => request('GET', P() + '/graph?includeRejected=' + (includeRejected ? 'true' : 'false')),
      expand: (companyId) => request('POST', P() + '/graph/expand/' + companyId),
      relationship: (id) => request('GET', P() + '/relationships/' + id),
      review: (id, status, note) => request('PUT', P() + '/relationships/' + id + '/review', { status, note }),
      riskSummary: () => request('GET', P() + '/risks/summary'),
      risks: (q) => request('GET', P() + '/risks' + (q || '')),
      concentration: () => request('GET', P() + '/risks/concentration'),
      alerts: (q) => request('GET', P() + '/alerts' + (q || '')),
      alertReview: (id) => request('PUT', P() + '/alerts/' + id + '/review'),
      alertDismiss: (id, reason) => request('PUT', P() + '/alerts/' + id + '/dismiss', { reason }),
      alertReopen: (id) => request('PUT', P() + '/alerts/' + id + '/reopen'),
      newsRefresh: () => request('POST', P() + '/news/refresh'),
      refreshNow: () => request('POST', P() + '/refresh'),
      schedule: () => request('GET', P() + '/refresh/schedule'),
      recommendations: () => request('GET', P() + '/recommendations'),
      simulate: (req) => request('POST', P() + '/simulations', req),
      compare: (a, b) => request('POST', P() + '/simulations/compare', { a, b }),
      ask: (question) => request('POST', P() + '/ask', { question }),
      exportUrl: (kind) => P() + '/export/' + kind,
    },
    portfolios: {
      list: () => request('GET', '/api/portfolios'),
      create: (name) => request('POST', '/api/portfolios', { name }),
      rename: (id, name) => request('PUT', '/api/portfolios/' + id, { name }),
      remove: (id) => request('DELETE', '/api/portfolios/' + id),
    },
    companies: { search: (q) => request('GET', '/api/companies/search?q=' + encodeURIComponent(q)) },
    settings: {
      profile: () => request('GET', '/api/settings/profile'),
      updateProfile: (b) => request('PUT', '/api/settings/profile', b),
      preferences: () => request('GET', '/api/settings/preferences'),
      updatePreferences: (b) => request('PUT', '/api/settings/preferences', b),
      watchlist: () => request('GET', '/api/settings/watchlist'),
      addWatch: (ticker) => request('POST', '/api/settings/watchlist', { ticker }),
      removeWatch: (id) => request('DELETE', '/api/settings/watchlist/' + id),
    },
    audit: { list: (q) => request('GET', '/api/audit' + (q || '')) },
    status: () => request('GET', '/api/status'),
    auth: {
      login: (email, password) => request('POST', '/api/auth/login', { email, password }, { noRedirect: true }),
      register: (b) => request('POST', '/api/auth/register', b, { noRedirect: true }),
    },
  };
})();
