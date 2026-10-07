// =============================================================================
// STORAGE HELPERS
// =============================================================================

function safeParse(key, defaultVal) {
    try {
        const val = localStorage.getItem(key);
        return val ? JSON.parse(val) : defaultVal;
    } catch (e) {
        localStorage.removeItem(key);
        return defaultVal;
    }
}

// Remove lone Unicode surrogates from a string.
// Modern Chrome throws TypeError in JSON.stringify when a string contains an unpaired
// surrogate (e.g. \uD83D without a following \uDC00-\uDFFF). This can happen when the
// server emits 🟢 as separate \uXXXX JSON escapes for emoji outside the BMP.
function stripLoneSurrogates(str) {
    if (!str || typeof str !== 'string') return str || '';
    let out = '';
    for (let i = 0; i < str.length; i++) {
        const code = str.charCodeAt(i);
        if (code >= 0xD800 && code <= 0xDBFF) {           // high surrogate
            const next = i + 1 < str.length ? str.charCodeAt(i + 1) : 0;
            if (next >= 0xDC00 && next <= 0xDFFF) {
                out += str[i] + str[i + 1];                // valid pair — keep both
                i++;
            }
            // else: lone high surrogate — drop it
        } else if (code >= 0xDC00 && code <= 0xDFFF) {
            // lone low surrogate — drop it
        } else {
            out += str[i];
        }
    }
    return out;
}

// =============================================================================
// THEME  (dark / light — applied immediately to avoid flash)
// =============================================================================

(function () {
    var saved = localStorage.getItem('gp_theme') || 'dark';
    document.documentElement.setAttribute('data-theme', saved);
})();

function applyThemeLabel() {
    var btn = document.getElementById('themeToggleBtn');
    if (!btn) return;
    var isDark = document.documentElement.getAttribute('data-theme') === 'dark';
    btn.textContent = isDark ? '☀️' : '🌙';
    btn.title = isDark ? 'Switch to Light Mode' : 'Switch to Dark Mode';
    btn.dataset.tip = isDark ? 'Light Mode' : 'Dark Mode';
}

function togglePinVisibility() {
    var inp = document.getElementById('adminPinInput');
    if (!inp) return;
    inp.type = inp.type === 'password' ? 'text' : 'password';
}

function toggleTheme() {
    var current = document.documentElement.getAttribute('data-theme') || 'light';
    var next = current === 'dark' ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', next);
    localStorage.setItem('gp_theme', next);
    applyThemeLabel();
    // Persist to server so theme loads correctly in any browser/incognito session
    if (CURRENT_USER_ID) {
        fetch('/api/settings', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userId: CURRENT_USER_ID, theme: next })
        }).catch(function() {});
    }
}

// =============================================================================
// USER IDENTITY  (username chosen at PIN setup — survives browser cache clear)
// =============================================================================

let CURRENT_USER_ID    = localStorage.getItem('gp_user_id') || null;
let USER_EMAIL         = '';
let CF_MODE            = false;
let IS_PERMANENT_ADMIN = false;

// --- SSO logout ---
function logout() {
    ['gp_user_id', 'gp_sessions', 'gp_current_session', 'gp_history', 'gp_config', 'gp_theme']
        .forEach(k => localStorage.removeItem(k));
    Object.keys(localStorage).filter(k => k.startsWith('gp_chat_ui_')).forEach(k => localStorage.removeItem(k));
    window.location.href = '/logout';
}

// =============================================================================
// ADMIN — global pre-training prompt + allowlist management
// =============================================================================

async function hashPin(pin) {
    const buf = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(pin));
    return Array.from(new Uint8Array(buf)).map(b => b.toString(16).padStart(2, '0')).join('');
}

let adminPinHashInSession = null;
let _roles = [];          // loaded after PIN verify and on Roles tab open
let _userRolesCache = {}; // email → role, loaded for the allowlist display

function openAdminModal() {
    adminPinHashInSession = null;
    document.getElementById('adminModal').style.display = 'flex';
    document.getElementById('adminAuthSection').style.display = 'block';
    document.getElementById('adminEditorSection').style.display = 'none';
    document.getElementById('adminPinInput').value = '';
    document.getElementById('adminAuthError').style.display = 'none';
    const btn = document.getElementById('adminAuthBtn');
    btn.textContent = 'Verify & Enter';
    btn.disabled    = false;
    setTimeout(() => document.getElementById('adminPinInput').focus(), 100);
}

function closeAdminModal() {
    document.getElementById('adminModal').style.display = 'none';
}

function switchAdminTab(tab) {
    ['Prompt', 'Access', 'Roles', 'Audit', 'Usage'].forEach(t => {
        const panel = document.getElementById('adminPanel' + t);
        const btn   = document.getElementById('adminTab'   + t);
        if (panel) panel.style.display = t === tab ? 'block' : 'none';
        if (btn) {
            btn.style.borderBottomColor = t === tab ? 'var(--primary-color)' : 'transparent';
            btn.style.color      = t === tab ? 'var(--primary-color)' : 'var(--muted-text)';
            btn.style.fontWeight = t === tab ? '600' : 'normal';
        }
    });
    if (tab === 'Access') {
        populateRoleDropdowns();
    } else if (tab === 'Roles') {
        loadRolesTab();
    } else if (tab === 'Audit') {
        loadAuditLog(0);
        loadActiveUsers();
    } else if (tab === 'Usage') {
        loadUsageTab();
    }
    // Always clear previous interval, restart only when on Audit tab
    if (_activeUsersTimer) { clearInterval(_activeUsersTimer); _activeUsersTimer = null; }
    if (tab === 'Audit') {
        _activeUsersTimer = setInterval(loadActiveUsers, 60_000);
    }
}

// =============================================================================
// AUDIT LOG
// =============================================================================
let auditCurrentPage = 0;
let auditRowsCache   = [];
let auditSortCol     = 'ts';
let auditSortDir     = 'desc';

const AUDIT_ACTION_STYLE = {
    LOGIN:  'background:#1d4ed8;color:#fff;',
    LOGOUT: 'background:#b45309;color:#fff;',
    QUERY:  'background:#059669;color:#fff;'
};

async function loadAuditLog(page) {
    auditCurrentPage = page;
    const from  = (document.getElementById('auditFrom')  || {}).value || '';
    const to    = (document.getElementById('auditTo')    || {}).value || '';
    const email = (document.getElementById('auditEmail') || {}).value || '';
    const tbody    = document.getElementById('auditTableBody');
    const pageInfo = document.getElementById('auditPageInfo');
    const prevBtn  = document.getElementById('auditPrevBtn');
    const nextBtn  = document.getElementById('auditNextBtn');

    if (tbody) tbody.innerHTML = '<tr><td colspan="3" style="padding:16px;text-align:center;color:var(--muted-text);">Loading…</td></tr>';

    try {
        const params = new URLSearchParams({ page, size: 50 });
        if (from)  params.append('from',  from);
        if (to)    params.append('to',    to);
        if (email) params.append('email', email);
        const res  = await fetch('/api/admin/audit?' + params);
        if (res.status === 403) {
            if (tbody) tbody.innerHTML = '<tr><td colspan="3" style="padding:16px;text-align:center;color:#ef4444;">Access denied — permanent admins only</td></tr>';
            return;
        }
        const data = await res.json();
        if (!data.rows || data.rows.length === 0) {
            auditRowsCache = [];
            if (tbody)    tbody.innerHTML = '<tr><td colspan="3" style="padding:16px;text-align:center;color:var(--muted-text);">No records found</td></tr>';
            if (pageInfo) pageInfo.textContent = 'No records';
            if (prevBtn)  prevBtn.disabled = true;
            if (nextBtn)  nextBtn.disabled = true;
            return;
        }

        auditRowsCache = data.rows;
        renderAuditRows();

        const total      = data.total      || 0;
        const totalPages = data.totalPages || 1;
        const curPage    = data.page       || 0;
        if (pageInfo) pageInfo.textContent = `Page ${curPage + 1} of ${totalPages} (${total} records)`;
        if (prevBtn)  prevBtn.disabled = curPage === 0;
        if (nextBtn)  nextBtn.disabled = curPage >= totalPages - 1;

    } catch (e) {
        if (tbody) tbody.innerHTML = '<tr><td colspan="3" style="padding:16px;text-align:center;color:#ef4444;">Error loading audit log</td></tr>';
    }
}

function renderAuditRows() {
    const tbody = document.getElementById('auditTableBody');
    if (!tbody || !auditRowsCache.length) return;

    const sorted = [...auditRowsCache].sort((a, b) => {
        let va = a[auditSortCol] || '', vb = b[auditSortCol] || '';
        if (auditSortCol === 'ts') { va = new Date(va); vb = new Date(vb); }
        else { va = va.toLowerCase(); vb = vb.toLowerCase(); }
        if (va < vb) return auditSortDir === 'asc' ? -1 :  1;
        if (va > vb) return auditSortDir === 'asc' ?  1 : -1;
        return 0;
    });

    tbody.innerHTML = sorted.map(r => {
        const ts     = r.ts ? new Date(r.ts).toLocaleString() : '—';
        const action = r.action || '';
        const style  = AUDIT_ACTION_STYLE[action] || 'background:#f1f5f9;color:#475569;';
        return `<tr>
            <td style="padding:7px 10px;border-bottom:1px solid var(--border-subtle);white-space:nowrap;font-size:0.82em;color:var(--muted-text);">${ts}</td>
            <td style="padding:7px 10px;border-bottom:1px solid var(--border-subtle);font-size:0.85em;">${r.email || ''}</td>
            <td style="padding:7px 10px;border-bottom:1px solid var(--border-subtle);"><span style="border-radius:3px;padding:2px 8px;font-size:0.82em;font-weight:600;${style}">${action}</span></td>
        </tr>`;
    }).join('');

    // Update sort indicators
    ['ts', 'email', 'action'].forEach(col => {
        const el = document.getElementById('auditSort_' + col);
        if (el) el.textContent = col === auditSortCol ? (auditSortDir === 'asc' ? ' ▲' : ' ▼') : '';
    });
}

function sortAuditBy(col) {
    if (auditSortCol === col) {
        auditSortDir = auditSortDir === 'asc' ? 'desc' : 'asc';
    } else {
        auditSortCol = col;
        auditSortDir = col === 'ts' ? 'desc' : 'asc';
    }
    renderAuditRows();
}

function auditChangePage(delta) {
    loadAuditLog(auditCurrentPage + delta);
}

let _activeUsersTimer = null;

async function loadActiveUsers() {
    const minutesSel = document.getElementById('activeUsersWindow');
    const minutes    = minutesSel ? minutesSel.value : 30;
    const badge      = document.getElementById('activeUsersBadge');
    const list       = document.getElementById('activeUsersList');
    if (!list) return;

    try {
        const res  = await fetch('/api/admin/active-users?minutes=' + minutes);
        if (!res.ok) { if (list) list.textContent = 'Access denied'; return; }
        const data = await res.json();
        const users = data.users || [];

        if (badge) {
            badge.textContent = users.length;
            badge.style.background = users.length > 0 ? '#dcfce7' : '#f1f5f9';
            badge.style.color      = users.length > 0 ? '#16a34a' : '#64748b';
        }

        if (users.length === 0) {
            list.innerHTML = '<span style="color:var(--muted-text);">No active users in this window</span>';
        } else {
            list.innerHTML = users.map(u => {
                const ago        = u.last_seen ? timeSince(new Date(u.last_seen)) : '—';
                const queries    = u.query_count || 0;
                const lastAction = u.last_action || '';
                const dotColor   = queries > 0 ? '#22c55e' : '#f59e0b'; // green = querying, amber = logged in only
                const label      = queries > 0 ? `${queries} quer${queries === 1 ? 'y' : 'ies'}` : 'logged in';
                return `<div style="display:flex;align-items:center;gap:8px;padding:4px 0;border-bottom:1px solid var(--border-subtle);">
                    <span style="width:8px;height:8px;border-radius:50%;background:${dotColor};flex-shrink:0;"></span>
                    <span style="flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">${u.email || ''}</span>
                    <span style="color:var(--muted-text);white-space:nowrap;font-size:0.9em;">${label} · ${ago}</span>
                </div>`;
            }).join('');
        }
    } catch (e) {
        if (list) list.textContent = 'Error loading active users';
    }
}

function timeSince(date) {
    const sec = Math.floor((Date.now() - date) / 1000);
    if (sec < 60)  return sec + 's ago';
    if (sec < 3600) return Math.floor(sec / 60) + 'm ago';
    return Math.floor(sec / 3600) + 'h ago';
}

// =============================================================================
// MODEL USAGE TAB
// =============================================================================
let _usageChartTokens = null;
let _usageChartCalls  = null;

