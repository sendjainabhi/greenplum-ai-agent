// --- 1. Smart Autocomplete & Global State ---
let suggestionHistory = new Set();
let currentAbortController = null;

window.onload = function() {
    const savedHistory = JSON.parse(localStorage.getItem('gp_history') || '[]');
    suggestionHistory = new Set([
        "Check bloat in the 'sales' table",
        "Show cluster status",
        "List all users in the 'public' schema",
        ...savedHistory
    ]);
    setupTextarea();
};

function setupTextarea() {
    const input = document.getElementById('prompt');
    const sgBox = document.getElementById('suggestionBox');

    input.addEventListener('input', function() {
        this.style.height = 'auto';
        this.style.height = (this.scrollHeight) + 'px';
        
        const val = this.value.toLowerCase();
        sgBox.innerHTML = '';
        let matches = 0;
        
        if (val.trim().length > 0) {
            suggestionHistory.forEach(item => {
                if (item.toLowerCase().includes(val) && matches < 5) {
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
                    sgBox.appendChild(div);
                    matches++;
                }
            });
        }
        sgBox.style.display = matches > 0 ? 'block' : 'none';
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

function clearChat() {
    const messagesDiv = document.getElementById('messages');
    messagesDiv.innerHTML = `
        <div class="message-wrapper wrapper-ai">
            <div class="message ai-message">Chat history cleared. How can I help you?</div>
        </div>
    `;
}

// --- 2. Security Gatekeeper Logic ---
function attemptOpenSettings() {
    if (sessionStorage.getItem('gp_admin') === 'true') {
        openSettings();
    } else {
        document.getElementById('authUsername').value = '';
        document.getElementById('authPassword').value = '';
        document.getElementById('loginError').style.display = 'none';
        document.getElementById('loginModal').style.display = 'flex';
        document.getElementById('authUsername').focus();
    }
}

function closeLoginModal() { document.getElementById('loginModal').style.display = 'none'; }

async function performLogin() {
    const u = document.getElementById('authUsername').value.trim();
    const p = document.getElementById('authPassword').value.trim();
    const errorDiv = document.getElementById('loginError');
    
    if(!u || !p) {
        errorDiv.textContent = 'Please enter both username and password.';
        errorDiv.style.display = 'block';
        return;
    }
    errorDiv.style.display = 'none';
    try {
        const response = await fetch('/api/auth/login', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ username: u, password: p })
        });
        const data = await response.json();
        if (data.success) {
            sessionStorage.setItem('gp_admin', 'true');
            closeLoginModal();
            openSettings();
        } else {
            errorDiv.textContent = 'Invalid admin credentials.';
            errorDiv.style.display = 'block';
        }
    } catch (e) {
        errorDiv.textContent = 'Server connection failed. Is the backend running?';
        errorDiv.style.display = 'block';
    }
}

// --- 3. Settings & Configuration ---
async function openSettings() {
    try {
        const response = await fetch('/api/settings');
        const settings = await response.json();
        
        document.getElementById('provider').value = settings.provider || '';
        document.getElementById('baseUrl').value = settings.baseUrl || '';
        document.getElementById('apiKey').value = settings.apiKey || '';
        document.getElementById('modelName').value = settings.modelName || '';
        document.getElementById('mcpUrl').value = settings.mcpUrl || '';
        document.getElementById('mcpAuth').value = settings.mcpAuth || '';
    } catch (e) {
        document.getElementById('provider').value = '';
        document.getElementById('baseUrl').value = '';
        document.getElementById('apiKey').value = '';
        document.getElementById('modelName').value = '';
        document.getElementById('mcpUrl').value = '';
        document.getElementById('mcpAuth').value = '';
    }
    
    document.getElementById('testResult').style.display = 'none';
    toggleProviderFields(); 
    document.getElementById('settingsModal').style.display = 'flex';
}

function closeSettings() { document.getElementById('settingsModal').style.display = 'none'; }

async function saveSettings() {
    const payload = {
        provider: document.getElementById('provider').value,
        baseUrl: document.getElementById('baseUrl').value.trim(),
        apiKey: document.getElementById('apiKey').value.trim(),
        modelName: document.getElementById('modelName').value.trim(),
        mcpUrl: document.getElementById('mcpUrl').value.trim(),
        mcpAuth: document.getElementById('mcpAuth').value.trim()
    };
    await fetch('/api/settings', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
    });
    closeSettings();
}

function toggleProviderFields() {
    const provider = document.getElementById('provider').value;
    const baseUrlLabel = document.getElementById('baseUrlLabel');
    
    const baseUrlHelp = document.getElementById('baseUrlHelp');
    const apiKeyHelp = document.getElementById('apiKeyHelp');
    const modelNameHelp = document.getElementById('modelNameHelp');

    if (provider === 'ollama') {
        baseUrlLabel.textContent = 'Ollama Server URL';
        baseUrlHelp.textContent = 'Format: http://localhost:11434';
        apiKeyHelp.textContent = 'Leave blank (Ollama does not require an API key)';
        modelNameHelp.textContent = 'Format: qwen3:30b, llama3';
    } else if (provider === 'openai') {
        baseUrlLabel.textContent = 'OpenAI Compatible Base URL';
        baseUrlHelp.textContent = 'Format: https://api.openai.com/v1 or http://localhost:1234/v1';
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
        testResult.style.display = 'block'; testResult.className = 'test-result test-success';
        testResult.textContent = '✅ File loaded! Review the fields and click "Save Configuration".';
        event.target.value = ''; 
    };
    reader.readAsText(file);
}

