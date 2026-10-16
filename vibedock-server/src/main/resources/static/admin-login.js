(() => {
  if(location.protocol !== 'file:') setInterval(() => {
    for(const image of document.querySelectorAll('[data-client-preview]'))
      image.src = image.src.split('?')[0] + '?t=' + Date.now();
  },30000);
  for (const panel of document.querySelectorAll('[data-provider]')) {
    const provider = panel.dataset.provider;
    const message = panel.querySelector('[data-login-message]');
    const start = panel.querySelector('[data-action="start"]');
    const cancel = panel.querySelector('[data-action="cancel"]');
    const link = panel.querySelector('[data-login-link]');
    const deviceCode = panel.querySelector('[data-device-code]');
    const codeBox = panel.querySelector('[data-code-box]');
    const input = panel.querySelector('[data-login-code]');
    let current, timer, popup, opening = false;
    const active = view => ['starting', 'waiting', 'completing'].includes(view.status);
    async function request(action, body) {
      if (location.protocol === 'file:') throw new Error('Please open the administration of the running server at http://127.0.0.1:10001/admin.');
      const headers = { Accept: 'application/json' };
      if (action) {
        const token = document.querySelector('meta[name="csrf-token"]').content;
        const header = document.querySelector('meta[name="csrf-header"]').content;
        if (!token || !header) throw new Error('Please reload the administration page and sign in.');
        headers[header] = token;
        headers['Content-Type'] = 'application/json';
      }
      const response = await fetch(`/admin/login/${provider}${action ? '/' + action : ''}`, {
        method: action ? 'POST' : 'GET', headers, credentials: 'same-origin',
        body: action ? JSON.stringify(body || {}) : undefined
      });
      if (response.status === 401 || response.status === 403 || response.redirected)
        throw new Error('Administration session expired. Please reload the page.');
      const view = await response.json();
      if (!response.ok) throw new Error(view.message || 'Sign-in is currently not possible.');
      return view;
    }
    function render(view) {
      clearTimeout(timer); current = view;
      start.disabled = active(view);
      cancel.hidden = !active(view);
      message.textContent = view.message;
      link.hidden = !view.authUrl;
      if (view.authUrl) link.href = view.authUrl; else link.removeAttribute('href');
      deviceCode.hidden = !view.userCode;
      deviceCode.querySelector('code').textContent = view.userCode || '';
      codeBox.hidden = !view.needsCode;
      if (opening && view.authUrl) {
        opening = false;
        if (popup && !popup.closed) popup.location.href = view.authUrl;
        popup = null; // provider browser tab belongs to the user from this point on
      }
      if (!active(view)) {
        opening = false;
        if (popup && !popup.closed) popup.close();
        popup = null; input.value = '';
      }
      if (active(view)) timer = setTimeout(async () => {
        try { render(await request()); } catch (error) { fail(error); }
      }, 1500);
    }
    function fail(error) {
      clearTimeout(timer); message.textContent = error.message;
      start.disabled = false; opening = false;
      if (popup && !popup.closed) popup.close();
      popup = null;
    }
    start.addEventListener('click', async () => {
      start.disabled = true;
      if (location.protocol !== 'file:') {
        // Open synchronously with the click so browser popup blocking does not hide the login.
        popup = window.open('about:blank', '_blank');
        if (popup) { popup.opener = null; popup.document.title = 'Sign-in'; popup.document.body.textContent = 'Preparing sign-in. If a one-time code is needed, it is shown in the VibeDock administration.'; }
        opening = true;
      }
      try { render(await request('start')); } catch (error) { fail(error); }
    });
    cancel.addEventListener('click', async () => {
      try { render(await request('cancel', { id: current.id })); } catch (error) { fail(error); }
    });
    const copy = panel.querySelector('[data-action="copy-code"]');
    if (copy) copy.addEventListener('click', async () => {
      const code = deviceCode.querySelector('code').textContent;
      if (!code) return;
      try {
        await navigator.clipboard.writeText(code);
        copy.textContent = 'Copied';
      } catch (error) {
        // Fallback: select the code so it can be copied with Cmd+C.
        const range = document.createRange(); range.selectNodeContents(deviceCode.querySelector('code'));
        const selection = getSelection(); selection.removeAllRanges(); selection.addRange(range);
        copy.textContent = 'Press Cmd+C to copy';
      }
      setTimeout(() => { copy.textContent = 'Copy code'; }, 2000);
    });
    panel.querySelector('[data-action="code"]').addEventListener('click', async () => {
      const code = input.value.trim(); input.value = '';
      try { render(await request('code', { id: current.id, code })); } catch (error) { fail(error); }
    });
    if (location.protocol !== 'file:') request().then(render).catch(fail);
  }
})();