async function loadUsageTab() {
    const daysSel  = document.getElementById('usageDays');
    const days     = daysSel ? daysSel.value : 7;
    const bodyEl   = document.getElementById('usageTableBody');
    const noData   = document.getElementById('usageNoData');
    const daysLbl  = document.getElementById('usageDaysLabel');
    if (!bodyEl) return;
    if (daysLbl) daysLbl.textContent = days;
    bodyEl.innerHTML = '<tr><td colspan="6" style="padding:16px;text-align:center;color:#6366f1;">Loading…</td></tr>';
    if (noData) noData.style.display = 'none';

    // Destroy old charts while data loads
    if (_usageChartTokens) { _usageChartTokens.destroy(); _usageChartTokens = null; }
    if (_usageChartCalls)  { _usageChartCalls.destroy();  _usageChartCalls  = null; }

    try {
        const res  = await fetch('/api/admin/usage?days=' + days);
        if (!res.ok) {
            bodyEl.innerHTML = '<tr><td colspan="6" style="padding:16px;text-align:center;color:#dc2626;">Access denied</td></tr>';
            return;
        }
        const data  = await res.json();
        const daily = data.daily || [];

        if (daily.length === 0) {
            if (noData) noData.style.display = '';
            bodyEl.innerHTML = '<tr><td colspan="6" style="padding:16px;text-align:center;color:#6366f1;">No data for selected period.</td></tr>';
            return;
        }

        // Aggregate by day (sum across all models — single-model case this is a no-op)
        const byDay = {};
        daily.forEach(r => {
            const d = (r.day || '').toString().substring(0, 10);
            if (!byDay[d]) byDay[d] = { input: 0, output: 0, calls: 0 };
            byDay[d].input  += Number(r.input_tokens  || 0);
            byDay[d].output += Number(r.output_tokens || 0);
            byDay[d].calls  += Number(r.call_count    || 0);
        });
        const days_sorted = Object.keys(byDay).sort();

        _buildTokenChart(days_sorted, byDay);
        _buildCallsChart(days_sorted, byDay);

        // Table — full detail rows
        bodyEl.innerHTML = daily.map(r => {
            const day    = (r.day || '').toString().substring(0, 10);
            const model  = r.model_name || '—';
            const calls  = Number(r.call_count    || 0).toLocaleString();
            const input  = Number(r.input_tokens  || 0).toLocaleString();
            const output = Number(r.output_tokens || 0).toLocaleString();
            const total  = Number(r.total_tokens  || 0).toLocaleString();
            return `<tr style="border-bottom:1px solid #e0e7ff;">
                <td style="padding:7px 10px; white-space:nowrap; color:#1e293b;">${day}</td>
                <td style="padding:7px 10px; max-width:200px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; color:#1e293b;" title="${model}">${model}</td>
                <td style="padding:7px 10px; text-align:right; color:#1e293b;">${calls}</td>
                <td style="padding:7px 10px; text-align:right; color:#3b82f6;">${input}</td>
                <td style="padding:7px 10px; text-align:right; color:#8b5cf6;">${output}</td>
                <td style="padding:7px 10px; text-align:right; font-weight:600; color:#4338ca;">${total}</td>
            </tr>`;
        }).join('');
    } catch (e) {
        bodyEl.innerHTML = '<tr><td colspan="6" style="padding:16px;text-align:center;color:#dc2626;">Error loading usage data</td></tr>';
    }
}

function _buildTokenChart(days, byDay) {
    const canvas = document.getElementById('usageChartTokens');
    if (!canvas || typeof Chart === 'undefined') return;

    _usageChartTokens = new Chart(canvas, {
        type: 'bar',
        data: {
            labels: days,
            datasets: [
                {
                    label: 'Input tokens',
                    data: days.map(d => byDay[d].input),
                    backgroundColor: '#3b82f6cc',
                    hoverBackgroundColor: '#3b82f6',
                    borderRadius: 4,
                    borderSkipped: false,
                },
                {
                    label: 'Output tokens',
                    data: days.map(d => byDay[d].output),
                    backgroundColor: '#8b5cf6cc',
                    hoverBackgroundColor: '#8b5cf6',
                    borderRadius: 4,
                    borderSkipped: false,
                }
            ]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            interaction: { mode: 'index', intersect: false },
            plugins: {
                legend: { position: 'top', labels: { font: { size: 10 }, boxWidth: 10, padding: 8, color: '#4338ca' } },
                tooltip: {
                    backgroundColor: '#1e1b4b',
                    titleColor: '#a5b4fc',
                    bodyColor: '#e0e7ff',
                    callbacks: {
                        label: ctx => {
                            const v = ctx.parsed.y;
                            return ` ${ctx.dataset.label}: ${v >= 1000 ? (v/1000).toFixed(1)+'k' : v.toLocaleString()}`;
                        },
                        footer: items => {
                            const sum = items.reduce((a, b) => a + b.parsed.y, 0);
                            return `Total: ${sum >= 1000 ? (sum/1000).toFixed(1)+'k' : sum.toLocaleString()} tokens`;
                        }
                    }
                }
            },
            scales: {
                x: { stacked: true, ticks: { font: { size: 9 }, maxRotation: 40, color: '#818cf8' }, grid: { display: false } },
                y: { stacked: true,
                     ticks: { font: { size: 9 }, color: '#818cf8', callback: v => v >= 1000 ? (v/1000).toFixed(0)+'k' : v },
                     grid: { color: 'rgba(99,102,241,0.08)' } }
            }
        }
    });
}

function _buildCallsChart(days, byDay) {
    const canvas = document.getElementById('usageChartCalls');
    if (!canvas || typeof Chart === 'undefined') return;

    _usageChartCalls = new Chart(canvas, {
        type: 'line',
        data: {
            labels: days,
            datasets: [{
                label: 'Calls',
                data: days.map(d => byDay[d].calls),
                borderColor: '#10b981',
                backgroundColor: 'rgba(16,185,129,0.12)',
                pointBackgroundColor: '#10b981',
                pointBorderColor: '#fff',
                pointBorderWidth: 2,
                pointRadius: 5,
                pointHoverRadius: 7,
                borderWidth: 2.5,
                fill: true,
                tension: 0.35,
            }]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            interaction: { mode: 'index', intersect: false },
            plugins: {
                legend: { display: false },
                tooltip: {
                    backgroundColor: '#022c22',
                    titleColor: '#6ee7b7',
                    bodyColor: '#d1fae5',
                    callbacks: {
                        label: ctx => ` ${ctx.parsed.y} LLM call${ctx.parsed.y !== 1 ? 's' : ''}`
                    }
                }
            },
            scales: {
                x: { ticks: { font: { size: 9 }, maxRotation: 40, color: '#34d399' }, grid: { display: false } },
                y: { beginAtZero: true,
                     ticks: { font: { size: 9 }, color: '#34d399', precision: 0 },
                     grid: { color: 'rgba(16,185,129,0.08)' } }
            }
        }
    });
}

function resetAuditFilter() {
    const f = document.getElementById('auditFrom');
    const t = document.getElementById('auditTo');
    const e = document.getElementById('auditEmail');
    if (f) f.value = '';
    if (t) t.value = '';
    if (e) e.value = '';
    auditSortCol = 'ts';
    auditSortDir = 'desc';
    loadAuditLog(0);
}

// =============================================================================
// USER PREFERENCES
// =============================================================================
async function openUserPrefs() {
    const ta       = document.getElementById('userPrefsInput');
    const statusEl = document.getElementById('userPrefsSaveStatus');
    const editBtn  = document.getElementById('userPrefsEditBtn');
    const saveBtn  = document.getElementById('userPrefsSaveBtn');

    if (statusEl) { statusEl.style.display = 'none'; statusEl.textContent = ''; }
    if (ta)       { ta.readOnly = true; ta.style.opacity = '0.7'; ta.value = 'Loading...'; }
    if (editBtn)  { editBtn.style.display = 'inline-block'; }
    if (saveBtn)  { saveBtn.style.display = 'none'; }

    document.getElementById('userPrefsModal').style.display = 'flex';

    try {
        const res  = await fetch('/api/user/prefs?userId=' + encodeURIComponent(CURRENT_USER_ID));
        const data = await res.json();
        if (ta) ta.value = data.success ? (data.prefs || '') : '';
    } catch (_) {
        if (ta) ta.value = '';
    }
}

function editUserPrefs() {
    const ta      = document.getElementById('userPrefsInput');
    const editBtn = document.getElementById('userPrefsEditBtn');
    const saveBtn = document.getElementById('userPrefsSaveBtn');
    if (ta)      { ta.readOnly = false; ta.style.opacity = '1'; ta.focus(); }
    if (editBtn) { editBtn.style.display = 'none'; }
    if (saveBtn) { saveBtn.style.display = 'inline-block'; }
}

function closeUserPrefs() {
    document.getElementById('userPrefsModal').style.display = 'none';
}

async function saveUserPrefs() {
    if (!CURRENT_USER_ID) return;
    const ta       = document.getElementById('userPrefsInput');
    const statusEl = document.getElementById('userPrefsSaveStatus');
    const editBtn  = document.getElementById('userPrefsEditBtn');
    const saveBtn  = document.getElementById('userPrefsSaveBtn');
    const val      = ta ? ta.value.trim() : '';

    try {
        const res  = await fetch('/api/user/prefs/save', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userId: CURRENT_USER_ID, prefs: val })
        });
        const data = await res.json();
        if (statusEl) {
            statusEl.textContent = data.success ? '✅ Preferences saved.' : ('❌ ' + (data.error || 'Save failed.'));
            statusEl.style.color = data.success ? 'var(--online-color, #22c55e)' : '#ef4444';
            statusEl.style.display = 'block';
        }
        if (data.success) {
            if (ta)      { ta.readOnly = true; ta.style.opacity = '0.7'; }
            if (editBtn) { editBtn.style.display = 'inline-block'; }
            if (saveBtn) { saveBtn.style.display = 'none'; }
        }
    } catch (e) {
        if (statusEl) {
            statusEl.textContent = '❌ Could not reach server.';
            statusEl.style.color = '#ef4444';
            statusEl.style.display = 'block';
        }
    }
}

async function verifyAdminPin() {
    const pin   = document.getElementById('adminPinInput').value;
    const errEl = document.getElementById('adminAuthError');
    const btn   = document.getElementById('adminAuthBtn');
    errEl.style.display = 'none';

    if (!pin) {
        errEl.textContent = 'Please enter the admin PIN.';
        errEl.style.display = 'block'; return;
    }

    btn.textContent = '⏳ Verifying...'; btn.disabled = true;
    const hash = await hashPin(pin);

    try {
        const res  = await fetch('/api/admin/verify', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ pinHash: hash })
        });
        const data = await res.json();

        if (!data.success) {
            errEl.className   = 'test-result test-error';
            errEl.textContent = data.error || 'Incorrect admin PIN.';
            errEl.style.display = 'block';
            btn.textContent = 'Verify & Enter'; btn.disabled = false;
            return;
        }

        adminPinHashInSession = hash;
        // Load known users for autocomplete (non-blocking)
        loadKnownUsersAutocomplete();
        // Load global prompt in read-only mode
        const promptEl = document.getElementById('globalPromptText');
        promptEl.value = data.globalPrompt || '';
        promptEl.readOnly = true;
        promptEl.style.opacity = '0.7';
        const editBtn = document.getElementById('promptEditBtn');
        if (editBtn) editBtn.style.display = 'inline-block';
        const saveBtn = document.getElementById('promptSaveBtn');
        if (saveBtn) saveBtn.style.display = 'none';
        document.getElementById('adminSaveResult').style.display = 'none';
        document.getElementById('adminAuthSection').style.display = 'none';
        document.getElementById('adminEditorSection').style.display = 'block';
        // Show Audit and Usage tabs only for permanent admins
        const auditTabBtn = document.getElementById('adminTabAudit');
        if (auditTabBtn) auditTabBtn.style.display = IS_PERMANENT_ADMIN ? '' : 'none';
        const usageTabBtn = document.getElementById('adminTabUsage');
        if (usageTabBtn) usageTabBtn.style.display = IS_PERMANENT_ADMIN ? '' : 'none';
        // Start on Prompt tab; load allowlist + roles in background
        switchAdminTab('Prompt');
        loadAllowlist();
        loadRolesList();

    } catch (e) {
        errEl.className   = 'test-result test-error';
        errEl.textContent = 'Could not reach server.';
        errEl.style.display = 'block';
        btn.textContent = 'Verify & Enter'; btn.disabled = false;
    }
}

function editGlobalPrompt() {
    const promptEl = document.getElementById('globalPromptText');
    promptEl.readOnly = false;
    promptEl.style.opacity = '1';
    promptEl.focus();
    document.getElementById('promptEditBtn').style.display = 'none';
    document.getElementById('promptSaveBtn').style.display = 'inline-block';
}

