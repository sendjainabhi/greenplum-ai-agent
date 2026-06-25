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
    btn.textContent = isDark ? '☀️ Light Mode' : '🌙 Dark Mode';
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

let CURRENT_USER_ID = localStorage.getItem('gp_user_id') || null;

// =============================================================================
// PIN  (SHA-256 via Web Crypto; hash stored in browser AND on server)
// =============================================================================

async function hashPin(pin) {
    const buf = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(pin));
    return Array.from(new Uint8Array(buf)).map(b => b.toString(16).padStart(2, '0')).join('');
}


function isSessionUnlocked() {
    return localStorage.getItem('gp_unlocked') === 'true';
}

function markSessionUnlocked() {
    localStorage.setItem('gp_unlocked', 'true');
}

function clearUnlockedState() {
    localStorage.removeItem('gp_unlocked');
    localStorage.removeItem('gp_credential_hash');
}

// Silently verify the cached PIN hash against the server on every boot.
// Returns: true = valid, false = invalid (stale/deleted), null = network error (offline)
async function silentVerifyWithServer() {
    const hash = localStorage.getItem('gp_credential_hash');
    if (!CURRENT_USER_ID || !hash) return false;
    try {
        const res  = await fetch('/api/auth/verify', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userId: CURRENT_USER_ID, pinHash: hash })
        });
        const data = await res.json();
        return data.success === true;
    } catch (e) {
        return null; // network error — treat as offline, do not force sign-out
    }
}

// --- First-visit PIN setup ---
function showPinSetup() {
    document.getElementById('pinSetupModal').style.display = 'flex';
    setTimeout(() => document.getElementById('setupUsername').focus(), 100);
}

async function confirmSetupPin() {
    const username = document.getElementById('setupUsername').value.trim();
    const pin      = document.getElementById('setupPin').value;
    const confirm  = document.getElementById('setupPinConfirm').value;
    const hint     = document.getElementById('setupPinHint').value.trim();
    const errEl    = document.getElementById('pinSetupError');
    const btn      = document.querySelector('#pinSetupModal .btn-save');

    errEl.style.display = 'none';

    if (!username || !/^[a-zA-Z0-9_-]{3,50}$/.test(username)) {
        errEl.textContent = 'Username must be 3–50 characters (letters, numbers, - or _).';
        errEl.style.display = 'block'; return;
    }
    if (!pin || pin.length < 4) {
        errEl.textContent = 'PIN must be at least 4 characters.';
        errEl.style.display = 'block'; return;
    }
    if (pin !== confirm) {
        errEl.textContent = 'PINs do not match.';
        errEl.style.display = 'block'; return;
    }

    btn.textContent = '⏳ Saving...'; btn.disabled = true;
    const hash = await hashPin(pin);

    try {
        const res  = await fetch('/api/auth/setup', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userId: username, pinHash: hash, pinHint: hint })
        });
        const data = await res.json();
        if (!data.success) {
            errEl.textContent = data.error || 'Failed to save PIN to server.';
            errEl.style.display = 'block'; return;
        }
    } catch (e) {
        errEl.textContent = 'Could not reach the server. Please ensure the server is running.';
        errEl.style.display = 'block'; return;
    } finally {
        btn.textContent = 'Create PIN'; btn.disabled = false;
    }

    CURRENT_USER_ID = username;
    localStorage.setItem('gp_user_id', username);
    localStorage.setItem('gp_credential_hash', hash);
    localStorage.setItem('gp_pin_hint', hint);
    markSessionUnlocked();
    document.getElementById('pinSetupModal').style.display = 'none';
    bootApp();
}

// --- Returning-visit PIN entry ---
function showPinEntry() {
    document.getElementById('pinEntryModal').style.display = 'flex';
    setTimeout(() => document.getElementById('entryPin').focus(), 100);
}