async function testConnection() {
    const testBtn = document.getElementById('testBtn');
    const resultDiv = document.getElementById('testResult');
    testBtn.disabled = true; testBtn.textContent = 'Testing...';
    updateHeaderStatus('testing');
    
    resultDiv.className = 'test-result'; resultDiv.textContent = 'Connecting to AI provider...'; resultDiv.style.display = 'block';

    const payload = {
        provider: document.getElementById('provider').value, baseUrl: document.getElementById('baseUrl').value.trim(),
        apiKey: document.getElementById('apiKey').value.trim(), modelName: document.getElementById('modelName').value.trim()
    };

    try {
        const response = await fetch('/api/test', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) });
        const data = await response.json();
        if (data.status === 'success') {
            resultDiv.textContent = '✅ ' + data.message; resultDiv.className = 'test-result test-success'; updateHeaderStatus('online');
        } else {
            resultDiv.textContent = '❌ ' + data.message; resultDiv.className = 'test-result test-error'; updateHeaderStatus('offline');
        }
    } catch (error) {
        resultDiv.textContent = '❌ Network error.'; resultDiv.className = 'test-result test-error'; updateHeaderStatus('offline');
    } finally {
        testBtn.disabled = false; testBtn.textContent = 'Test Connection';
    }
}

// --- 4. Chat & Formatted UI Logic ---
async function sendPrompt() {
    const input = document.getElementById('prompt');
    const sendBtn = document.getElementById('sendBtn');
    const cancelBtn = document.getElementById('cancelBtn');
    const prompt = input.value.trim();
    if (!prompt) return;

    addMessage(prompt, 'user-message', false);
    
    input.value = '';
    input.style.height = 'auto';
    input.disabled = true;
    
    sendBtn.style.display = 'none';
    cancelBtn.style.display = 'block';
    updateHeaderStatus('running');
    
    const loading = document.getElementById('loading');
    loading.style.display = 'block';
    
    const loadingPhases = ["Analyzing request...", "Constructing queries...", "Retrieving data...", "Formulating insights..."];
    let phaseIndex = 0;
    loading.textContent = "Connecting to agent...";
    const loadingInterval = setInterval(() => { loading.textContent = loadingPhases[phaseIndex++ % loadingPhases.length]; }, 2000);

    currentAbortController = new AbortController();

    try {
        const response = await fetch('/api/chat', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ prompt: prompt }), signal: currentAbortController.signal
        });
        
        const data = await response.json();
        addMessage(data.response, 'ai-message', true);
        updateHeaderStatus('online'); 
        
        suggestionHistory.add(prompt);
        localStorage.setItem('gp_history', JSON.stringify(Array.from(suggestionHistory)));

    } catch (error) {
        if (error.name === 'AbortError') {
            addMessage('⚠️ Request cancelled by user.', 'ai-message', false);
            updateHeaderStatus('online'); 
        } else {
            addMessage('Error connecting to backend API.', 'ai-message', false);
            updateHeaderStatus('offline'); 
        }
    } finally {
        clearInterval(loadingInterval);
        loading.style.display = 'none';
        sendBtn.style.display = 'block';
        cancelBtn.style.display = 'none';
        input.disabled = false;
        currentAbortController = null;
        input.focus(); 
    }
}

function cancelRequest() {
    if (currentAbortController) currentAbortController.abort();
}

// UPDATED: Now uses "Connected" and "Disconnected" terminology
function updateHeaderStatus(state) {
    const dot = document.getElementById('headerStatusDot');
    const text = document.getElementById('headerStatusText');
    
    let dotClass = 'status-unknown';
    let statusText = 'Disconnected'; // Default state

    if (state === 'testing') {
        dotClass = 'status-testing';
        statusText = 'Testing...';
    } else if (state === 'running') {
        dotClass = 'status-testing'; 
        statusText = 'Running...';
    } else if (state === 'online') {
        dotClass = 'status-online';
        statusText = 'Connected';
    } else if (state === 'offline') {
        dotClass = 'status-offline';
        statusText = 'Disconnected';
    }

    dot.className = 'status-dot ' + dotClass;
    text.textContent = statusText;
}