async function saveGlobalPrompt() {
    const prompt   = document.getElementById('globalPromptText').value.trim();
    const resultEl = document.getElementById('adminSaveResult');
    resultEl.style.display = 'none';

    try {
        const res  = await fetch('/api/admin/save', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ pinHash: adminPinHashInSession, prompt })
        });
        const data = await res.json();
        resultEl.className   = 'test-result ' + (data.success ? 'test-success' : 'test-error');
        resultEl.textContent = data.success
            ? '✅ Global prompt saved.'
            : '❌ ' + (data.error || 'Save failed.');
        resultEl.style.display = 'block';
        if (data.success) {
            // Switch back to read-only
            const promptEl = document.getElementById('globalPromptText');
            promptEl.readOnly = true; promptEl.style.opacity = '0.7';
            document.getElementById('promptEditBtn').style.display = 'inline-block';
            document.getElementById('promptSaveBtn').style.display = 'none';
        }
    } catch (e) {
        resultEl.className   = 'test-result test-error';
        resultEl.textContent = '❌ Could not reach server.';
        resultEl.style.display = 'block';
    }
}

// Internal email list state for the allowlist editor
let _allowlistEmails = [];

async function loadAllowlist() {
    if (!adminPinHashInSession) return;
    try {
        const res  = await fetch('/api/admin/allowlist?pinHash=' + encodeURIComponent(adminPinHashInSession));
        const data = await res.json();
        if (data.success) {
            _allowlistEmails = (data.allowlist || '')
                .split('\n')
                .map(l => l.trim())
                .filter(l => l && !l.startsWith('#'));
        }
    } catch (e) {}
}

async function _saveAllowlist() {
    const text     = _allowlistEmails.join('\n');
    const resultEl = document.getElementById('allowlistSaveResult');
    if (resultEl) resultEl.style.display = 'none';
    try {
        const res  = await fetch('/api/admin/allowlist', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ pinHash: adminPinHashInSession, allowlist: text })
        });
        const data = await res.json();
        if (resultEl) {
            resultEl.className   = 'test-result ' + (data.success ? 'test-success' : 'test-error');
            resultEl.textContent = data.success
                ? '✅ Allowlist saved. Takes effect on next login.'
                : '❌ ' + (data.error || 'Save failed.');
            resultEl.style.display = 'block';
            setTimeout(() => { if (resultEl) resultEl.style.display = 'none'; }, 3000);
        }
    } catch (e) {
        if (resultEl) {
            resultEl.className   = 'test-result test-error';
            resultEl.textContent = '❌ Could not reach server.';
            resultEl.style.display = 'block';
        }
    }
}

// Populate datalist from already-logged-in users (on admin panel open)
async function loadKnownUsersAutocomplete() {
    if (!adminPinHashInSession) return;
    try {
        const res  = await fetch('/api/admin/known-users?pinHash=' + encodeURIComponent(adminPinHashInSession));
        const data = await res.json();
        if (!data.success || !data.users) return;
        const dl = document.getElementById('knownUsersList');
        if (!dl) return;
        dl.innerHTML = '';
        data.users.forEach(email => {
            const opt = document.createElement('option');
            opt.value = email;
            dl.appendChild(opt);
        });
    } catch (e) {}
}

// Live directory search — debounced, fires when admin types 2+ chars
let _userSearchTimer = null;
function onAllowlistEmailInput() {
    if (!adminPinHashInSession) return;
    clearTimeout(_userSearchTimer);
    const q = (document.getElementById('allowlistEmailInput').value || '').trim();
    if (q.length < 2) return;
    _userSearchTimer = setTimeout(async () => {
        try {
            const res  = await fetch('/api/admin/search-users?q=' + encodeURIComponent(q)
                                     + '&pinHash=' + encodeURIComponent(adminPinHashInSession));
            const data = await res.json();
            if (!data.success || !data.users || !data.users.length) return;
            const dl = document.getElementById('knownUsersList');
            if (!dl) return;
            // Merge search results with existing options (preserve known-users)
            const existing = new Set(Array.from(dl.options).map(o => o.value));
            data.users.forEach(email => {
                if (!existing.has(email)) {
                    const opt = document.createElement('option');
                    opt.value = email;
                    dl.appendChild(opt);
                }
            });
        } catch (e) {}
    }, 300);
}

async function addAllowlistEmail() {
    const input    = document.getElementById('allowlistEmailInput');
    const roleSel  = document.getElementById('allowlistRoleSelect');
    const resultEl = document.getElementById('allowlistAddResult');
    const email    = (input ? input.value : '').trim().toLowerCase();
    const role     = roleSel ? roleSel.value : '';
    if (resultEl) resultEl.style.display = 'none';

    if (!email) return;
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
        if (resultEl) { resultEl.className = 'test-result test-error'; resultEl.textContent = '❌ Enter a valid email address.'; resultEl.style.display = 'block'; }
        return;
    }
    if (!role) {
        if (resultEl) { resultEl.className = 'test-result test-error'; resultEl.textContent = '❌ Select a role for this user.'; resultEl.style.display = 'block'; }
        return;
    }
    if (_allowlistEmails.includes(email)) {
        if (resultEl) { resultEl.className = 'test-result test-error'; resultEl.textContent = '⚠️ ' + email + ' is already in the list.'; resultEl.style.display = 'block'; }
        return;
    }
    _allowlistEmails.push(email);
    if (input) input.value = '';
    await _saveAllowlist();
    await saveUserRole(email, role);
    // Re-render if list is visible
    const listEl = document.getElementById('allowlistUserList');
    if (listEl && listEl.style.display !== 'none') renderAllowlistUI();
}

async function removeAllowlistEmail(email) {
    _allowlistEmails = _allowlistEmails.filter(e => e !== email);
    await _saveAllowlist();
    renderAllowlistUI();
}

function renderAllowlistUI() {
    const listEl = document.getElementById('allowlistUserList');
    if (!listEl) return;
    if (_allowlistEmails.length === 0) {
        listEl.innerHTML = '<p style="font-size:0.85em;color:var(--muted-text);margin:0;">No users added — all authenticated BC users have access.</p>';
        return;
    }
    const roleOptions = (_roles.length ? _roles : ['ADMIN'])
        .map(r => `<option value="${r}">${r}</option>`).join('');
    listEl.innerHTML = _allowlistEmails.map(email => {
        const currentRole = _userRolesCache[email] || '';
        const opts = `<option value="">— role —</option>` +
            (_roles.length ? _roles : ['ADMIN']).map(r =>
                `<option value="${r}"${r === currentRole ? ' selected' : ''}>${r}</option>`
            ).join('');
        return `<div class="allowlist-item" style="gap:6px;">
            <span style="flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:0.88em;" title="${email}">${email}</span>
            <select onchange="saveUserRole('${email}',this.value).then(ok=>{this.style.borderColor=ok?'#22c55e':'#ef4444';setTimeout(()=>this.style.borderColor='',1500);})"
                    style="padding:3px 6px;border:1px solid var(--border-subtle);border-radius:4px;font-size:0.8em;background:var(--surface-bg,#fff);color:var(--text-color,#111);min-width:110px;">${opts}</select>
            <button class="allowlist-item-remove" onclick="removeAllowlistEmail('${email}')" title="Remove">✕</button>
        </div>`;
    }).join('');
}

async function toggleAllowlistView() {
    const listEl = document.getElementById('allowlistUserList');
    const btn    = document.getElementById('allowlistViewBtn');
    if (!listEl) return;
    if (listEl.style.display === 'none') {
        // Ensure roles and role assignments are loaded before rendering dropdowns
        if (_roles.length === 0) await loadRolesList();
        if (adminPinHashInSession) {
            try {
                const res  = await fetch('/api/admin/user-roles?pinHash=' + encodeURIComponent(adminPinHashInSession));
                const data = await res.json();
                if (data.success) (data.assignments || []).forEach(a => { _userRolesCache[a.email] = a.role; });
            } catch (_) {}
        }
        renderAllowlistUI();
        listEl.style.display = 'block';
        if (btn) btn.textContent = '🙈 Hide User List';
    } else {
        listEl.style.display = 'none';
        if (btn) btn.textContent = '👁 Show User List';
    }
}

// =============================================================================
// ROLES — management (admin)
// =============================================================================

async function loadRolesList() {
    if (!adminPinHashInSession) return;
    try {
        const res  = await fetch('/api/admin/roles?pinHash=' + encodeURIComponent(adminPinHashInSession));
        const data = await res.json();
        if (data.success) {
            _roles = data.roles || ['ADMIN'];
            populateRoleDropdowns();
        }
    } catch (_) {}
}

function populateRoleDropdowns() {
    const sel = document.getElementById('allowlistRoleSelect');
    if (!sel) return;
    const current = sel.value;
    sel.innerHTML = '<option value="">— select role —</option>' +
        _roles.map(r => `<option value="${r}"${r === current ? ' selected' : ''}>${r}</option>`).join('');
}

async function loadRolesTab() {
    await loadRolesList();
    renderRolesUI();
    await loadUserRoleAssignments();
}

function renderRolesUI() {
    const el = document.getElementById('rolesListEl');
    if (!el) return;
    el.innerHTML = (_roles.length === 0 ? ['ADMIN'] : _roles).map(role => {
        const isAdmin = role === 'ADMIN';
        return `<span style="display:inline-flex;align-items:center;gap:4px;background:var(--sidebar-bg);border:1px solid var(--border-subtle);border-radius:14px;padding:3px 10px;font-size:0.82em;color:var(--muted-text);">
            ${role}
            ${isAdmin ? '' : `<button onclick="deleteRole('${role}')" title="Delete" style="background:none;border:none;cursor:pointer;color:#ef4444;font-size:0.9em;padding:0 2px;line-height:1;">✕</button>`}
        </span>`;
    }).join('');
}

async function addRole() {
    const input    = document.getElementById('newRoleInput');
    const resultEl = document.getElementById('rolesActionResult');
    const role     = (input ? input.value : '').trim().toUpperCase().replace(/[^A-Z0-9_]/g, '');
    if (resultEl) resultEl.style.display = 'none';
    if (!role) return;
    if (_roles.includes(role)) {
        if (resultEl) { resultEl.className = 'test-result test-error'; resultEl.textContent = '⚠️ Role already exists.'; resultEl.style.display = 'block'; }
        return;
    }
    try {
        const res  = await fetch('/api/admin/roles/save', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ pinHash: adminPinHashInSession, role })
        });
        const data = await res.json();
        if (data.success) {
            _roles = data.roles || _roles;
            if (input) input.value = '';
            renderRolesUI();
            populateRoleDropdowns();
            if (resultEl) { resultEl.className = 'test-result test-success'; resultEl.textContent = '✅ Role "' + role + '" created.'; resultEl.style.display = 'block'; setTimeout(() => { if (resultEl) resultEl.style.display = 'none'; }, 2500); }
        } else {
            if (resultEl) { resultEl.className = 'test-result test-error'; resultEl.textContent = '❌ ' + (data.error || 'Failed.'); resultEl.style.display = 'block'; }
        }
    } catch (_) {
        if (resultEl) { resultEl.className = 'test-result test-error'; resultEl.textContent = '❌ Could not reach server.'; resultEl.style.display = 'block'; }
    }
}

async function deleteRole(role) {
    const resultEl = document.getElementById('rolesActionResult');
    if (resultEl) resultEl.style.display = 'none';
    try {
        const res  = await fetch('/api/admin/roles/delete', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ pinHash: adminPinHashInSession, role })
        });
        const data = await res.json();
        if (data.success) {
            _roles = data.roles || _roles;
            renderRolesUI();
            populateRoleDropdowns();
            if (resultEl) { resultEl.className = 'test-result test-success'; resultEl.textContent = '✅ Role "' + role + '" deleted.'; resultEl.style.display = 'block'; setTimeout(() => { if (resultEl) resultEl.style.display = 'none'; }, 2500); }
        } else {
            if (resultEl) { resultEl.className = 'test-result test-error'; resultEl.textContent = '❌ ' + (data.error || 'Failed.'); resultEl.style.display = 'block'; }
        }
    } catch (_) {}
}

async function saveUserRole(email, role) {
    if (!adminPinHashInSession) return false;
    try {
        const res  = await fetch('/api/admin/user-roles/save', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ pinHash: adminPinHashInSession, email, role })
        });
        const data = await res.json();
        if (data.success) _userRolesCache[email] = role;
        return data.success;
    } catch (_) { return false; }
}