async function confirmEntryPin() {
    const pin   = document.getElementById('entryPin').value;
    const errEl = document.getElementById('pinEntryError');
    const btn   = document.querySelector('#pinEntryModal .btn-save');
    errEl.style.display = 'none';

    if (!pin) {
        errEl.textContent = 'Please enter your PIN.';
        errEl.style.display = 'block'; return;
    }

    // Always verify against server — browser cache is never used to authenticate
    btn.textContent = '⏳ Verifying...'; btn.disabled = true;
    try {
        const hash = await hashPin(pin);
        const res  = await fetch('/api/auth/verify', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userId: CURRENT_USER_ID, pinHash: hash })
        });
        const data = await res.json();
        if (data.success) {
            localStorage.setItem('gp_credential_hash', hash); // update for silent boot verify only
            markSessionUnlocked();
            document.getElementById('pinEntryModal').style.display = 'none';
            document.getElementById('entryPin').value = '';
            bootApp();
        } else {
            localStorage.removeItem('gp_credential_hash'); // clear stale cache
            errEl.textContent = 'Incorrect PIN. Please try again.';
            errEl.style.display = 'block';
            document.getElementById('entryPin').value = '';
        }
    } catch (e) {
        errEl.textContent = 'Cannot reach server. Please check your connection.';
        errEl.style.display = 'block';
    } finally {
        btn.textContent = 'Unlock'; btn.disabled = false;
    }
}

// --- Account recovery (browser cache was cleared) ---
function showRecoverAccount() {
    document.getElementById('pinSetupModal').style.display = 'none';
    document.getElementById('recoverModal').style.display = 'flex';
    setTimeout(() => document.getElementById('recoverUsername').focus(), 100);
}

function cancelRecover() {
    document.getElementById('recoverModal').style.display = 'none';
    showPinSetup();
}

async function confirmRecover() {
    const username = document.getElementById('recoverUsername').value.trim();
    const pin      = document.getElementById('recoverPin').value;
    const errEl    = document.getElementById('recoverError');
    const btn      = document.querySelector('#recoverModal .btn-save');

    errEl.style.display = 'none';
    if (!username || !pin) {
        errEl.textContent = 'Username and PIN are required.';
        errEl.style.display = 'block'; return;
    }

    btn.textContent = '⏳ Verifying...'; btn.disabled = true;
    const hash = await hashPin(pin);
    let data;   // declared outside try so it's accessible after the block

    try {
        const res  = await fetch('/api/auth/verify', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userId: username, pinHash: hash })
        });
        data = await res.json();
        if (!data.success) {
            errEl.textContent = data.error || 'Verification failed.';
            errEl.style.display = 'block'; return;
        }
    } catch (e) {
        errEl.textContent = 'Could not reach the server.';
        errEl.style.display = 'block'; return;
    } finally {
        btn.textContent = 'Sign In'; btn.disabled = false;
    }

    // Restore identity — also restore pinHint so "Forgot PIN?" works on new browsers
    CURRENT_USER_ID = username;
    localStorage.setItem('gp_user_id', username);
    localStorage.setItem('gp_credential_hash', hash);
    if (data && data.pinHint) localStorage.setItem('gp_pin_hint', data.pinHint);
    markSessionUnlocked();
    document.getElementById('recoverModal').style.display = 'none';
    document.getElementById('recoverPin').value = '';
    bootApp();
}

// --- Forgot PIN ---
function showForgotPin() {
    document.getElementById('pinEntryModal').style.display = 'none';
    const hint = localStorage.getItem('gp_pin_hint') || '';
    const box  = document.getElementById('pinHintDisplay');
    box.textContent = hint.trim() ? hint : 'No hint was set for this PIN.';
    document.getElementById('forgotPinModal').style.display = 'flex';
}

function backToPinEntry() {
    document.getElementById('forgotPinModal').style.display = 'none';
    showPinEntry();
}

function showResetConfirm() {
    document.getElementById('forgotPinModal').style.display = 'none';
    document.getElementById('resetConfirmModal').style.display = 'flex';
}

function cancelReset() {
    document.getElementById('resetConfirmModal').style.display = 'none';
    showPinEntry();
}