function addMessage(text, className, isMarkdown) {
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
            const preNode = block.parentNode;
            const copyBtn = document.createElement('button');
            copyBtn.innerHTML = '📋 Copy';
            copyBtn.className = 'copy-btn';
            
            copyBtn.onclick = () => {
                navigator.clipboard.writeText(block.innerText).then(() => {
                    copyBtn.innerHTML = '✅ Copied!';
                    setTimeout(() => { copyBtn.innerHTML = '📋 Copy'; }, 2000);
                });
            };
            preNode.appendChild(copyBtn);
        });

        messagesDiv.appendChild(wrapperDiv);
        chartCaches.forEach(c => setTimeout(() => constructSimpleGraph(c.id, c.config), 50));
    } else {
        msgDiv.textContent = text;
        messagesDiv.appendChild(wrapperDiv);
    }
    
    if (className === 'ai-message' && !text.includes('⚠️ Request cancelled')) {
        const downloadBtn = document.createElement('button');
        downloadBtn.innerHTML = '⬇️ Download Response PDF';
        downloadBtn.style.cssText = 'margin-top: 5px; background: transparent; border: 1px solid #cbd5e1; color: #475569; padding: 6px 12px; border-radius: 4px; cursor: pointer; font-size: 0.8em; align-self: flex-start; transition: background 0.2s;';
        downloadBtn.onmouseover = () => downloadBtn.style.backgroundColor = '#f1f5f9';
        downloadBtn.onmouseout = () => downloadBtn.style.backgroundColor = 'transparent';
        downloadBtn.onclick = () => exportSinglePDF(uniqueId, downloadBtn);
        wrapperDiv.appendChild(downloadBtn);
    }

    messagesDiv.scrollTop = messagesDiv.scrollHeight;
}

function constructSimpleGraph(canvasId, configStr) {
    try {
        const cfg = JSON.parse(configStr);
        const ctx = document.getElementById(canvasId).getContext('2d');
        const colors = ['#0284c7', '#f59e0b', '#10b981', '#ef4444', '#8b5cf6'];
        new Chart(ctx, { type: cfg.type || 'bar', data: { labels: cfg.labels, datasets: cfg.datasets.map((d, i) => ({ ...d, backgroundColor: colors[i % colors.length] })) }, options: { responsive: true, maintainAspectRatio: false, animation: false } });
    } catch (err) {}
}

// --- 5. PDF Export Logic ---
function exportSinglePDF(wrapperId, btnElement) {
    const originalText = btnElement.innerHTML;
    btnElement.innerHTML = "⏳ Generating...";
    btnElement.disabled = true;

    const aiWrapper = document.getElementById(wrapperId);
    const userWrapper = aiWrapper.previousElementSibling;
    const queryText = (userWrapper && userWrapper.classList.contains('wrapper-user')) ? userWrapper.querySelector('.message').innerText : "Automated Data Query";

    const aiNode = aiWrapper.querySelector('.message').cloneNode(true);
    
    aiNode.querySelectorAll('.copy-btn').forEach(btn => btn.remove());
    
    const originalCharts = aiWrapper.querySelectorAll('canvas');
    const clonedCharts = aiNode.querySelectorAll('canvas');
    originalCharts.forEach((c, index) => {
        const img = document.createElement('img');
        img.src = c.toDataURL('image/png', 1.0); 
        img.style.maxWidth = '100%'; img.style.border = '1px solid #cbd5e1'; img.style.borderRadius = '6px'; img.style.margin = '15px 0';
        clonedCharts[index].parentNode.replaceChild(img, clonedCharts[index]);
    });

    const htmlContent = `
        <div style="font-family: 'Helvetica Neue', Helvetica, Arial, sans-serif; color: #1e293b; padding: 20px;">
            <style>
                table { width: 100%; border-collapse: collapse; margin-bottom: 20px; box-shadow: 0 1px 3px rgba(0,0,0,0.1); }
                th, td { border: 1px solid #e2e8f0; padding: 10px; text-align: left; font-size: 10pt; }
                th { background-color: #f1f5f9; color: #334155; text-transform: uppercase; font-size: 9pt; }
                pre { background-color: #1e293b; color: #f8fafc; padding: 15px; border-radius: 6px; overflow-x: auto; }
                code { font-family: monospace; }
                h3 { color: #0f172a; border-bottom: 1px solid #e2e8f0; padding-bottom: 5px; }
            </style>
            <h1 style="border-bottom: 3px solid #0ea5e9; color: #0f172a; padding-bottom: 10px; margin-top:0;">Greenplum Query Response</h1>
            <h2 style="color: #0369a1; font-size: 14pt; border-left: 4px solid #0ea5e9; padding-left: 10px; margin-top: 25px;">Query: ${queryText}</h2>
            <div style="margin-top: 20px; font-size: 11pt; line-height: 1.6;">${aiNode.innerHTML}</div>
        </div>`;

    html2pdf().set({ margin: 0.5, filename: `Query_Response_${Date.now()}.pdf`, image: { type: 'jpeg', quality: 1.0 }, html2canvas: { scale: 2, useCORS: true }, jsPDF: { unit: 'in', format: 'letter', orientation: 'portrait' } }).from(htmlContent).save().then(() => {
        btnElement.innerHTML = originalText; btnElement.disabled = false;
    }).catch(err => {
        btnElement.innerHTML = "❌ Error"; setTimeout(() => { btnElement.innerHTML = originalText; btnElement.disabled = false; }, 2000);
    });
}