async function loadUserRoleAssignments() {
    const el = document.getElementById('userRoleAssignments');
    if (!el || !adminPinHashInSession) return;
    el.innerHTML = '<p style="font-size:0.85em;color:var(--muted-text);">Loading...</p>';
    try {
        const res  = await fetch('/api/admin/user-roles?pinHash=' + encodeURIComponent(adminPinHashInSession));
        const data = await res.json();
        if (data.success) {
            (data.assignments || []).forEach(a => { _userRolesCache[a.email] = a.role; });
            renderUserRoleAssignments(data.assignments || []);
        } else {
            el.innerHTML = '<p style="font-size:0.85em;color:#ef4444;">Failed to load assignments.</p>';
        }
    } catch (_) {
        el.innerHTML = '<p style="font-size:0.85em;color:#ef4444;">Could not reach server.</p>';
    }
}

function renderUserRoleAssignments(assignments) {
    const el = document.getElementById('userRoleAssignments');
    if (!el) return;
    if (!assignments.length) {
        el.innerHTML = '<p style="font-size:0.85em;color:var(--muted-text);margin:0;">No users found yet. Users appear here after their first login.</p>';
        return;
    }
    el.innerHTML = assignments.map(({email, role, active}) => {
        const isActive  = active !== 'false';
        const emailStyle = isActive
            ? 'flex:1;font-size:0.85em;color:var(--text-color);overflow:hidden;text-overflow:ellipsis;white-space:nowrap;'
            : 'flex:1;font-size:0.85em;color:#ef4444;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;text-decoration:line-through;';
        const badge = isActive ? '' : '<span style="font-size:0.72em;background:#fee2e2;color:#ef4444;border-radius:3px;padding:1px 5px;margin-left:4px;flex-shrink:0;">removed</span>';
        return `
        <div style="display:flex;align-items:center;gap:8px;padding:5px 0;border-bottom:1px solid var(--border-subtle);">
            <span style="${emailStyle}" title="${email}">${email}</span>
            ${badge}
            <select onchange="saveUserRole('${email}',this.value).then(ok=>{this.style.borderColor=ok?'#22c55e':'#ef4444';setTimeout(()=>this.style.borderColor='',1500);})"
                    style="padding:4px 6px;border:1px solid var(--border-subtle);border-radius:4px;font-size:0.82em;background:var(--surface-bg,#fff);color:var(--text-color,#111);min-width:130px;">
                ${_roles.map(r => `<option value="${r}"${r === role ? ' selected' : ''}>${r}</option>`).join('')}
            </select>
            <button class="allowlist-item-remove" onclick="deleteUserRoleAssignment('${email}')" title="Remove role assignment">✕</button>
        </div>`;
    }).join('');
}

async function deleteUserRoleAssignment(email) {
    if (!adminPinHashInSession) return;
    const resultEl = document.getElementById('rolesActionResult');
    if (resultEl) resultEl.style.display = 'none';
    try {
        const res  = await fetch('/api/admin/user-roles/delete', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ pinHash: adminPinHashInSession, email })
        });
        const data = await res.json();
        if (data.success) {
            await loadUserRoleAssignments();
            if (resultEl) { resultEl.className = 'test-result test-success'; resultEl.textContent = '✅ Role assignment removed for ' + email; resultEl.style.display = 'block'; setTimeout(() => { if (resultEl) resultEl.style.display = 'none'; }, 2500); }
        } else {
            if (resultEl) { resultEl.className = 'test-result test-error'; resultEl.textContent = '❌ ' + (data.error || 'Failed.'); resultEl.style.display = 'block'; }
        }
    } catch (_) {}
}

// =============================================================================
// FAVOURITES — saved prompts
// =============================================================================

async function loadFavourites() {
    if (!CURRENT_USER_ID) return;
    try {
        const res  = await fetch('/api/favourites/list', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userId: CURRENT_USER_ID })
        });
        const data = await res.json();
        if (data.success) { favourites = data.favourites || []; renderFavourites(); }
    } catch (e) {}
}

function renderFavourites() {
    const list = document.getElementById('favouritesList');
    if (!list) return;
    list.innerHTML = '';

    if (favourites.length === 0) {
        const empty = document.createElement('div');
        empty.style.cssText = 'font-size:0.78em; color:#94a3b8; padding:6px 10px;';
        empty.textContent = 'No saved favourites yet. Click ⭐ Favourite on any message.';
        list.appendChild(empty);
        return;
    }

    favourites.forEach(fav => {
        const div = document.createElement('div');
        div.className = 'chat-item';
        div.style.cssText = 'display:flex; justify-content:space-between; align-items:center;';
        div.title = fav.prompt;

        const labelSpan = document.createElement('span');
        labelSpan.style.cssText = 'overflow:hidden; text-overflow:ellipsis; white-space:nowrap; flex-grow:1; cursor:pointer;';
        labelSpan.textContent = fav.label;
        labelSpan.onclick = () => runFavourite(fav.prompt);

        const btnStyle = 'background:transparent; border:none; cursor:pointer; opacity:0.5; padding:0 4px; font-size:0.85em;';

        const delBtn = document.createElement('button');
        delBtn.innerHTML = '🗑️';
        delBtn.style.cssText = btnStyle;
        delBtn.title = 'Remove favourite';
        delBtn.onmouseover = () => delBtn.style.opacity = '1';
        delBtn.onmouseout  = () => delBtn.style.opacity = '0.5';
        delBtn.onclick = (e) => { e.stopPropagation(); deleteFavourite(fav.id); };

        div.appendChild(labelSpan);
        div.appendChild(delBtn);
        list.appendChild(div);
    });
}

function runFavourite(prompt) {
    const input = document.getElementById('prompt');
    if (!input) return;
    input.value        = prompt;
    input.style.height = 'auto';
    input.style.height = input.scrollHeight + 'px';
    input.focus();
}

function openSaveFavModal(promptText) {
    document.getElementById('favLabelInput').value        = '';
    document.getElementById('favPromptInput').value       = promptText || '';
    document.getElementById('saveFavError').style.display = 'none';
    document.getElementById('saveFavModal').style.display = 'flex';
    setTimeout(() => document.getElementById('favLabelInput').focus(), 100);
}

function closeSaveFavModal() {
    document.getElementById('saveFavModal').style.display = 'none';
}

async function confirmSaveFav() {
    const label  = document.getElementById('favLabelInput').value.trim();
    const prompt = document.getElementById('favPromptInput').value.trim();
    const errEl  = document.getElementById('saveFavError');
    errEl.style.display = 'none';

    if (!CURRENT_USER_ID) { errEl.textContent = 'Not logged in.'; errEl.style.display = 'block'; return; }
    if (!prompt) {
        errEl.textContent = 'Prompt cannot be empty.';
        errEl.style.display = 'block'; return;
    }

    try {
        const res  = await fetch('/api/favourites/save', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userId: CURRENT_USER_ID, label, prompt })
        });
        const data = await res.json();
        if (!data.success) {
            errEl.textContent = data.error || 'Could not save favourite.';
            errEl.style.display = 'block'; return;
        }
        closeSaveFavModal();
        await loadFavourites();
    } catch (e) {
        errEl.textContent = 'Could not reach server.';
        errEl.style.display = 'block';
    }
}

async function deleteFavourite(id) {
    if (!CURRENT_USER_ID) return;
    try {
        await fetch('/api/favourites/delete', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userId: CURRENT_USER_ID, id })
        });
        await loadFavourites();
    } catch (e) {}
}

// =============================================================================
// APP BOOT
// =============================================================================

// Fetch SSO auth status from the server. If Spring Security redirected to SSO login,
// res.redirected is true — we redirect the full page to trigger SSO.
async function fetchAuthStatus() {
    try {
        const res = await fetch('/api/auth/status');
        if (res.redirected) {
            window.location.href = '/oauth2/authorization/sso';
            return { authenticated: false };
        }
        return await res.json();
    } catch (e) {
        return { authenticated: false };
    }
}

window.onload = async function () {
    applyThemeLabel();
    try {
        const status = await fetchAuthStatus();

        if (status.cfMode) {
            CF_MODE        = true;
            CF_MODEL_LABEL = status.model || '';
            CF_MCP_SERVERS = status.mcpServers || [];
            const settingsBtn = document.getElementById('settingsBtn');
            if (settingsBtn) settingsBtn.style.display = 'none';
            buildMcpDots();
        }

        if (!status.authenticated) {
            window.location.href = '/oauth2/authorization/sso';
            return;
        }

        CURRENT_USER_ID    = status.userId;
        USER_EMAIL         = status.email || status.userId;
        IS_PERMANENT_ADMIN = status.isPermanentAdmin === true;
        localStorage.setItem('gp_user_id', CURRENT_USER_ID);

        // Compute initials from email (e.g. "abhishek.jain@broadcom.com" → "AJ")
        const atIdx    = USER_EMAIL.indexOf('@');
        const localPart = atIdx > 0 ? USER_EMAIL.substring(0, atIdx) : USER_EMAIL;
        const parts    = localPart.split(/[._\-+]/);
        const initials = parts.length >= 2
            ? (parts[0][0] || '') + (parts[1][0] || '')
            : (localPart[0] || '?');

        const avatarEl = document.getElementById('hdrAvatar');
        if (avatarEl) { avatarEl.textContent = initials.toUpperCase(); avatarEl.title = USER_EMAIL; }

        const userEmailEl = document.getElementById('userEmailDisplay');
        if (userEmailEl) userEmailEl.textContent = USER_EMAIL;

        // Show admin button only for ADMIN role users (or unassigned = defaults to ADMIN)
        try {
            const roleRes  = await fetch('/api/user/current-role');
            const roleData = await roleRes.json();
            if (roleData.success && roleData.role === 'ADMIN') {
                const adminBtn = document.getElementById('adminPanelBtn');
                if (adminBtn) adminBtn.style.display = '';
            }
        } catch (_) {
            // If role check fails, show the button to avoid locking admins out
            const adminBtn = document.getElementById('adminPanelBtn');
            if (adminBtn) adminBtn.style.display = '';
        }

        bootApp();
    } catch (e) {
        console.error('[boot] Unexpected error:', e);
        window.location.href = '/oauth2/authorization/sso';
    }
};

// CF info stored at boot
let CF_MODEL_LABEL = '';
let CF_MCP_SERVERS = []; // ["Greenplum"], ["OpenMetadata"], or ["Greenplum","OpenMetadata"]

// Build MCP service dots in the header using the globally set CF_MCP_SERVERS.
// Safe to call multiple times — clears and rebuilds each time.
function buildMcpDots() {
    const toShow = ['Greenplum'];
    if (CF_MCP_SERVERS.includes('OpenMetadata')) toShow.push('OpenMetadata');

    const container = document.getElementById('mcpStatusContainer');
    if (!container) return;
    container.innerHTML = '';
    toShow.forEach(name => {
        const sep = document.createElement('span');
        sep.style.cssText = 'display:inline-flex;align-items:center;gap:5px;' +
            'border-left:1px solid rgba(255,255,255,0.15);padding-left:10px;margin-left:6px;';
        sep.innerHTML = `<span id="mcp-dot-${name}" class="status-dot status-unknown"></span>` +
                        `<span>${name}</span>`;
        container.appendChild(sep);
    });
}

function applyCfMode(status) {
    if (status) {
        CF_MCP_SERVERS = status.mcpServers || CF_MCP_SERVERS;
        CF_MODEL_LABEL = status.model || CF_MODEL_LABEL;
    }
    const settingsBtn = document.getElementById('settingsBtn');
    if (settingsBtn) settingsBtn.style.display = 'none';
    buildMcpDots();
}

/**
 * After /api/test/cf completes, color model dot + each MCP server dot.
 *   green  = connected
 *   red    = failed / not configured
 */
function buildCfStatusLabel(data) {
    const modelOk = data.modelStatus === 'success';

    // Model dot + name
    const modelDot  = document.getElementById('headerStatusDot');
    const modelText = document.getElementById('headerStatusText');
    if (modelDot)  modelDot.className = 'status-dot ' + (modelOk ? 'status-online' : 'status-offline');
    if (modelText) modelText.textContent = CF_MODEL_LABEL || 'Model';
    const modelIndicator = document.getElementById('modelIndicator');
    if (modelIndicator) modelIndicator.dataset.tip = CF_MODEL_LABEL || 'Model';

    // MCP dots — Greenplum always shown, OpenMetadata if bound
    const statusByServer = { 'Greenplum': data.mcpStatus, 'OpenMetadata': data.omMcpStatus };
    ['Greenplum', 'OpenMetadata'].forEach(name => {
        const dot = document.getElementById('mcp-dot-' + name);
        if (!dot) return;
        const ok = statusByServer[name] === 'success';
        dot.className = 'status-dot ' + (ok ? 'status-online' : 'status-offline');
    });
}