async function executeReset() {
    await clearAllServerData();
    clearAllLocalData(true);
    document.getElementById('resetConfirmModal').style.display = 'none';
    window.location.reload();
}

// --- Change PIN (inside settings) ---
async function changePin() {
    const current  = document.getElementById('changePinCurrent').value;
    const newPin   = document.getElementById('changePinNew').value;
    const confirm  = document.getElementById('changePinConfirm').value;
    const newHint  = document.getElementById('changePinHint').value.trim();
    const errEl    = document.getElementById('changePinError');

    errEl.style.display = 'none';
    errEl.className     = 'test-result test-error';

    if (!current || !newPin) {
        errEl.textContent = 'Current and new PIN are required.';
        errEl.style.display = 'block'; return;
    }
    if (newPin.length < 4) {
        errEl.textContent = 'New PIN must be at least 4 characters.';
        errEl.style.display = 'block'; return;
    }
    if (newPin !== confirm) {
        errEl.textContent = 'New PINs do not match.';
        errEl.style.display = 'block'; return;
    }

    const currentHash = await hashPin(current);

    // Always verify current PIN against server (not just localStorage)
    try {
        const verifyRes  = await fetch('/api/auth/verify', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userId: CURRENT_USER_ID, pinHash: currentHash })
        });
        const verifyData = await verifyRes.json();
        if (!verifyData.success) {
            errEl.textContent = 'Current PIN is incorrect.';
            errEl.style.display = 'block'; return;
        }
    } catch (e) {
        errEl.textContent = 'Could not reach server to verify current PIN.';
        errEl.style.display = 'block'; return;
    }

    const newHash = await hashPin(newPin);

    // Save new PIN to server first, then update localStorage
    try {
        const res  = await fetch('/api/auth/setup', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ userId: CURRENT_USER_ID, pinHash: newHash, pinHint: newHint })
        });
        const data = await res.json();
        if (!data.success) throw new Error(data.error || 'Server error');
    } catch (e) {
        errEl.textContent = '❌ Failed to save new PIN to server: ' + e.message;
        errEl.style.display = 'block'; return;
    }

    localStorage.setItem('gp_credential_hash', newHash);
    if (newHint) localStorage.setItem('gp_pin_hint', newHint);

    ['changePinCurrent','changePinNew','changePinConfirm','changePinHint']
        .forEach(id => { document.getElementById(id).value = ''; });
    errEl.className   = 'test-result test-success';
    errEl.textContent = '✅ PIN updated successfully.';
    errEl.style.display = 'block';
}

// =============================================================================
// ADMIN — global pre-training prompt
// =============================================================================

let adminPinHashInSession = null; // keep admin hash in memory for this browser session

function openAdminModal() {
    adminPinHashInSession = null;
    document.getElementById('adminModal').style.display = 'flex';
    document.getElementById('adminAuthSection').style.display = 'block';
    document.getElementById('adminEditorSection').style.display = 'none';
    document.getElementById('adminPinInput').value = '';
    document.getElementById('adminAuthError').style.display = 'none';
    // Reset button — may have been left disabled after a previous successful verify
    const btn = document.getElementById('adminAuthBtn');
    btn.textContent = 'Verify & Enter';
    btn.disabled    = false;
    setTimeout(() => document.getElementById('adminPinInput').focus(), 100);
}

function closeAdminModal() {
    document.getElementById('adminModal').style.display = 'none';
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
            errEl.className = 'test-result test-error';
            errEl.textContent = data.error || 'Incorrect admin PIN.';
            errEl.style.display = 'block';
            btn.textContent = 'Verify & Enter'; btn.disabled = false;
            return;
        }

        // Verified — show editor with current global prompt
        adminPinHashInSession = hash;
        document.getElementById('globalPromptText').value = data.globalPrompt || '';
        document.getElementById('adminSaveResult').style.display = 'none';
        document.getElementById('adminAuthSection').style.display = 'none';
        document.getElementById('adminEditorSection').style.display = 'block';

    } catch (e) {
        errEl.className = 'test-result test-error';
        errEl.textContent = 'Could not reach server.';
        errEl.style.display = 'block';
        btn.textContent = 'Verify & Enter'; btn.disabled = false;
    }
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
        resultEl.className = 'test-result ' + (data.success ? 'test-success' : 'test-error');
        resultEl.textContent = data.success
            ? '✅ Global prompt saved. Applies to all users on the next request.'
            : '❌ ' + (data.error || 'Save failed.');
        resultEl.style.display = 'block';
    } catch (e) {
        resultEl.className = 'test-result test-error';
        resultEl.textContent = '❌ Could not reach server.';
        resultEl.style.display = 'block';
    }
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

