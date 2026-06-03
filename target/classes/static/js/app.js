// --- 1. Bulletproof Memory Loading ---
function safeParse(key, defaultVal) {
    try {
        const val = localStorage.getItem(key);
        return val ? JSON.parse(val) : defaultVal;
    } catch (e) {
        localStorage.removeItem(key);
        return defaultVal;
    }
}

// Global State
let activeRequests = {}; 
let suggestionHistory = new Set();
let chatSessions = safeParse('gp_sessions', []);
if (!Array.isArray(chatSessions)) chatSessions = [];
let currentSessionId = localStorage.getItem('gp_current_session');
let currentChatUiHistory = [];

window.onload = function() {
    try {
        const savedHistory = safeParse('gp_history', []);
        suggestionHistory = new Set([
            "Check bloat in the 'sales' table",
            "Show cluster status",
            ...Array.isArray(savedHistory) ? savedHistory : []
        ]);
        setupTextarea();
        initSessions();
        autoConnect();
    } catch (err) {}
};

function initSessions() {
    if (!currentSessionId || chatSessions.length === 0) createNewChat(false);
    else loadSession(currentSessionId);
}

async function createNewChat(render = true) {
    if (chatSessions.length >= 4) {
        const oldestSession = chatSessions.pop();
        localStorage.removeItem('gp_chat_ui_' + oldestSession.id);
        if (activeRequests[oldestSession.id]) {
            activeRequests[oldestSession.id].abort();
            delete activeRequests[oldestSession.id];
        }
        try {
            await fetch('/api/chat/clear', { 
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ userId: oldestSession.id }) 
            });
        } catch (e) {}
    }

    const newId = 'session-' + Date.now();
    chatSessions.unshift({ id: newId, title: 'New Conversation' });
    localStorage.setItem('gp_sessions', JSON.stringify(chatSessions));
    
    if (render) {
        loadSession(newId);
        renderSidebar();
    } else {
        currentSessionId = newId;
        localStorage.setItem('gp_current_session', currentSessionId);
        renderSidebar();
    }
}

function loadSession(sessionId) {
    currentSessionId = sessionId;
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
        div.textContent = session.title;
        if (activeRequests[session.id]) div.innerHTML = `⏳ ` + session.title;
        div.onclick = () => loadSession(session.id);
        chatList.appendChild(div);
    });
}

function updateSessionTitle(firstPrompt, targetSessionId) {
    const session = chatSessions.find(s => s.id === targetSessionId);
    if (session && session.title === 'New Conversation') {
        session.title = firstPrompt.length > 30 ? firstPrompt.substring(0, 30) + '...' : firstPrompt;
        localStorage.setItem('gp_sessions', JSON.stringify(chatSessions));
        renderSidebar();
    }
}

async function clearChat() {
    if (activeRequests[currentSessionId]) {
        activeRequests[currentSessionId].abort();
        delete activeRequests[currentSessionId];
        updateUIState(false);
    }
    document.getElementById('messages').innerHTML = `<div class="message-wrapper wrapper-ai"><div class="message ai-message">Chat history cleared. How can I help you?</div></div>`;
    currentChatUiHistory = [];
    localStorage.removeItem('gp_chat_ui_' + currentSessionId);
    try {
        await fetch('/api/chat/clear', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ userId: currentSessionId }) });
    } catch (e) {}
}

function updateUIState(isRunning) {
    const input = document.getElementById('prompt');
    const sendBtn = document.getElementById('sendBtn');
    const cancelBtn = document.getElementById('cancelBtn');
    const loading = document.getElementById('loading');
    if (!input || !sendBtn || !cancelBtn || !loading) return; 

    if (isRunning) {
        input.disabled = true;
        sendBtn.style.display = 'none';
        cancelBtn.style.display = 'block';
        loading.style.display = 'block';
        loading.textContent = "Connecting to agent..."; 
        updateHeaderStatus('running');
    } else {
        input.disabled = false;
        sendBtn.style.display = 'block';
        cancelBtn.style.display = 'none';
        loading.style.display = 'none';
        updateHeaderStatus('online'); 
    }
}