function configureMarked() {
    marked.use({ breaks: true, gfm: true });
    if (typeof DOMPurify !== 'undefined') {
        DOMPurify.addHook('afterSanitizeAttributes', function (node) {
            if (node.tagName === 'A' && node.getAttribute('href')) {
                node.setAttribute('target', '_blank');
                node.setAttribute('rel', 'noopener noreferrer');
            }
        });
    }
}

// Pull settings from server filesystem and populate localStorage.
// Called on every boot so incognito / new-browser sessions always have
// the correct config without the user needing to re-upload credentials.
async function loadSettingsFromServer() {
    if (!CURRENT_USER_ID) return;
    try {
        const res  = await fetch('/api/settings/load?userId=' + encodeURIComponent(CURRENT_USER_ID));
        const data = await res.json();
        if (data.success && data.config) {
            localStorage.setItem('gp_config', JSON.stringify({ data: data.config }));
            // Apply theme from server so it's consistent across all browsers/sessions
            if (data.config.theme) {
                localStorage.setItem('gp_theme', data.config.theme);
                document.documentElement.setAttribute('data-theme', data.config.theme);
                applyThemeLabel();
            }
        }
    } catch (e) {
        // Server unreachable — fall back to whatever is already in localStorage
    }
}

// Load all session data from server filesystem — called on every boot.
async function loadSessionsFromServer() {
    if (!CURRENT_USER_ID) return;
    try {
        const res  = await fetch('/api/sessions/load?userId=' + encodeURIComponent(CURRENT_USER_ID));
        const data = await res.json();
        if (!data.success) return;

        if (Array.isArray(data.sessions) && data.sessions.length > 0) {
            chatSessions = data.sessions;
            localStorage.setItem('gp_sessions', JSON.stringify(chatSessions));
        }
        if (data.currentSessionId) {
            currentSessionId = data.currentSessionId;
            localStorage.setItem('gp_current_session', data.currentSessionId);
        }
        if (Array.isArray(data.history) && data.history.length > 0) {
            localStorage.setItem('gp_history', JSON.stringify(data.history));
        }
        if (data.chatData && typeof data.chatData === 'object') {
            Object.entries(data.chatData).forEach(([sid, msgs]) => {
                if (Array.isArray(msgs)) {
                    localStorage.setItem('gp_chat_ui_' + sid, JSON.stringify(msgs));
                }
            });
        }
    } catch (e) {
        // Server unreachable — fall back to localStorage only if session not yet set
        if (!currentSessionId) currentSessionId = localStorage.getItem('gp_current_session');
    }
}

// Debounced save — coalesces rapid changes into one server write.
let _sessionSaveTimer = null;
function scheduleSessionSave() {
    if (_sessionSaveTimer) clearTimeout(_sessionSaveTimer);
    _sessionSaveTimer = setTimeout(saveSessionsToServer, 3000);
}

async function saveSessionsToServer() {
    if (!CURRENT_USER_ID) return;
    try {
        const chatData = {};
        chatSessions.forEach(s => {
            const msgs = safeParse('gp_chat_ui_' + s.id, []);
            if (msgs.length > 0) chatData[s.id] = msgs;
        });
        await fetch('/api/sessions/save', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                userId: CURRENT_USER_ID,
                sessions: chatSessions,
                currentSessionId,
                history: Array.from(suggestionHistory),
                chatData
            })
        });
    } catch (e) { /* localStorage still holds current state */ }
}

async function bootApp() {
    try { configureMarked(); } catch (err) { console.warn('[boot] configureMarked failed:', err); }
    setupTextarea();
    await loadSettingsFromServer();    // settings + theme from server file
    await loadSessionsFromServer();    // sessions, messages, history from server file
    // Build suggestion history AFTER server load so gp_history is populated
    const savedHistory = safeParse('gp_history', []);
    suggestionHistory = new Set([
        "Check bloat in the 'sales' table",
        "Show cluster status",
        ...Array.isArray(savedHistory) ? savedHistory : []
    ]);
    initSessions();
    loadFavourites();
    updateModeBadge();
    autoConnect();
}

// =============================================================================
// SESSION / SIDEBAR
// =============================================================================

let activeRequests    = {};
let suggestionHistory = new Set();
let chatSessions      = safeParse('gp_sessions', []);
if (!Array.isArray(chatSessions)) chatSessions = [];
let currentSessionId      = null; // server is authoritative; set by loadSessionsFromServer()
let currentChatUiHistory  = [];
let favourites            = [];

function initSessions() {
    if (!currentSessionId || chatSessions.length === 0) createNewChat(false);
    else loadSession(currentSessionId);
}

async function createNewChat(render = true) {
    if (chatSessions.length >= 10) {
        const oldest = chatSessions.pop();
        localStorage.removeItem('gp_chat_ui_' + oldest.id);
        if (activeRequests[oldest.id]) {
            activeRequests[oldest.id].abort();
            delete activeRequests[oldest.id];
        }
        try {
            await fetch('/api/memory/clear', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ userId: CURRENT_USER_ID, sessionId: oldest.id })
            });
        } catch (e) {}
    }

    const newId = 'session-' + Date.now();
    chatSessions.unshift({ id: newId, title: 'New Conversation' });
    localStorage.setItem('gp_sessions', JSON.stringify(chatSessions));

    if (render) { loadSession(newId); renderSidebar(); }
    else { currentSessionId = newId; localStorage.setItem('gp_current_session', newId); renderSidebar(); }
    scheduleSessionSave();
}

function loadSession(sessionId) {
    currentSessionId     = sessionId;
    localStorage.setItem('gp_current_session', currentSessionId);
    currentChatUiHistory = safeParse('gp_chat_ui_' + currentSessionId, []);

    const messagesDiv = document.getElementById('messages');
    messagesDiv.innerHTML = '';

    if (currentChatUiHistory.length === 0) {
        messagesDiv.innerHTML = `<div class="message-wrapper wrapper-ai"><div class="message ai-message">Hello! I am connected to the server. How can I help you today?</div></div>`;
    } else {
        currentChatUiHistory.forEach(msg => addMessageToDOM(msg.text, msg.className, msg.isMarkdown));
    }

    updateUIState(!!activeRequests[currentSessionId]);
    renderSidebar();
}

function renderSidebar() {
    const chatList = document.getElementById('chatList');
    if (!chatList) return;
    chatList.innerHTML = '';

    chatSessions.forEach(session => {
        const div = document.createElement('div');
        div.className = `chat-item ${session.id === currentSessionId ? 'active' : ''}`;
        div.style.cssText = 'display:flex; justify-content:space-between; align-items:center;';

        const titleSpan = document.createElement('span');
        titleSpan.style.cssText = 'overflow:hidden; text-overflow:ellipsis; white-space:nowrap; flex-grow:1;';
        titleSpan.textContent = activeRequests[session.id] ? '⏳ ' + session.title : session.title;

        const btnStyle = 'background:transparent; border:none; cursor:pointer; opacity:0.5; padding:0 4px; font-size:0.85em;';

        const editBtn = document.createElement('button');
        editBtn.innerHTML = '✏️';
        editBtn.style.cssText = btnStyle;
        editBtn.title = 'Rename';
        editBtn.onmouseover = () => editBtn.style.opacity = '1';
        editBtn.onmouseout  = () => editBtn.style.opacity = '0.5';
        editBtn.onclick = (e) => {
            e.stopPropagation();
            const newTitle = prompt('Rename conversation:', session.title);
            if (newTitle && newTitle.trim()) {
                session.title = newTitle.trim();
                localStorage.setItem('gp_sessions', JSON.stringify(chatSessions));
                renderSidebar();
                scheduleSessionSave();
            }
        };

        const delBtn = document.createElement('button');
        delBtn.innerHTML = '🗑️';
        delBtn.style.cssText = btnStyle;
        delBtn.title = 'Delete conversation';
        delBtn.onmouseover = () => delBtn.style.opacity = '1';
        delBtn.onmouseout  = () => delBtn.style.opacity = '0.5';
        delBtn.onclick = (e) => {
            e.stopPropagation();
            deleteSession(session.id);
        };

        div.onclick = () => loadSession(session.id);
        div.appendChild(titleSpan);
        div.appendChild(editBtn);
        div.appendChild(delBtn);
        chatList.appendChild(div);
    });
}

async function deleteSession(sessionId) {
    if (activeRequests[sessionId]) {
        activeRequests[sessionId].abort();
        delete activeRequests[sessionId];
    }
    chatSessions = chatSessions.filter(s => s.id !== sessionId);
    localStorage.setItem('gp_sessions', JSON.stringify(chatSessions));
    localStorage.removeItem('gp_chat_ui_' + sessionId);
    try {
        await fetch('/api/memory/clear', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userId: CURRENT_USER_ID, sessionId })
        });
    } catch (e) {}
    scheduleSessionSave();
    if (sessionId === currentSessionId) {
        if (chatSessions.length > 0) loadSession(chatSessions[0].id);
        else createNewChat(true);
    } else {
        renderSidebar();
    }
}

function updateSessionTitle(firstPrompt, targetSessionId) {
    const session = chatSessions.find(s => s.id === targetSessionId);
    if (session && session.title === 'New Conversation') {
        session.title = firstPrompt.length > 25 ? firstPrompt.substring(0, 25) + '...' : firstPrompt;
        localStorage.setItem('gp_sessions', JSON.stringify(chatSessions));
        renderSidebar();
        scheduleSessionSave();
    }
}

// =============================================================================
// CHAT
// =============================================================================

function updateUIState(isRunning) {
    const input     = document.getElementById('prompt');
    const sendBtn   = document.getElementById('sendBtn');
    const cancelBtn = document.getElementById('cancelBtn');
    const loading   = document.getElementById('loading');
    if (!input || !sendBtn || !cancelBtn || !loading) return;

    if (isRunning) {
        input.disabled         = true;
        sendBtn.style.display  = 'none';
        cancelBtn.style.display = 'block';
        loading.style.display  = 'block';
        loading.textContent    = 'Connecting to agent...';
        updateHeaderStatus('running');
    } else {
        input.disabled          = false;
        sendBtn.style.display   = 'block';
        cancelBtn.style.display = 'none';
        loading.style.display   = 'none';
        updateHeaderStatus('online');
    }
}

async function sendPrompt() {
    const input  = document.getElementById('prompt');
    const prompt = input.value.trim();
    if (!prompt) return;

    const targetSessionId = currentSessionId;
    saveMessageToStorage(targetSessionId, prompt, 'user-message', false);
    updateSessionTitle(prompt, targetSessionId);

    if (currentSessionId === targetSessionId) {
        addMessageToDOM(prompt, 'user-message', false, new Date());
        input.value        = '';
        input.style.height = 'auto';
        updateUIState(true);
    }

    activeRequests[targetSessionId] = new AbortController();
    renderSidebar();

    const loadingPhases = ['Analyzing request...', 'Constructing queries...', 'Retrieving data...', 'Formulating insights...'];
    let phaseIndex = 0;
    const loadingInterval = setInterval(() => {
        if (currentSessionId === targetSessionId) {
            const el = document.getElementById('loading');
            if (el && el.style.display === 'block') el.textContent = loadingPhases[phaseIndex++ % loadingPhases.length];
        }
    }, 2000);

    try {
        const config  = safeParse('gp_config', { data: {} }).data || {};
        const history = safeParse('gp_chat_ui_' + targetSessionId, [])
            .slice(-30)
            .map(m => ({ role: m.className === 'user-message' ? 'user' : 'assistant',
                         content: stripLoneSurrogates(m.text || '') }));

        const response = await fetch('/api/chat', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                prompt,
                userId:    CURRENT_USER_ID,
                sessionId: targetSessionId,
                config,
                history
            }),
            signal: activeRequests[targetSessionId].signal
        });

        if (!response.ok) {
            let serverMsg = null;
            try { const d = await response.json(); serverMsg = d?.response; } catch (_) {}
            const err = new Error('Server returned HTTP ' + response.status);
            err.serverMsg = serverMsg;
            throw err;
        }

        const data = await response.json();
        const aiText = (data && typeof data.response === 'string') ? data.response
                     : '⚠️ Server returned an unexpected response. Please try again.';
        saveMessageToStorage(targetSessionId, aiText, 'ai-message', true);
        if (currentSessionId === targetSessionId) addMessageToDOM(aiText, 'ai-message', true, new Date());

        suggestionHistory.add(prompt);
        localStorage.setItem('gp_history', JSON.stringify(Array.from(suggestionHistory)));
        scheduleSessionSave();

    } catch (error) {
        console.error('[sendPrompt] error:', error);
        const errText = error.name === 'AbortError' ? '⚠️ Request cancelled by user.'
                      : (error.serverMsg || 'Error connecting to backend API.');
        saveMessageToStorage(targetSessionId, errText, 'ai-message', false);
        if (currentSessionId === targetSessionId) {
            addMessageToDOM(errText, 'ai-message', false, new Date());
            if (error.name !== 'AbortError') updateHeaderStatus('offline');
        }
    } finally {
        clearInterval(loadingInterval);
        delete activeRequests[targetSessionId];
        renderSidebar();
        if (currentSessionId === targetSessionId) {
            updateUIState(false);
            document.getElementById('prompt').focus();
        }
    }
}

