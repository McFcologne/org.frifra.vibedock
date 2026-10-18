// Refresh authentication and CSRF before writes without navigating away from the user's current draft.
(() => {
  const nativeFetch = window.fetch.bind(window);
  let refreshing;
  const admin = url => url.origin === location.origin && (url.pathname === '/admin' || url.pathname.startsWith('/admin/'));
  const unsafe = method => !['GET', 'HEAD', 'OPTIONS', 'TRACE'].includes(method.toUpperCase());
  function csrf() {
    if (!refreshing) refreshing = (async () => {
      const response = await nativeFetch('/admin/csrf', {credentials: 'same-origin', cache: 'no-store', headers: {Accept: 'application/json'}});
      if (!response.ok || !response.headers.get('Content-Type')?.includes('application/json'))
        throw new Error('Your session has expired. Sign in in another tab, then try again.');
      const current = await response.json();
      if (!current.token || !current.headerName || !current.parameterName) throw new Error('Could not refresh the session. Please try again.');
      document.querySelector('meta[name="csrf-token"]').content = current.token;
      document.querySelector('meta[name="csrf-header"]').content = current.headerName;
      document.querySelectorAll('input[type="hidden"]').forEach(input => {
        if (input.name === current.parameterName) input.value = current.token;
      });
      return current;
    })().finally(() => { refreshing = null; });
    return refreshing;
  }
  window.fetch = async (input, options = {}) => {
    const request = input instanceof Request ? input : null;
    const url = new URL(request ? request.url : input, location.href);
    if (!admin(url) || !unsafe(options.method || request?.method || 'GET') || (options.credentials || request?.credentials) === 'omit')
      return nativeFetch(input, options);
    // Unload saves must enter the browser's keepalive queue synchronously; an awaited bootstrap could be abandoned.
    if (options.keepalive || request?.keepalive) {
      const headers = new Headers(options.headers || request?.headers);
      headers.set(document.querySelector('meta[name="csrf-header"]').content, document.querySelector('meta[name="csrf-token"]').content);
      return nativeFetch(input, {...options, headers});
    }
    const current = await csrf();
    const headers = new Headers(options.headers || request?.headers);
    headers.set(current.headerName, current.token);
    // Current callers send replayable strings, FormData or Blobs. A caller-supplied stream must never be replayed.
    const replayable = !(options.body instanceof ReadableStream);
    const retryInput = request && replayable ? request.clone() : input;
    const response = await nativeFetch(input, {...options, headers});
    if (response.status !== 403 || response.headers.get('X-VibeDock-CSRF') !== 'invalid' || !replayable) return response;
    // Another tab can establish a different session between bootstrap and POST. Retry only a CSRF rejection, once.
    const fresh = await csrf();headers.set(fresh.headerName, fresh.token);
    return nativeFetch(retryInput, {...options, headers});
  };

  const ready = new WeakSet(), pending = new WeakSet();
  document.addEventListener('submit', async event => {
    const form = event.target, submitter = event.submitter;
    if (!(form instanceof HTMLFormElement) || ready.delete(form) || event.defaultPrevented) return;
    const method = submitter?.getAttribute('formmethod') || form.method;
    const action = new URL(submitter?.getAttribute('formaction') || form.action, location.href);
    if (!unsafe(method) || !admin(action)) return;
    event.preventDefault();
    if (pending.has(form)) return;
    pending.add(form);
    try {
      const current = await csrf();
      let field = form.querySelector(`input[name="${CSS.escape(current.parameterName)}"]`);
      if (!field) { field = document.createElement('input'); field.type = 'hidden'; field.name = current.parameterName; form.append(field); }
      field.value = current.token;
      ready.add(form);
      form.requestSubmit(submitter);
    } catch (error) {
      let notice = document.getElementById('session-recovery-error');
      if (!notice) {
        notice = document.createElement('p'); notice.id = 'session-recovery-error'; notice.className = 'notice error'; notice.setAttribute('role', 'alert');
        (document.querySelector('main') || form).prepend(notice);
      }
      notice.textContent = error.message;
      const signIn = document.createElement('a'); signIn.href = '/login'; signIn.target = '_blank'; signIn.rel = 'noopener'; signIn.textContent = ' Sign in';
      notice.append(signIn);
    } finally { ready.delete(form); pending.delete(form); }
  });
})();
