/* AC 1.4, 10.3, 8.1: profile, alert preferences, watchlist, refresh now + schedule. No LLM key field exists here, by design. */
App.page('settings', async (view) => {
  const { esc } = UI;
  const [profile, prefs, watch, schedule] = await Promise.all([
    Api.settings.profile(), Api.settings.preferences(), Api.settings.watchlist(), Api.portfolio.schedule()]);
  const EVENT_TYPES = ['OPERATIONAL_SHUTDOWN', 'GEOPOLITICAL_EXPORT_CURB', 'COMPONENT_SHORTAGE', 'CAPACITY_EXPANSION', 'DEMAND_CATALYST', 'OTHER'];

  view.innerHTML = `
    <div class="page-head"><div><h1>Settings</h1><div class="sub">Profile, alert preferences, watchlist, refresh.</div></div></div>
    <div class="grid cols-2">
      <div class="panel"><div class="hd"><h3>Profile</h3></div><div class="bd">
        <form id="profileForm" class="stack">
          <div class="field"><label>Email</label><input type="email" value="${esc(profile.email)}" disabled></div>
          <div class="field"><label for="displayName">Name</label><input id="displayName" type="text" value="${esc(profile.displayName || '')}"></div>
          <div class="field"><label for="firm">Firm</label><input id="firm" type="text" value="${esc(profile.firm || '')}"></div>
          <div class="field"><label for="alertEmail">Alert email</label><input id="alertEmail" type="email" value="${esc(profile.alertEmail || '')}"><span class="help">Where digests would be sent. Email delivery is not wired in this reference build; the address is stored.</span></div>
          <div class="row"><button class="btn primary" type="submit">Save profile</button><span class="small muted" id="profileMsg"></span></div>
        </form></div></div>

      <div class="panel"><div class="hd"><h3>Alert preferences</h3></div><div class="bd">
        <form id="prefForm" class="stack">
          <div class="field"><label for="minSev">Minimum alert severity shown</label>
            <select id="minSev">${['LOW', 'MODERATE', 'ELEVATED', 'CRITICAL'].map(s => `<option value="${s}" ${prefs.minimumAlertSeverity === s ? 'selected' : ''}>${UI.SEV_LABEL[s]}</option>`).join('')}</select>
            <span class="help">Alerts below this are still stored; raising the threshold later loses nothing.</span></div>
          <div class="field"><label>Event types of interest</label>
            <div class="chips" id="eventChips">${EVENT_TYPES.map(t => `<button type="button" class="chip" data-t="${t}" aria-pressed="${prefs.eventTypes.includes(t) || prefs.eventTypes.length === 0}">${esc(t.toLowerCase().replace(/_/g, ' '))}</button>`).join('')}</div>
            <span class="help">All selected = no filtering by type.</span></div>
          <div class="row"><button class="btn primary" type="submit">Save preferences</button><span class="small muted" id="prefMsg"></span></div>
        </form></div></div>

      <div class="panel"><div class="hd"><h3>Watchlist</h3><span class="small muted">companies you watch without holding</span></div><div class="bd">
        <form id="watchForm" class="row" style="margin-bottom:10px"><input type="text" id="watchTicker" placeholder="Ticker, e.g. TSM" style="flex:1"><button class="btn" type="submit">Add</button></form>
        <div id="watchList">${watch.length ? watch.map(w => `<div class="row between" style="padding:6px 0;border-bottom:1px solid var(--line)"><span><span class="mono">${esc(w.ticker || '—')}</span> ${esc(w.name)}</span><button class="btn ghost sm" data-unwatch="${w.id}">Remove</button></div>`).join('') : '<p class="muted small">Nothing watched yet.</p>'}</div>
      </div></div>

      <div class="panel"><div class="hd"><h3>Refresh</h3></div><div class="bd stack">
        <p class="small muted">${esc(schedule.description)} (<span class="mono">${esc(schedule.cron)}</span>). Last automatic run: ${schedule.lastScheduledRunAt ? UI.fullTime(schedule.lastScheduledRunAt) : 'not yet in this server session'}.</p>
        <p class="small muted">Only holdings whose primary SEC filing has a new accession number are re-analysed; unchanged filings use cached results. Relationships the new filing no longer mentions are flagged, not deleted. A failed refresh keeps previous results.</p>
        <div class="row"><button class="btn primary" id="refreshNow">Refresh now</button><span class="small muted" id="refreshMsg"></span></div>
      </div></div>
    </div>`;

  UI.el('profileForm').onsubmit = async (e) => {
    e.preventDefault();
    try { await Api.settings.updateProfile({ displayName: UI.el('displayName').value, firm: UI.el('firm').value, alertEmail: UI.el('alertEmail').value }); UI.el('profileMsg').textContent = 'Saved.'; }
    catch (err) { UI.el('profileMsg').textContent = err.message; }
  };
  UI.el('eventChips').onclick = (e) => { const c = e.target.closest('.chip'); if (c) c.setAttribute('aria-pressed', c.getAttribute('aria-pressed') !== 'true'); };
  UI.el('prefForm').onsubmit = async (e) => {
    e.preventDefault();
    const types = [...view.querySelectorAll('#eventChips .chip[aria-pressed="true"]')].map(c => c.dataset.t);
    try { await Api.settings.updatePreferences({ minimumAlertSeverity: UI.el('minSev').value, eventTypes: types.length === EVENT_TYPES.length ? [] : types }); UI.el('prefMsg').textContent = 'Saved.'; }
    catch (err) { UI.el('prefMsg').textContent = err.message; }
  };
  UI.el('watchForm').onsubmit = async (e) => {
    e.preventDefault(); const t = UI.el('watchTicker').value.trim(); if (!t) return;
    try { await Api.settings.addWatch(t); App.render(); } catch (err) { UI.banner('danger', 'Could not add to watchlist', err.message, 'watch'); }
  };
  view.querySelectorAll('[data-unwatch]').forEach(b => b.onclick = async () => { await Api.settings.removeWatch(Number(b.dataset.unwatch)); App.render(); });
  UI.el('refreshNow').onclick = async () => {
    const b = UI.el('refreshNow'); b.disabled = true; b.innerHTML = '<span class="spinner"></span> Refreshing…';
    try {
      const r = await Api.portfolio.refreshNow();
      UI.el('refreshMsg').textContent = `${r.filings.reanalysed} re-analysed, ${r.filings.unchanged} unchanged, ${r.filings.failed} failed (previous results kept), ${r.filings.relationshipsMarkedNoLongerDisclosed} flagged; news: ${r.news.alertsCreated} new alert(s).`;
      App.refreshStatus();
    } catch (err) { UI.el('refreshMsg').textContent = err.message; }
    b.disabled = false; b.textContent = 'Refresh now';
  };
});