function cancelRequest() {
    if (activeRequests[currentSessionId]) {
        activeRequests[currentSessionId].abort();
        delete activeRequests[currentSessionId];
        updateUIState(false);
        renderSidebar();
    }
}

function saveMessageToStorage(targetSessionId, text, className, isMarkdown) {
    // Sanitize before storing — lone surrogates cause JSON.stringify to throw in Chrome 72+
    const safeText = stripLoneSurrogates(typeof text === 'string' ? text : (text ?? ''));
    let history = safeParse('gp_chat_ui_' + targetSessionId, []);
    history.push({ text: safeText, className, isMarkdown });
    try {
        localStorage.setItem('gp_chat_ui_' + targetSessionId, JSON.stringify(history));
    } catch (e) {
        // Last-resort fallback: strip all non-ASCII if JSON.stringify still fails
        console.warn('[storage] JSON.stringify failed, retrying with ASCII-only text:', e.message);
        history[history.length - 1].text = safeText.replace(/[^\x09\x0A\x0D\x20-\x7E]/g, '');
        try {
            localStorage.setItem('gp_chat_ui_' + targetSessionId, JSON.stringify(history));
        } catch (e2) {
            console.error('[storage] Could not persist message even with ASCII fallback:', e2);
        }
    }
    if (targetSessionId === currentSessionId) currentChatUiHistory = history;
    scheduleSessionSave();
}

let _msgSeq = 0;

function addMessageToDOM(text, className, isMarkdown, timestamp) {
    const messagesDiv = document.getElementById('messages');
    const wrapperDiv  = document.createElement('div');
    const uniqueId    = 'msg-' + (++_msgSeq);
    wrapperDiv.id        = uniqueId;
    wrapperDiv.className = `message-wrapper ${className === 'user-message' ? 'wrapper-user' : 'wrapper-ai'}`;

    const msgDiv = document.createElement('div');
    msgDiv.className = `message ${className}`;
    wrapperDiv.appendChild(msgDiv);

    if (isMarkdown) {
        try {
            let processedText  = text;
            const chartCaches  = [];
            const chartRegex   = /```chart\s*([\s\S]*?)\s*```/g;
            let match;
            while ((match = chartRegex.exec(text)) !== null) {
                const uid = 'graph-' + Math.random().toString(36).substring(2, 9);
                chartCaches.push({ id: uid, config: match[1].trim() });
                processedText = processedText.replace(match[0], `<div class="chart-wrapper"><canvas id="${uid}"></canvas></div>`);
            }

            const rawHtml    = marked.parse(processedText);
            msgDiv.innerHTML = (typeof DOMPurify !== 'undefined')
                ? DOMPurify.sanitize(rawHtml, { ADD_TAGS: ['canvas'], ADD_ATTR: ['id', 'class', 'style'] })
                : rawHtml;

            msgDiv.querySelectorAll('table').forEach(table => {
                try {
                    const wrap = document.createElement('div');
                    wrap.className = 'table-responsive';
                    table.parentNode.insertBefore(wrap, table);
                    wrap.appendChild(table);
                } catch (_) {}
            });

            msgDiv.querySelectorAll('pre code').forEach(block => {
                try {
                    if (typeof hljs !== 'undefined') hljs.highlightElement(block);
                    const copyBtn     = document.createElement('button');
                    copyBtn.innerHTML = '📋 Copy';
                    copyBtn.className = 'copy-btn';
                    copyBtn.onclick   = () => {
                        navigator.clipboard.writeText(block.innerText).then(() => {
                            copyBtn.innerHTML = '✅ Copied!';
                            setTimeout(() => { copyBtn.innerHTML = '📋 Copy'; }, 2000);
                        });
                    };
                    block.parentNode.appendChild(copyBtn);
                } catch (_) {}
            });

            chartCaches.forEach(c => setTimeout(() => constructSimpleGraph(c.id, c.config), 50));
        } catch (renderErr) {
            console.error('[addMessageToDOM] render failed, falling back to plain text:', renderErr);
            msgDiv.textContent = text;
        }
    } else {
        msgDiv.textContent = text;
    }

    if (timestamp) {
        const timeEl = document.createElement('span');
        timeEl.className = 'msg-time';
        timeEl.textContent = timestamp.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
        wrapperDiv.appendChild(timeEl);
    }

    if (className === 'user-message') {
        const favBtn = document.createElement('button');
        favBtn.innerHTML = '⭐ Favourite';
        favBtn.title     = 'Save as favourite';
        favBtn.style.cssText = 'margin-top:4px; background:transparent; border:1px solid #fbbf24; color:#b45309; padding:4px 10px; border-radius:4px; cursor:pointer; font-size:0.75em; align-self:flex-end; opacity:0.7; transition:opacity 0.2s;';
        favBtn.onmouseover = () => favBtn.style.opacity = '1';
        favBtn.onmouseout  = () => favBtn.style.opacity = '0.7';
        const capturedText = text;
        favBtn.onclick = () => openSaveFavModal(capturedText);
        wrapperDiv.appendChild(favBtn);
    }

    if (className === 'ai-message' && !text.includes('⚠️ Request cancelled') && !text.includes('Error connecting')) {
        const hasTables  = wrapperDiv.querySelectorAll('table').length > 0;
        const noTableTip = 'No table data in this response';

        const dropWrap = document.createElement('div');
        dropWrap.style.cssText = 'position:relative; display:inline-block; margin-top:6px;';

        const triggerBtn = document.createElement('button');
        triggerBtn.innerHTML = '⬇ Export ▾';
        triggerBtn.style.cssText = 'background:var(--surface-bg); border:1px solid var(--border-subtle); color:var(--muted-text); padding:3px 8px; border-radius:4px; cursor:pointer; font-size:0.72em; transition:opacity 0.1s; opacity:0.75;';
        triggerBtn.onmouseenter = () => triggerBtn.style.opacity = '1';
        triggerBtn.onmouseleave = () => triggerBtn.style.opacity = '0.75';

        const menu = document.createElement('div');
        menu.className = 'export-dd-menu';
        menu.style.cssText = 'position:absolute; top:calc(100% + 4px); left:0; z-index:200; background:var(--surface-bg); border:1px solid var(--border-subtle); border-radius:6px; box-shadow:0 4px 16px rgba(0,0,0,0.18); min-width:150px; display:none; overflow:hidden;';

        const mkItem = (label, enabled) => {
            const it = document.createElement('button');
            it.innerHTML = label;
            it.style.cssText = `display:block; width:100%; padding:7px 14px; text-align:left; background:transparent; border:none; border-bottom:1px solid var(--border-subtle); cursor:${enabled ? 'pointer' : 'not-allowed'}; font-size:0.8em; color:var(--muted-text); opacity:${enabled ? '1' : '0.4'}; transition:background 0.1s;`;
            it.disabled = !enabled;
            if (!enabled) it.title = noTableTip;
            if (enabled) {
                it.onmouseenter = () => it.style.background = 'var(--sidebar-bg)';
                it.onmouseleave = () => it.style.background = 'transparent';
            }
            return it;
        };

        const pdfItem  = mkItem('⬇ Export as PDF', true);
        const csvItem  = mkItem('⬇ Export as CSV', hasTables);
        const xlsItem  = mkItem('⬇ Export as XLS', hasTables);
        const copyItem = mkItem('⎘ Copy Table', hasTables);
        copyItem.style.borderBottom = 'none';

        pdfItem.onclick  = () => { closeExportDropdowns(); exportSinglePDF(uniqueId, triggerBtn); };
        if (hasTables) {
            csvItem.onclick  = () => { closeExportDropdowns(); exportCSV(uniqueId, triggerBtn); };
            xlsItem.onclick  = () => { closeExportDropdowns(); exportXLS(uniqueId, triggerBtn); };
            copyItem.onclick = () => { closeExportDropdowns(); copyTableTSV(uniqueId, triggerBtn); };
        }

        [pdfItem, csvItem, xlsItem, copyItem].forEach(it => menu.appendChild(it));

        triggerBtn.onclick = e => {
            e.stopPropagation();
            const isOpen = menu.style.display === 'block';
            closeExportDropdowns();
            if (!isOpen) menu.style.display = 'block';
        };

        dropWrap.appendChild(triggerBtn);
        dropWrap.appendChild(menu);
        wrapperDiv.appendChild(dropWrap);
    }

    messagesDiv.appendChild(wrapperDiv);
    messagesDiv.scrollTop = messagesDiv.scrollHeight;
}

function constructSimpleGraph(canvasId, configStr) {
    try {
        const cfg = JSON.parse(configStr);
        new Chart(document.getElementById(canvasId).getContext('2d'), {
            type: cfg.type || 'bar',
            data: { labels: cfg.labels, datasets: cfg.datasets },
            options: { responsive: true, maintainAspectRatio: false }
        });
    } catch (err) {}
}