// Ask the server whether a user is already registered on its filesystem.
// Falls back to localStorage if the server is unreachable (offline mode).
async function fetchAuthStatus() {
    try {
        const res = await fetch('/api/auth/status');
        return await res.json();
    } catch (e) {
        const cached = localStorage.getItem('gp_user_id');
        if (cached) return { registered: true, userId: cached };
        return { registered: false };
    }
}

window.onload = async function () {
    applyThemeLabel();
    try {
        // Always check server filesystem first — works in incognito & after cache clear
        const status = await fetchAuthStatus();

        if (!status.registered) {
            // No PIN on server yet — show create account (username + PIN, one time only)
            showPinSetup();
            return;
        }

        // User is registered on server — sync identity from server, no username input needed
        CURRENT_USER_ID = status.userId;
        localStorage.setItem('gp_user_id', status.userId);

        if (isSessionUnlocked()) {
            // Already verified earlier in this browser session — silent re-check
            const verified = await silentVerifyWithServer();
            if (verified === true) {
                bootApp();
            } else {
                clearUnlockedState();
                showPinEntry();
            }
        } else {
            // Require PIN (first open, incognito session, or after cache clear)
            showPinEntry();
        }
    } catch (e) {
        console.error('[boot] Unexpected error:', e);
        showPinEntry();
    }
};

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
        // Server unreachable — local state already in memory from module init
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
    initSessions();    // uses globals freshly populated from server
    loadFavourites();
    autoConnect();
}

// =============================================================================
// SESSION / SIDEBAR
// =============================================================================