async function sendPrompt() {
    const input = document.getElementById('prompt');
    const prompt = input.value.trim();
    if (!prompt) return;

    const targetSessionId = currentSessionId;
    saveMessageToStorage(targetSessionId, prompt, 'user-message', false);
    updateSessionTitle(prompt, targetSessionId); 
    
    if (currentSessionId === targetSessionId) {
        addMessageToDOM(prompt, 'user-message', false);
        input.value = '';
        input.style.height = 'auto';
        updateUIState(true);
    }
    
    activeRequests[targetSessionId] = new AbortController();
    renderSidebar(); 

    const loadingPhases = ["Analyzing request...", "Constructing queries...", "Retrieving data...", "Formulating insights..."];
    let phaseIndex = 0;
    const loadingInterval = setInterval(() => {
        if (currentSessionId === targetSessionId) {
            const loadingEl = document.getElementById('loading');
            if (loadingEl && loadingEl.style.display === 'block') loadingEl.textContent = loadingPhases[phaseIndex++ % loadingPhases.length];
        }
    }, 2000);

    try {
        const response = await fetch('/api/chat', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ prompt: prompt, userId: targetSessionId }), 
            signal: activeRequests[targetSessionId].signal
        });
        
        const data = await response.json();
        saveMessageToStorage(targetSessionId, data.response, 'ai-message', true);
        if (currentSessionId === targetSessionId) addMessageToDOM(data.response, 'ai-message', true);
        
        suggestionHistory.add(prompt);
        localStorage.setItem('gp_history', JSON.stringify(Array.from(suggestionHistory)));

    } catch (error) {
        const errorText = error.name === 'AbortError' ? '⚠️ Request cancelled by user.' : 'Error connecting to backend API.';
        saveMessageToStorage(targetSessionId, errorText, 'ai-message', false);
        if (currentSessionId === targetSessionId) {
            addMessageToDOM(errorText, 'ai-message', false);
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
    let history = safeParse('gp_chat_ui_' + targetSessionId, []);
    history.push({ text: text, className: className, isMarkdown: isMarkdown });
    localStorage.setItem('gp_chat_ui_' + targetSessionId, JSON.stringify(history));
    if (targetSessionId === currentSessionId) currentChatUiHistory = history;
}

function addMessageToDOM(text, className, isMarkdown) {
    const messagesDiv = document.getElementById('messages');
    const wrapperDiv = document.createElement('div');
    const uniqueId = 'msg-' + Date.now();
    wrapperDiv.id = uniqueId;
    wrapperDiv.className = `message-wrapper ${className === 'user-message' ? 'wrapper-user' : 'wrapper-ai'}`;
    
    const msgDiv = document.createElement('div');
    msgDiv.className = `message ${className}`;
    wrapperDiv.appendChild(msgDiv);
    
    if (isMarkdown) {
        let processedText = text;
        const chartCaches = [];
        const chartRegex = /```chart\s*([\s\S]*?)\s*```/g;
        let match;
        while ((match = chartRegex.exec(text)) !== null) {
            const uniqueChartId = 'graph-' + Math.random().toString(36).substring(2, 9);
            chartCaches.push({ id: uniqueChartId, config: match[1].trim() });
            processedText = processedText.replace(match[0], `<div class="chart-wrapper"><canvas id="${uniqueChartId}"></canvas></div>`);
        }
        
        msgDiv.innerHTML = marked.parse(processedText);
        msgDiv.querySelectorAll('table').forEach(table => {
            const responsiveWrap = document.createElement('div');
            responsiveWrap.className = 'table-responsive';
            table.parentNode.insertBefore(responsiveWrap, table);
            responsiveWrap.appendChild(table);
        });

        msgDiv.querySelectorAll('pre code').forEach((block) => {
            hljs.highlightElement(block);
            const copyBtn = document.createElement('button');
            copyBtn.innerHTML = '📋 Copy';
            copyBtn.className = 'copy-btn';
            copyBtn.onclick = () => {
                navigator.clipboard.writeText(block.innerText).then(() => {
                    copyBtn.innerHTML = '✅ Copied!';
                    setTimeout(() => { copyBtn.innerHTML = '📋 Copy'; }, 2000);
                });
            };
            block.parentNode.appendChild(copyBtn);
        });

        chartCaches.forEach(c => setTimeout(() => constructSimpleGraph(c.id, c.config), 50));
    } else {
        msgDiv.textContent = text;
    }
    
    if (className === 'ai-message' && !text.includes('⚠️ Request cancelled') && !text.includes('Error connecting')) {
        const downloadBtn = document.createElement('button');
        downloadBtn.innerHTML = '⬇️ Download Response PDF';
        downloadBtn.style.cssText = 'margin-top: 5px; background: transparent; border: 1px solid #cbd5e1; color: #475569; padding: 6px 12px; border-radius: 4px; cursor: pointer; font-size: 0.8em; align-self: flex-start; transition: background 0.2s;';
        downloadBtn.onclick = () => exportSinglePDF(uniqueId, downloadBtn);
        wrapperDiv.appendChild(downloadBtn);
    }
    
    messagesDiv.appendChild(wrapperDiv);
    messagesDiv.scrollTop = messagesDiv.scrollHeight;
}

function constructSimpleGraph(canvasId, configStr) {
    try {
        const cfg = JSON.parse(configStr);
        new Chart(document.getElementById(canvasId).getContext('2d'), { 
            type: cfg.type || 'bar', data: { labels: cfg.labels, datasets: cfg.datasets }, options: { responsive: true, maintainAspectRatio: false } 
        });
    } catch (err) {}
}

function exportSinglePDF(wrapperId, btnElement) {
    const originalText = btnElement.innerHTML;
    btnElement.innerHTML = "⏳ Generating...";
    btnElement.disabled = true;

    const aiWrapper = document.getElementById(wrapperId);
    const userWrapper = aiWrapper.previousElementSibling;
    const queryText = (userWrapper && userWrapper.classList.contains('wrapper-user')) ? userWrapper.querySelector('.message').innerText : "Data Query";

    const aiNode = aiWrapper.querySelector('.message').cloneNode(true);
    aiNode.querySelectorAll('.copy-btn').forEach(btn => btn.remove());
    
    const htmlContent = `<div style="font-family: sans-serif; padding: 20px;"><h2>Query: ${queryText}</h2>${aiNode.innerHTML}</div>`;
    html2pdf().from(htmlContent).save().then(() => {
        btnElement.innerHTML = originalText; btnElement.disabled = false;
    });
}

let debounceTimeout = null;
function setupTextarea() {
    const input = document.getElementById('prompt');
    const sgBox = document.getElementById('suggestionBox');

    input.addEventListener('input', function() {
        this.style.height = 'auto';
        this.style.height = (this.scrollHeight) + 'px';
        
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
                        const div = document.createElement('div');
                        div.className = 'suggestion-item';
                        div.innerText = item;
                        div.onclick = () => { 
                            input.value = item; 
                            sgBox.style.display = 'none'; 
                            input.style.height = 'auto';
                            input.style.height = (input.scrollHeight) + 'px';
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

    input.addEventListener('keydown', function(e) {
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

function updateHeaderStatus(state) {
    const dot = document.getElementById('headerStatusDot');
    const text = document.getElementById('headerStatusText');
    if (!dot || !text) return; 
    const states = { 'testing': ['status-testing', 'Testing...'], 'running': ['status-testing', 'Running...'], 'online': ['status-online', 'Connected'], 'offline': ['status-offline', 'Disconnected'] };
    dot.className = 'status-dot ' + (states[state]?.[0] || 'status-unknown');
    text.textContent = states[state]?.[1] || 'Disconnected';
}

function attemptOpenSettings() {
    if (sessionStorage.getItem('gp_admin') === 'true') openSettings();
    else document.getElementById('loginModal').style.display = 'flex';
}
function closeLoginModal() { document.getElementById('loginModal').style.display = 'none'; }
async function performLogin() {
    const u = document.getElementById('authUsername').value.trim();
    const p = document.getElementById('authPassword').value.trim();
    if(u === 'admin' && p === 'admin') { sessionStorage.setItem('gp_admin', 'true'); closeLoginModal(); openSettings(); }
    else document.getElementById('loginError').style.display = 'block';
}

async function openSettings() {
    const testResult = document.getElementById('testResult');
    if (testResult) { testResult.style.display = 'none'; testResult.className = 'test-result'; testResult.textContent = ''; }
    try {
        const stored = safeParse('gp_config', { data: {} });

        ['provider','baseUrl','apiKey','modelName','mcpUrl','mcpAuth'].forEach(id => {
            if(document.getElementById(id) && stored.data[id]) document.getElementById(id).value = stored.data[id];
        });
        toggleProviderFields();
    } catch(e) {}
    document.getElementById('settingsModal').style.display = 'flex';
}

function closeSettings() { document.getElementById('settingsModal').style.display = 'none'; }

async function saveSettings() {
    const saveBtn = document.querySelector('.btn-save');
    const originalText = saveBtn.textContent;
    saveBtn.textContent = '⏳ Saving...';
    saveBtn.disabled = true;

    try {
        const payload = {};
        ['provider','baseUrl','apiKey','modelName','mcpUrl','mcpAuth'].forEach(id => {
            const element = document.getElementById(id);
            if (element) payload[id] = element.value.trim();
        });
        
        localStorage.setItem('gp_config', JSON.stringify({ data: payload, expiry: Date.now() + 2592000000 }));
        
        const response = await fetch('/api/settings', { 
            method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) 
        });

        if (!response.ok) throw new Error("Server rejected");
        closeSettings(); 
        testConnection(false); 
    } catch (error) {
        const testResult = document.getElementById('testResult');
        if (testResult) {
            testResult.style.display = 'block';
            testResult.className = 'test-result test-error';
            testResult.textContent = '❌ Failed to save configuration to server.';
        }
    } finally {
        saveBtn.textContent = originalText;
        saveBtn.disabled = false;
    }
}

async function testConnection(isFromModal = false) {
    updateHeaderStatus('testing');
    
    let payload = {};
    if (isFromModal) {
        ['provider','baseUrl','apiKey','modelName','mcpUrl','mcpAuth'].forEach(id => {
            if(document.getElementById(id)) payload[id] = document.getElementById(id).value.trim();
        });
        
        const testResult = document.getElementById('testResult');
        if (testResult) {
            testResult.style.display = 'block';
            testResult.className = 'test-result';
            testResult.textContent = '⏳ Testing connection... (Awaiting server response)';
        }
    } else {
        const stored = safeParse('gp_config', { data: {} });
        payload = stored.data || {};
        if (Object.keys(payload).length === 0) {
            updateHeaderStatus('offline');
            return;
        }
    }

    try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 90000); // 90 seconds hard stop

        const res = await fetch('/api/test', { 
            method: 'POST', 
            headers: { 'Content-Type': 'application/json' }, 
            body: JSON.stringify(payload),
            signal: controller.signal
        });
        
        clearTimeout(timeoutId);
        const data = await res.json();
        updateHeaderStatus(data.status === 'success' ? 'online' : 'offline');
        
        if (isFromModal) {
            const testResult = document.getElementById('testResult');
            if (testResult) {
                testResult.className = 'test-result ' + (data.status === 'success' ? 'test-success' : 'test-error');
                testResult.textContent = data.status === 'success' ? '✅ Connection Successful! AI is ready.' : '❌ ' + data.message;
            }
        }
    } catch(e) { 
        updateHeaderStatus('offline'); 
        if (isFromModal) {
            const testResult = document.getElementById('testResult');
            if (testResult) {
                testResult.className = 'test-result test-error';
                testResult.textContent = e.name === 'AbortError' 
                    ? '❌ Request Timed Out. If using a Local Model, it might be heavily loading into memory. Try again in a minute.' 
                    : '❌ Connection Failed: Could not reach the backend server.';
            }
        }
    }
}

async function autoConnect() {
    const stored = safeParse('gp_config', null);
    if (stored && Date.now() < stored.expiry) testConnection(false); 
}

function toggleProviderFields() {
    const provider = document.getElementById('provider').value;
    const baseUrlLabel = document.getElementById('baseUrlLabel');
    const baseUrlHelp = document.getElementById('baseUrlHelp');
    const apiKeyHelp = document.getElementById('apiKeyHelp');
    const modelNameHelp = document.getElementById('modelNameHelp');

    if (!baseUrlLabel) return; 

    if (provider === 'ollama') {
        baseUrlLabel.textContent = 'Ollama Server URL';
        baseUrlHelp.textContent = 'Format: http://localhost:11434';
        apiKeyHelp.textContent = 'Leave blank (Ollama does not require an API key)';
        modelNameHelp.textContent = 'Format: qwen3:30b, llama3';
    } else if (provider === 'openai') {
        baseUrlLabel.textContent = 'OpenAI Compatible Base URL';
        baseUrlHelp.textContent = 'Format: https://api.openai.com/v1';
        apiKeyHelp.textContent = 'Format: sk-... (Enter API Key if required)';
        modelNameHelp.textContent = 'Format: gpt-4o, llama-3.1-70b';
    } else if (provider === 'anthropic') {
        baseUrlLabel.textContent = 'Anthropic Base URL';
        baseUrlHelp.textContent = 'Format: https://api.anthropic.com/v1';
        apiKeyHelp.textContent = 'Format: sk-ant-...';
        modelNameHelp.textContent = 'Format: claude-3-5-sonnet-20241022';
    } else {
        baseUrlLabel.textContent = 'Endpoint / Base URL';
        baseUrlHelp.textContent = 'Select a provider to see format';
        apiKeyHelp.textContent = 'Select a provider to see format';
        modelNameHelp.textContent = 'Select a provider to see format';
    }
}

function handleConfigUpload(event) {
    const file = event.target.files[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = function(e) {
        const content = e.target.result;
        const config = {};
        content.split('\n').forEach(line => {
            line = line.trim();
            if (line && !line.startsWith('#')) {
                const splitIdx = line.indexOf('=');
                if (splitIdx > 0) {
                    config[line.substring(0, splitIdx).trim()] = line.substring(splitIdx + 1).trim();
                }
            }
        });
        
        if (config.provider) document.getElementById('provider').value = config.provider.toLowerCase();
        if (config.baseUrl) document.getElementById('baseUrl').value = config.baseUrl;
        if (config.apiKey) document.getElementById('apiKey').value = config.apiKey;
        if (config.modelName) document.getElementById('modelName').value = config.modelName;
        if (config.mcpUrl) document.getElementById('mcpUrl').value = config.mcpUrl;
        if (config.mcpAuth) document.getElementById('mcpAuth').value = config.mcpAuth;

        toggleProviderFields();
        const testResult = document.getElementById('testResult');
        if (testResult) {
            testResult.style.display = 'block'; 
            testResult.className = 'test-result test-success';
            testResult.textContent = '✅ File loaded! Review the fields and click "Save Configuration".';
        }
        event.target.value = '';
    };
    reader.readAsText(file);
}