async function exportSinglePDF(wrapperId, btnElement) {
    const originalText   = btnElement.innerHTML;
    btnElement.innerHTML = '⏳ Generating...';
    btnElement.disabled  = true;

    let viewport   = null;
    let savedTheme = null;

    const restoreTheme = (theme) => {
        if (theme) document.documentElement.setAttribute('data-theme', theme);
        else document.documentElement.removeAttribute('data-theme');
    };

    try {
        const aiWrapper = document.getElementById(wrapperId);
        if (!aiWrapper) throw new Error('AI wrapper not found');

        // Walk backwards to find the nearest preceding user message
        let userWrapper = aiWrapper.previousElementSibling;
        while (userWrapper && !userWrapper.classList.contains('wrapper-user')) {
            userWrapper = userWrapper.previousElementSibling;
        }
        const queryText = (userWrapper && userWrapper.querySelector('.message'))
            ? (userWrapper.querySelector('.message').innerText.trim() || 'Data Query')
            : 'Data Query';

        // Clone the AI message div — leave the live DOM untouched
        const aiNode = aiWrapper.querySelector('.message').cloneNode(true);
        aiNode.querySelectorAll('.copy-btn, button').forEach(el => el.remove());

        // Replace canvas elements with PNG snapshots of the live versions
        const liveCanvases   = aiWrapper.querySelectorAll('canvas');
        const clonedCanvases = aiNode.querySelectorAll('canvas');
        liveCanvases.forEach((live, i) => {
            try {
                const cloned = clonedCanvases[i];
                if (!cloned) return;
                const img = document.createElement('img');
                img.src = live.toDataURL('image/png');
                img.style.cssText = 'max-width:100%; height:auto; display:block; margin:8px 0;';
                cloned.parentNode.replaceChild(img, cloned);
            } catch (_) {}
        });

        // Build filename
        const safeName = queryText.replace(/[^a-zA-Z0-9\s]/g, '').trim()
                                   .substring(0, 40).trim().replace(/\s+/g, '-') || 'report';
        const dateStr  = new Date().toISOString().slice(0, 10);
        const filename = `greenplum-${safeName}-${dateStr}.pdf`;
        const nowStr   = new Date().toLocaleString();

        // Build the PDF HTML — all colors are hard-coded (no CSS variables)
        const pdfHtml = `
<div style="font-family:Arial,sans-serif;font-size:13px;color:#1a2e1f;padding:12px 16px;background:#ffffff;width:100%;">

  <div style="border-bottom:2px solid #2d6a4f;padding-bottom:12px;margin-bottom:18px;display:flex;align-items:center;gap:12px;">
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100" width="30" height="30">
      <circle cx="50" cy="50" r="47" fill="none" stroke="#78be20" stroke-width="9"/>
      <circle cx="50" cy="50" r="35" fill="#78be20"/>
      <path d="M 46 16 C 26 16 14 32 14 50 C 14 68 27 83 46 83 C 58 83 67 76 69 65 L 50 50 Z" fill="white"/>
    </svg>
    <div>
      <div style="font-size:19px;font-weight:bold;color:#2d6a4f;line-height:1.2;">Greenplum AI Analytics Report</div>
      <div style="font-size:11px;color:#4b7a5e;margin-top:3px;">Generated: ${nowStr}</div>
    </div>
  </div>

  <div style="background:#f0fdf4;border-left:4px solid #2d6a4f;padding:10px 14px;margin-bottom:20px;border-radius:0 4px 4px 0;">
    <div style="font-size:10px;font-weight:700;color:#4b7a5e;text-transform:uppercase;letter-spacing:0.8px;margin-bottom:5px;">Query</div>
    <div style="font-size:13px;color:#1a2e1f;line-height:1.5;">${queryText}</div>
  </div>

  <style>
    *{box-sizing:border-box;}
    p,li,span,strong,em{color:#1a2e1f!important;}
    a{color:#2d6a4f!important;}
    h1,h2,h3,h4,h5,h6{color:#2d6a4f!important;margin:14px 0 6px;}
    pre{background:#f5f9f6!important;border:1px solid #bbf7d0!important;border-radius:4px!important;padding:12px!important;white-space:pre-wrap!important;word-break:break-all!important;margin:10px 0!important;}
    code{color:#0f4c2a!important;background:#f5f9f6!important;font-family:monospace!important;font-size:12px!important;}
    table{border-collapse:collapse!important;width:100%!important;margin:12px 0!important;font-size:12px!important;}
    th{background:#2d6a4f!important;color:#ffffff!important;padding:9px 11px!important;text-align:left!important;}
    td{padding:7px 11px!important;border:1px solid #bbf7d0!important;color:#1a2e1f!important;background:#ffffff!important;}
    tr:nth-child(even) td{background:#f0fdf4!important;}
    .table-responsive{overflow:visible!important;}
    .copy-btn,button{display:none!important;}
    blockquote{border-left:4px solid #86efac!important;background:#f0fdf4!important;padding:8px 14px!important;margin:10px 0!important;}
    img{max-width:100%!important;height:auto!important;}
  </style>

  <div style="line-height:1.75;color:#1a2e1f;">
    ${aiNode.innerHTML}
  </div>

  <div style="border-top:1px solid #bbf7d0;margin-top:28px;padding-top:8px;font-size:10px;color:#4b7a5e;text-align:center;">
    Greenplum AI Analytics Agent &mdash; Confidential
  </div>
</div>`;

        // Page-by-page rendering — prevents blank PDF caused by canvas height limits on large documents.
        // A single html2canvas pass at scale 2 on a 36-page doc exceeds the ~16,384px browser canvas
        // limit, producing a blank image. Instead we render one A4 page at a time through a fixed
        // overflow:hidden viewport and slide the inner content upward for each page.
        const A4_W = 794;   // ≈ 210mm at 96dpi
        const A4_H = 1123;  // ≈ 297mm at 96dpi

        viewport = document.createElement('div');
        viewport.style.cssText = 'position:fixed;left:-9999px;top:0;width:' + A4_W + 'px;height:' + A4_H + 'px;overflow:hidden;background:#ffffff;';

        const inner = document.createElement('div');
        inner.style.cssText = 'position:absolute;top:0;left:0;width:100%;background:#ffffff;';
        inner.innerHTML = pdfHtml;
        viewport.appendChild(inner);
        document.body.appendChild(viewport);

        savedTheme = document.documentElement.getAttribute('data-theme');
        document.documentElement.removeAttribute('data-theme');

        // Let the browser lay out the content before measuring
        await new Promise(r => setTimeout(r, 120));

        const totalHeight = inner.scrollHeight;
        const totalPages  = Math.max(1, Math.ceil(totalHeight / A4_H));
        const pdf = new window.jspdf.jsPDF({ unit: 'mm', format: 'a4', orientation: 'portrait' });

        for (let page = 0; page < totalPages; page++) {
            inner.style.top = -(page * A4_H) + 'px';
            await new Promise(r => setTimeout(r, 30));
            const canvas = await window.html2canvas(viewport, {
                scale:           2,
                useCORS:         true,
                logging:         false,
                backgroundColor: '#ffffff',
                width:           A4_W,
                height:          A4_H
            });
            const imgData = canvas.toDataURL('image/jpeg', 0.92);
            if (page > 0) pdf.addPage('a4', 'portrait');
            pdf.addImage(imgData, 'JPEG', 0, 0, 210, 297);
        }

        document.body.removeChild(viewport);
        viewport = null;
        restoreTheme(savedTheme);
        pdf.save(filename);
        btnElement.innerHTML = '✅ Downloaded!';
        setTimeout(() => { btnElement.innerHTML = originalText; btnElement.disabled = false; }, 2000);

    } catch (err) {
        if (viewport && document.body.contains(viewport)) document.body.removeChild(viewport);
        restoreTheme(savedTheme);
        console.error('[PDF] Generation failed:', err);
        btnElement.innerHTML = '❌ Failed — try again';
        setTimeout(() => { btnElement.innerHTML = originalText; btnElement.disabled = false; }, 2500);
    }
}

// =============================================================================
// TABLE EXPORT — CSV / XLS / COPY AS TSV
// =============================================================================

function closeExportDropdowns() {
    document.querySelectorAll('.export-dd-menu').forEach(m => m.style.display = 'none');
}
document.addEventListener('click', closeExportDropdowns);

function extractTables(wrapperId) {
    const el = document.getElementById(wrapperId);
    if (!el) return [];
    const result = [];
    el.querySelectorAll('table').forEach(table => {
        const rows = [];
        const headers = [...table.querySelectorAll('thead tr th')].map(th => th.innerText.trim());
        if (headers.length) rows.push(headers);
        table.querySelectorAll('tbody tr').forEach(tr => {
            const cells = [...tr.querySelectorAll('td')].map(td => td.innerText.trim());
            if (cells.length) rows.push(cells);
        });
        if (rows.length) result.push(rows);
    });
    return result;
}

function getExportFilename(wrapperId, ext) {
    const el = document.getElementById(wrapperId);
    let userWrapper = el ? el.previousElementSibling : null;
    while (userWrapper && !userWrapper.classList.contains('wrapper-user')) {
        userWrapper = userWrapper.previousElementSibling;
    }
    const queryText = userWrapper?.querySelector('.message')?.innerText?.trim() || 'data';
    const safeName  = queryText.replace(/[^a-zA-Z0-9\s]/g, '').trim().substring(0, 40).trim().replace(/\s+/g, '-') || 'data';
    const dateStr   = new Date().toISOString().slice(0, 10);
    return `greenplum-${safeName}-${dateStr}.${ext}`;
}

function copyTableTSV(wrapperId, btn) {
    const tables = extractTables(wrapperId);
    if (!tables.length) return;
    const tsv  = tables.map(rows => rows.map(row => row.join('\t')).join('\n')).join('\n\n');
    const orig = btn.innerHTML;
    const done = () => { btn.innerHTML = orig; btn.disabled = false; };
    const mark = () => { btn.innerHTML = '✅ Copied!'; btn.disabled = true; setTimeout(done, 2000); };
    navigator.clipboard.writeText(tsv).then(mark).catch(() => {
        const ta = document.createElement('textarea');
        ta.value = tsv; ta.style.cssText = 'position:fixed;opacity:0;';
        document.body.appendChild(ta); ta.select(); document.execCommand('copy'); document.body.removeChild(ta);
        mark();
    });
}

function exportCSV(wrapperId, btn) {
    const tables = extractTables(wrapperId);
    if (!tables.length) return;
    const esc = v => `"${String(v).replace(/"/g, '""')}"`;
    const csv = tables.map((rows, i) =>
        (tables.length > 1 ? `# Table ${i + 1}\n` : '') + rows.map(r => r.map(esc).join(',')).join('\n')
    ).join('\n\n');
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
    const url  = URL.createObjectURL(blob);
    const a    = document.createElement('a');
    a.href = url; a.download = getExportFilename(wrapperId, 'csv'); a.click();
    URL.revokeObjectURL(url);
    const orig = btn.innerHTML;
    btn.innerHTML = '✅ Downloaded!'; btn.disabled = true;
    setTimeout(() => { btn.innerHTML = orig; btn.disabled = false; }, 2000);
}

function exportXLS(wrapperId, btn) {
    if (typeof XLSX === 'undefined') {
        btn.innerHTML = '❌ Library not loaded';
        setTimeout(() => { btn.innerHTML = '⬇ XLS'; btn.disabled = false; }, 2500);
        return;
    }
    const tables = extractTables(wrapperId);
    if (!tables.length) return;
    const wb = XLSX.utils.book_new();
    tables.forEach((rows, i) => {
        const ws = XLSX.utils.aoa_to_sheet(rows);
        const colWidths = rows[0]?.map((_, ci) => ({ wch: Math.max(...rows.map(r => (r[ci] || '').length), 8) }));
        if (colWidths) ws['!cols'] = colWidths;
        XLSX.utils.book_append_sheet(wb, ws, tables.length > 1 ? `Sheet${i + 1}` : 'Data');
    });
    XLSX.writeFile(wb, getExportFilename(wrapperId, 'xlsx'));
    const orig = btn.innerHTML;
    btn.innerHTML = '✅ Downloaded!'; btn.disabled = true;
    setTimeout(() => { btn.innerHTML = orig; btn.disabled = false; }, 2000);
}

// =============================================================================
// SETTINGS
// =============================================================================

function openSettings() {
    const testResult = document.getElementById('testResult');
    if (testResult) { testResult.style.display = 'none'; testResult.className = 'test-result'; testResult.textContent = ''; }

    const stored = safeParse('gp_config', { data: {} });
    ['provider', 'baseUrl', 'apiKey', 'modelName', 'systemPrompt', 'mcpUrl', 'mcpAuth', 'omMcpUrl', 'omMcpAuth'].forEach(id => {
        if (document.getElementById(id) && stored.data[id] !== undefined) {
            document.getElementById(id).value = stored.data[id];
        }
    });

    // Restore mode dropdown and wire change listener
    const activeModeEl = document.getElementById('activeMode');
    if (activeModeEl) activeModeEl.value = (stored.data && stored.data.activeMode) || 'greenplum';
    attachModeListener();
    toggleMcpFields();

    toggleProviderFields();
    document.getElementById('settingsModal').style.display = 'flex';
}

function closeSettings() {
    document.getElementById('settingsModal').style.display = 'none';
}

async function saveSettings() {
    const saveBtn      = document.querySelector('.btn-save');
    const originalText = saveBtn.textContent;
    saveBtn.textContent = '⏳ Saving...';
    saveBtn.disabled    = true;

    try {
        const payload = { userId: CURRENT_USER_ID };
        ['provider', 'baseUrl', 'apiKey', 'modelName', 'systemPrompt', 'mcpUrl', 'mcpAuth', 'omMcpUrl', 'omMcpAuth'].forEach(id => {
            const el = document.getElementById(id);
            if (el) payload[id] = el.value.trim();
        });
        const activeModeEl = document.getElementById('activeMode');
        payload.activeMode = activeModeEl ? activeModeEl.value : 'greenplum';

        // Write to browser localStorage
        const dataOnly = Object.fromEntries(Object.entries(payload).filter(([k]) => k !== 'userId'));
        localStorage.setItem('gp_config', JSON.stringify({ data: dataOnly }));

        // Write to server JSON file
        const response = await fetch('/api/settings', {
            method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload)
        });
        if (!response.ok) throw new Error('Server rejected');

        closeSettings();
        updateModeBadge();
        testConnection(false);
    } catch (error) {
        const testResult = document.getElementById('testResult');
        if (testResult) {
            testResult.style.display = 'block';
            testResult.className     = 'test-result test-error';
            testResult.textContent   = '❌ Failed to save configuration to server.';
        }
    } finally {
        saveBtn.textContent = originalText;
        saveBtn.disabled    = false;
    }
}

// =============================================================================
// DELETE ALL DATA
// =============================================================================

function deleteAllData() {
    document.getElementById('deleteConfirmModal').style.display = 'flex';
}
function cancelDeleteAll() {
    document.getElementById('deleteConfirmModal').style.display = 'none';
}
async function confirmDeleteAll() {
    document.getElementById('deleteConfirmModal').style.display = 'none';
    // Only delete memory files — config.json (credentials + PIN) is preserved
    try {
        await fetch('/api/memory/clear', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userId: CURRENT_USER_ID })
        });
    } catch (e) {}
    clearAllLocalData();
    await createNewChat(true);
    showReconnectBanner();
}

function showReconnectBanner() {
    const messagesDiv = document.getElementById('messages');
    if (!messagesDiv) return;
    const banner = document.createElement('div');
    banner.className = 'message-wrapper wrapper-ai';
    banner.innerHTML = `<div class="message ai-message">
        ✅ <strong>Chat history cleared.</strong> Your credentials and settings are still active.
        How can I help you today?
    </div>`;
    messagesDiv.appendChild(banner);
    messagesDiv.scrollTop = messagesDiv.scrollHeight;
}

async function clearAllServerData() {
    try {
        await fetch('/api/data/clear', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userId: CURRENT_USER_ID })
        });
    } catch (e) {}
}