let activeRequests    = {};
let suggestionHistory = new Set();
let chatSessions      = safeParse('gp_sessions', []);
if (!Array.isArray(chatSessions)) chatSessions = [];
let currentSessionId      = localStorage.getItem('gp_current_session');
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
        addMessageToDOM(prompt, 'user-message', false);
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
            throw new Error('Server returned HTTP ' + response.status);
        }

        const data = await response.json();
        const aiText = (data && typeof data.response === 'string') ? data.response
                     : '⚠️ Server returned an unexpected response. Please try again.';
        saveMessageToStorage(targetSessionId, aiText, 'ai-message', true);
        if (currentSessionId === targetSessionId) addMessageToDOM(aiText, 'ai-message', true);

        suggestionHistory.add(prompt);
        localStorage.setItem('gp_history', JSON.stringify(Array.from(suggestionHistory)));
        scheduleSessionSave();

    } catch (error) {
        console.error('[sendPrompt] error:', error);
        const errText = error.name === 'AbortError' ? '⚠️ Request cancelled by user.' : 'Error connecting to backend API.';
        saveMessageToStorage(targetSessionId, errText, 'ai-message', false);
        if (currentSessionId === targetSessionId) {
            addMessageToDOM(errText, 'ai-message', false);
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

function addMessageToDOM(text, className, isMarkdown) {
    const messagesDiv = document.getElementById('messages');
    const wrapperDiv  = document.createElement('div');
    const uniqueId    = 'msg-' + Date.now();
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
        const downloadBtn    = document.createElement('button');
        downloadBtn.innerHTML = '⬇ Export PDF';
        downloadBtn.style.cssText = 'margin-top:6px; background:var(--new-chat-btn-bg); border:1px solid var(--border-subtle); color:var(--muted-text); padding:6px 12px; border-radius:4px; cursor:pointer; font-size:0.8em; align-self:flex-start; transition:all 0.12s ease; box-shadow:0 2px 0 rgba(0,0,0,0.1),0 2px 6px rgba(0,0,0,0.06); transform:translateY(0);';
        downloadBtn.onmouseenter = () => { downloadBtn.style.transform='translateY(1px)'; downloadBtn.style.boxShadow='0 1px 0 rgba(0,0,0,0.1)'; };
        downloadBtn.onmouseleave = () => { downloadBtn.style.transform='translateY(0)'; downloadBtn.style.boxShadow='0 2px 0 rgba(0,0,0,0.1),0 2px 6px rgba(0,0,0,0.06)'; };
        downloadBtn.onmousedown  = () => { downloadBtn.style.transform='translateY(2px)'; downloadBtn.style.boxShadow='inset 0 1px 3px rgba(0,0,0,0.15)'; };
        downloadBtn.onmouseup    = () => { downloadBtn.style.transform='translateY(0)'; downloadBtn.style.boxShadow='0 2px 0 rgba(0,0,0,0.1),0 2px 6px rgba(0,0,0,0.06)'; };
        downloadBtn.onclick   = () => exportSinglePDF(uniqueId, downloadBtn);
        wrapperDiv.appendChild(downloadBtn);
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

function exportSinglePDF(wrapperId, btnElement) {
    const originalText   = btnElement.innerHTML;
    btnElement.innerHTML = '⏳ Generating...';
    btnElement.disabled  = true;

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

        const options = {
            margin:      [6, 6, 6, 6],
            filename:    filename,
            image:       { type: 'jpeg', quality: 0.98 },
            html2canvas: { scale: 2, useCORS: true, logging: false, backgroundColor: '#ffffff' },
            jsPDF:       { unit: 'mm', format: 'a4', orientation: 'portrait' }
        };

        // Temporarily switch to light mode so CSS variables resolve correctly in the render
        const savedTheme = document.documentElement.getAttribute('data-theme');
        document.documentElement.removeAttribute('data-theme');

        html2pdf().set(options).from(pdfHtml).save()
            .then(() => {
                restoreTheme(savedTheme);
                btnElement.innerHTML = '✅ Downloaded!';
                setTimeout(() => { btnElement.innerHTML = originalText; btnElement.disabled = false; }, 2000);
            })
            .catch(err => {
                restoreTheme(savedTheme);
                console.error('[PDF] Generation failed:', err);
                btnElement.innerHTML = '❌ Failed — try again';
                setTimeout(() => { btnElement.innerHTML = originalText; btnElement.disabled = false; }, 2500);
            });

    } catch (err) {
        console.error('[PDF] Setup failed:', err);
        btnElement.innerHTML = originalText;
        btnElement.disabled  = false;
    }
}

// =============================================================================
// SETTINGS
// =============================================================================

function openSettings() {
    const testResult = document.getElementById('testResult');
    if (testResult) { testResult.style.display = 'none'; testResult.className = 'test-result'; testResult.textContent = ''; }

    const stored = safeParse('gp_config', { data: {} });
    ['provider', 'baseUrl', 'apiKey', 'modelName', 'systemPrompt', 'mcpUrl', 'mcpAuth'].forEach(id => {
        if (document.getElementById(id) && stored.data[id] !== undefined) {
            document.getElementById(id).value = stored.data[id];
        }
    });
    toggleProviderFields();
    document.getElementById('settingsModal').style.display = 'flex';
}

function closeSettings() {
    document.getElementById('settingsModal').style.display = 'none';
    // Clear change-pin fields
    ['changePinCurrent','changePinNew','changePinConfirm','changePinHint'].forEach(id => {
        const el = document.getElementById(id);
        if (el) el.value = '';
    });
    const err = document.getElementById('changePinError');
    if (err) err.style.display = 'none';
}

async function saveSettings() {
    const saveBtn      = document.querySelector('.btn-save');
    const originalText = saveBtn.textContent;
    saveBtn.textContent = '⏳ Saving...';
    saveBtn.disabled    = true;

    try {
        const payload = { userId: CURRENT_USER_ID };
        ['provider', 'baseUrl', 'apiKey', 'modelName', 'systemPrompt', 'mcpUrl', 'mcpAuth'].forEach(id => {
            const el = document.getElementById(id);
            if (el) payload[id] = el.value.trim();
        });

        // Write to browser localStorage
        const dataOnly = Object.fromEntries(Object.entries(payload).filter(([k]) => k !== 'userId'));
        localStorage.setItem('gp_config', JSON.stringify({ data: dataOnly }));

        // Write to server JSON file
        const response = await fetch('/api/settings', {
            method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload)
        });
        if (!response.ok) throw new Error('Server rejected');

        closeSettings();
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
    clearAllLocalData(false);  // false = keep PIN
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

function clearAllLocalData(includingPin) {
    // Only remove chat history — credentials (gp_config) are preserved
    const keysToRemove = ['gp_sessions', 'gp_current_session', 'gp_history'];
    Object.keys(localStorage)
        .filter(k => k.startsWith('gp_chat_ui_'))
        .forEach(k => localStorage.removeItem(k));
    keysToRemove.forEach(k => localStorage.removeItem(k));

    if (includingPin) {
        localStorage.removeItem('gp_credential_hash');
        localStorage.removeItem('gp_pin_hint');
        localStorage.removeItem('gp_user_id');
        localStorage.removeItem('gp_unlocked');
        CURRENT_USER_ID = null;
    }

    // Reset in-memory state
    chatSessions         = [];
    currentSessionId     = null;
    currentChatUiHistory = [];
    activeRequests       = {};
    updateHeaderStatus('offline');
}

// =============================================================================
// CONNECTION TEST & AUTO-CONNECT
// =============================================================================

function updateHeaderStatus(state) {
    const dot  = document.getElementById('headerStatusDot');
    const text = document.getElementById('headerStatusText');
    if (!dot || !text) return;
    const states = {
        'testing': ['status-testing', 'Testing...'],
        'running': ['status-testing', 'Running...'],
        'online':  ['status-online',  'Connected'],
        'offline': ['status-offline', 'Disconnected']
    };
    dot.className   = 'status-dot ' + (states[state]?.[0] || 'status-unknown');
    text.textContent = states[state]?.[1] || 'Disconnected';
}

async function testConnection(isFromModal = false) {
    updateHeaderStatus('testing');

    let payload = {};
    if (isFromModal) {
        ['provider', 'baseUrl', 'apiKey', 'modelName', 'mcpUrl', 'mcpAuth'].forEach(id => {
            if (document.getElementById(id)) payload[id] = document.getElementById(id).value.trim();
        });
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
                if (data.modelStatus !== 'success') modelLine.style.color = '#dc2626';
                testResult.appendChild(modelLine);

                // MCP line (only when URL was provided)
                if (data.mcpStatus && data.mcpStatus !== 'skipped') {
                    const mcpLine = document.createElement('div');
                    mcpLine.style.marginTop = '6px';
                    mcpLine.textContent = (data.mcpStatus === 'success' ? '✅' : '❌')
                        + ' MCP Server: ' + data.mcpMessage;
                    if (data.mcpStatus !== 'success') mcpLine.style.color = '#dc2626';
                    testResult.appendChild(mcpLine);
                }
            }
        }
    } catch (e) {
        updateHeaderStatus('offline');
        if (isFromModal) {
            const testResult = document.getElementById('testResult');
            if (testResult) {
                testResult.className   = 'test-result test-error';
                testResult.textContent = e.name === 'AbortError'
                    ? '❌ Request Timed Out. If using a Local Model, it might be loading into memory. Try again in a minute.'
                    : '❌ Connection Failed: Could not reach the backend server.';
            }
        }
    }
}

async function autoConnect() {
    const stored = safeParse('gp_config', { data: {} });
    if (stored && stored.data && stored.data.modelName) testConnection(false);
    else updateHeaderStatus('offline');
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