function clearAllLocalData() {
    const keysToRemove = ['gp_sessions', 'gp_current_session', 'gp_history'];
    Object.keys(localStorage)
        .filter(k => k.startsWith('gp_chat_ui_'))
        .forEach(k => localStorage.removeItem(k));
    keysToRemove.forEach(k => localStorage.removeItem(k));

    chatSessions         = [];
    currentSessionId     = null;
    currentChatUiHistory = [];
    activeRequests       = {};
    updateHeaderStatus('offline');
}

// =============================================================================
// CONNECTION TEST & AUTO-CONNECT
// =============================================================================

// customLabel may contain HTML (e.g. <span class="svc-err">Greenplum</span>)
function updateHeaderStatus(state, customLabel) {
    const dot  = document.getElementById('headerStatusDot');
    const text = document.getElementById('headerStatusText');
    if (!dot || !text) return;
    const states = {
        'testing': ['status-testing', 'Testing...'],
        'running': ['status-testing', 'Running...'],
        'online':  ['status-online',  CF_MODE && CF_MODEL_LABEL ? CF_MODEL_LABEL : 'Connected'],
        'partial': ['status-partial', 'Partial'],
        'offline': ['status-offline', 'Disconnected']
    };
    dot.className = 'status-dot ' + (states[state]?.[0] || 'status-unknown');
    const label = customLabel || states[state]?.[1] || 'Disconnected';
    // Use innerHTML only when the label contains HTML markup
    if (label.includes('<')) {
        text.innerHTML = label;
    } else {
        text.textContent = label;
    }
}

async function testConnection(isFromModal = false) {
    updateHeaderStatus('testing');

    let payload = {};
    if (isFromModal) {
        ['provider', 'baseUrl', 'apiKey', 'modelName', 'mcpUrl', 'mcpAuth', 'omMcpUrl', 'omMcpAuth'].forEach(id => {
            if (document.getElementById(id)) payload[id] = document.getElementById(id).value.trim();
        });
        const activeModeEl2 = document.getElementById('activeMode');
        payload.activeMode = activeModeEl2 ? activeModeEl2.value : 'greenplum';
        const testResult = document.getElementById('testResult');
        if (testResult) {
            testResult.style.display = 'block';
            testResult.className     = 'test-result';
            testResult.textContent   = '⏳ Testing connection... (Awaiting server response)';
        }
    } else {
        payload = safeParse('gp_config', { data: {} }).data || {};
        if (!payload.modelName) { updateHeaderStatus('offline'); return; }
    }

    try {
        const controller = new AbortController();
        const timeoutId  = setTimeout(() => controller.abort(), 90000);

        const res  = await fetch('/api/test', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload), signal: controller.signal
        });
        clearTimeout(timeoutId);

        const data = await res.json();
        updateHeaderStatus(data.status === 'success' ? 'online' : 'offline');

        if (isFromModal) {
            const testResult = document.getElementById('testResult');
            if (testResult) {
                testResult.innerHTML = '';
                testResult.className = 'test-result ' + (data.status === 'success' ? 'test-success' : 'test-error');
                testResult.style.display = 'block';

                // AI model line
                const modelLine = document.createElement('div');
                modelLine.textContent = (data.modelStatus === 'success' ? '✅' : '❌')
                    + ' AI Model: ' + (data.modelMessage || data.message || '');
                modelLine.style.color = data.modelStatus === 'success' ? '#065f46' : '#991b1b';
                testResult.appendChild(modelLine);

                // Greenplum MCP line
                if (data.mcpStatus && data.mcpStatus !== 'skipped') {
                    const mcpLine = document.createElement('div');
                    mcpLine.style.marginTop = '6px';
                    mcpLine.textContent = (data.mcpStatus === 'success' ? '✅' : '❌')
                        + ' Greenplum MCP: ' + data.mcpMessage;
                    mcpLine.style.color = data.mcpStatus === 'success' ? '#065f46' : '#991b1b';
                    testResult.appendChild(mcpLine);
                }
                // OpenMetadata MCP line
                if (data.omMcpStatus && data.omMcpStatus !== 'skipped') {
                    const omLine = document.createElement('div');
                    omLine.style.marginTop = '6px';
                    omLine.textContent = (data.omMcpStatus === 'success' ? '✅' : '❌')
                        + ' OpenMetadata MCP: ' + data.omMcpMessage;
                    omLine.style.color = data.omMcpStatus === 'success' ? '#065f46' : '#991b1b';
                    testResult.appendChild(omLine);
                }
            }
        }
    } catch (e) {
        updateHeaderStatus('offline');
        if (isFromModal) {
            const testResult = document.getElementById('testResult');
            if (testResult) {
                testResult.className        = 'test-result test-error';
                testResult.style.display    = 'block';
                testResult.textContent      = e.name === 'AbortError'
                    ? '❌ Request Timed Out. If using a Local Model, it might be loading into memory. Try again in a minute.'
                    : '❌ Connection Failed: Could not reach the backend server.';
            }
        }
    }
}

function setCfDotsState(state) {
    const cls = { testing: 'status-testing', online: 'status-online', offline: 'status-offline' }[state] || 'status-unknown';
    const modelDot = document.getElementById('headerStatusDot');
    if (modelDot) modelDot.className = 'status-dot ' + cls;
    ['Greenplum', 'OpenMetadata'].forEach(name => {
        const dot = document.getElementById('mcp-dot-' + name);
        if (dot) dot.className = 'status-dot ' + cls;
    });
}

async function autoConnect() {
    if (CF_MODE) {
        try {
            setCfDotsState('testing');
            const res  = await fetch('/api/test/cf');
            const data = await res.json();
            buildCfStatusLabel(data);
        } catch (e) {
            setCfDotsState('offline');
            // Still show model name (or "Model") even when unreachable
            const modelText = document.getElementById('headerStatusText');
            if (modelText) modelText.textContent = CF_MODEL_LABEL || 'Model';
            const modelIndicator = document.getElementById('modelIndicator');
            if (modelIndicator) modelIndicator.dataset.tip = CF_MODEL_LABEL || 'Model';
        }
        return;
    }
    const stored = safeParse('gp_config', { data: {} });
    if (stored && stored.data && stored.data.modelName) testConnection(false);
    else updateHeaderStatus('offline');
}

// =============================================================================
// MCP SECTION TOGGLE + MODE BADGE
// =============================================================================

function toggleMcpFields() {
    var sel = document.getElementById('activeMode');
    var mode = sel ? sel.value : 'greenplum';
    var gpSection = document.getElementById('gpMcpSection');
    var omSection = document.getElementById('omMcpSection');
    if (gpSection) gpSection.style.display = (mode === 'openmetadata') ? 'none' : 'block';
    if (omSection) omSection.style.display = (mode === 'greenplum')    ? 'none' : 'block';
}

function attachModeListener() {
    var sel = document.getElementById('activeMode');
    if (sel) {
        sel.removeEventListener('change', toggleMcpFields);
        sel.addEventListener('change', toggleMcpFields);
    }
}

function updateModeBadge() {
    // In CF mode the MCP dot indicators replace this badge
    if (CF_MODE) return;
    const stored = safeParse('gp_config', { data: {} });
    const mode   = (stored.data && stored.data.activeMode) || 'greenplum';
    const badge  = document.getElementById('headerModeBadge');
    if (!badge) return;
    const labels = { greenplum: 'Greenplum', openmetadata: 'OpenMetadata', both: 'GP + OM' };
    badge.textContent = labels[mode] || 'Greenplum';
    badge.style.display = 'inline';
}

// =============================================================================
// PROVIDER FIELD HINTS
// =============================================================================

function toggleProviderFields() {
    const provider       = document.getElementById('provider').value;
    const baseUrlLabel   = document.getElementById('baseUrlLabel');
    const baseUrlHelp    = document.getElementById('baseUrlHelp');
    const apiKeyHelp     = document.getElementById('apiKeyHelp');
    const modelNameHelp  = document.getElementById('modelNameHelp');
    if (!baseUrlLabel) return;

    if (provider === 'ollama') {
        baseUrlLabel.textContent  = 'Ollama Server URL';
        baseUrlHelp.textContent   = 'Format: http://localhost:11434';
        apiKeyHelp.textContent    = 'Leave blank (Ollama does not require an API key)';
        modelNameHelp.textContent = 'Format: qwen3:30b, llama3';
    } else if (provider === 'openai') {
        baseUrlLabel.textContent  = 'OpenAI Compatible Base URL';
        baseUrlHelp.textContent   = 'Format: https://api.openai.com/v1';
        apiKeyHelp.textContent    = 'Format: sk-... (Enter API Key if required)';
        modelNameHelp.textContent = 'Format: gpt-4o, llama-3.1-70b';
    } else if (provider === 'anthropic') {
        baseUrlLabel.textContent  = 'Anthropic Base URL';
        baseUrlHelp.textContent   = 'Format: https://api.anthropic.com/v1';
        apiKeyHelp.textContent    = 'Format: sk-ant-...';
        modelNameHelp.textContent = 'Format: claude-3-5-sonnet-20241022';
    } else {
        baseUrlLabel.textContent  = 'Endpoint / Base URL';
        baseUrlHelp.textContent   = 'Select a provider to see format';
        apiKeyHelp.textContent    = 'Select a provider to see format';
        modelNameHelp.textContent = 'Select a provider to see format';
    }
}

// =============================================================================
// CONFIG FILE UPLOAD
// =============================================================================

function handleConfigUpload(event) {
    const file = event.target.files[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = function (e) {
        const content = e.target.result;
        const config  = {};
        content.split('\n').forEach(line => {
            line = line.trim();
            if (line && !line.startsWith('#')) {
                const idx = line.indexOf('=');
                if (idx > 0) config[line.substring(0, idx).trim()] = line.substring(idx + 1).trim();
            }
        });

        if (config.provider)     document.getElementById('provider').value     = config.provider.toLowerCase();
        if (config.baseUrl)      document.getElementById('baseUrl').value       = config.baseUrl;
        if (config.apiKey)       document.getElementById('apiKey').value        = config.apiKey;
        if (config.modelName)    document.getElementById('modelName').value     = config.modelName;
        if (config.systemPrompt) document.getElementById('systemPrompt').value  = config.systemPrompt;
        if (config.mcpUrl)       document.getElementById('mcpUrl').value        = config.mcpUrl;
        if (config.mcpAuth)      document.getElementById('mcpAuth').value       = config.mcpAuth;
        if (config.omMcpUrl)     document.getElementById('omMcpUrl').value      = config.omMcpUrl;
        if (config.omMcpAuth)    document.getElementById('omMcpAuth').value     = config.omMcpAuth;

        const activeModeEl = document.getElementById('activeMode');
        if (activeModeEl) {
            if (config.activeMode) {
                activeModeEl.value = config.activeMode;
            } else {
                // Auto-infer from which MCP URLs are present
                if (config.mcpUrl && config.omMcpUrl)   activeModeEl.value = 'both';
                else if (config.omMcpUrl)                activeModeEl.value = 'openmetadata';
                else                                     activeModeEl.value = 'greenplum';
            }
        }
        toggleMcpFields();
        toggleProviderFields();
        const testResult = document.getElementById('testResult');
        if (testResult) {
            testResult.style.display = 'block';
            testResult.className     = 'test-result test-success';
            testResult.textContent   = '✅ File loaded! Review the fields and click "Save Configuration".';
        }
        event.target.value = '';
    };
    reader.readAsText(file);
}

// =============================================================================
// TEXTAREA / SUGGESTIONS
// =============================================================================

let debounceTimeout = null;

function setupTextarea() {
    const input = document.getElementById('prompt');
    const sgBox = document.getElementById('suggestionBox');

    input.addEventListener('input', function () {
        this.style.height = 'auto';
        this.style.height = this.scrollHeight + 'px';

        const val = this.value.toLowerCase();
        if (debounceTimeout) clearTimeout(debounceTimeout);

        debounceTimeout = setTimeout(() => {
            sgBox.innerHTML = '';
            let matches = 0;
            if (val.trim().length > 0) {
                const fragment = document.createDocumentFragment();
                for (let item of suggestionHistory) {
                    if (matches >= 5) break;
                    if (item.toLowerCase().includes(val)) {
                        const div       = document.createElement('div');
                        div.className   = 'suggestion-item';
                        div.innerText   = item;
                        div.onclick     = () => {
                            input.value        = item;
                            sgBox.style.display = 'none';
                            input.style.height = 'auto';
                            input.style.height = input.scrollHeight + 'px';
                            input.focus();
                        };
                        fragment.appendChild(div);
                        matches++;
                    }
                }
                sgBox.appendChild(fragment);
            }
            sgBox.style.display = matches > 0 ? 'block' : 'none';
        }, 50);
    });

    input.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            sgBox.style.display = 'none';
            sendPrompt();
        }
    });

    document.addEventListener('click', (e) => {
        if (e.target !== input && e.target !== sgBox) sgBox.style.display = 'none';
    });
}
