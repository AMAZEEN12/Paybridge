/* PayBridge web page. Plain JavaScript, no libraries, no build step.
   Rules: the token lives in memory only; text from the server is only ever put in with textContent
   (never innerHTML); every transfer form gets ONE Idempotency-Key that is reused on retries. */
(function () {
  'use strict';

  var $ = function (id) { return document.getElementById(id); };
  var token = null;
  var me = null;
  var accounts = [];
  var banks = [];
  var sendKey = null;
  var bellTimer = null;
  var pollTimer = null;
  var quoteTimer = null;
  var resolveTicket = 0;
  var formatter = new Intl.NumberFormat('en-NG', { style: 'currency', currency: 'NGN' });

  var MESSAGES = {
    INVALID_CREDENTIALS: 'Email or password is incorrect.',
    ACCOUNT_LOCKED: 'Too many failed attempts. Try again in a few minutes.',
    EMAIL_TAKEN: 'An account with this email already exists.',
    VALIDATION_FAILED: 'Please check the highlighted details and try again.',
    INSUFFICIENT_FUNDS: 'You do not have enough money, including charges.',
    PIN_INVALID: 'That PIN is not correct.',
    PIN_LOCKED: 'Too many wrong PIN attempts. Try again later.',
    ACCOUNT_FROZEN: 'This account is frozen. Unfreeze it to send money.',
    ACCOUNT_NOT_FOUND: 'We could not find that account.',
    ACCOUNT_NOT_RESOLVED: 'We could not find that bank account. Check the bank and number.',
    UNKNOWN_BANK: 'That bank is not supported.',
    SAME_ACCOUNT: 'You cannot send money to the same account.',
    TRANSFER_NOT_APPROVED: 'This transfer was not approved. Please contact support.',
    IDEMPOTENCY_KEY_REUSED: 'This form was already used for a different transfer. Start again.',
    TOO_MANY_LOOKUPS: 'Too many lookups. Please wait a minute.',
    PROVIDER_UNAVAILABLE: 'We could not reach the bank network. Nothing was debited. Try again.',
    ACCOUNT_LIMIT_REACHED: 'You can open at most 5 accounts.'
  };

  function friendly(err) {
    if (err && err.network) { return 'We could not reach the server. Check your connection.'; }
    if (err && err.code && MESSAGES[err.code]) { return MESSAGES[err.code]; }
    return 'Something went wrong. Please try again.';
  }

  function money(value) { return formatter.format(Number(value)); }

  function el(tag, className, text) {
    var node = document.createElement(tag);
    if (className) { node.className = className; }
    if (text !== undefined && text !== null) { node.textContent = text; }
    return node;
  }

  function clear(node) { while (node.firstChild) { node.removeChild(node.firstChild); } }

  function toast(message, bad) {
    var t = $('toast');
    t.textContent = message;
    t.className = 'toast' + (bad ? ' bad' : '');
    t.hidden = false;
    clearTimeout(toast.timer);
    toast.timer = setTimeout(function () { t.hidden = true; }, 5000);
  }

  function newKey() {
    if (window.crypto && window.crypto.randomUUID) { return window.crypto.randomUUID(); }
    return 'k-' + Date.now() + '-' + Math.random().toString(16).slice(2);
  }

  /* ---------- the one place that talks to the server ---------- */
  function api(path, options) {
    options = options || {};
    var headers = { 'Accept': 'application/json' };
    if (options.body !== undefined) { headers['Content-Type'] = 'application/json'; }
    if (token) { headers['Authorization'] = 'Bearer ' + token; }
    if (options.key) { headers['Idempotency-Key'] = options.key; }
    return fetch('/api/v1' + path, {
      method: options.method || 'GET',
      headers: headers,
      body: options.body !== undefined ? JSON.stringify(options.body) : undefined
    }).then(function (res) {
      return res.text().then(function (text) {
        var data = null;
        try { data = text ? JSON.parse(text) : null; } catch (e) { data = null; }
        if (res.status === 401 && token) { signOut('Your session ended. Please sign in again.'); }
        if (!res.ok) {
          var err = new Error((data && data.message) || 'Request failed');
          err.status = res.status;
          err.code = data && data.code;
          throw err;
        }
        return { status: res.status, data: data, replayed: res.headers.get('Idempotent-Replay') === 'true' };
      });
    }, function () {
      var e = new Error('network');
      e.network = true;      // we do not know whether the server got it
      throw e;
    });
  }

  /* ---------- sign in / out ---------- */
  function setAuthTab(login) {
    $('loginForm').hidden = !login;
    $('registerForm').hidden = login;
    $('tabLogin').classList.toggle('active', login);
    $('tabRegister').classList.toggle('active', !login);
  }

  function enterApp() {
    $('authView').hidden = true;
    $('appView').hidden = false;
    $('who').hidden = false;
    $('whoName').textContent = me.fullName;
    $('pinCard').hidden = me.pinSet;
    showView('home');
    refreshBell();
    clearInterval(bellTimer);
    bellTimer = setInterval(refreshBell, 15000);
  }

  function signOut(message) {
    token = null; me = null; accounts = [];
    clearInterval(bellTimer); clearInterval(pollTimer);
    $('appView').hidden = true;
    $('who').hidden = true;
    $('authView').hidden = false;
    $('loginPassword').value = '';
    if (message) { toast(message, false); }
  }

  function loadMeAndEnter() {
    return api('/customers/me').then(function (r) { me = r.data; enterApp(); });
  }

  /* ---------- views ---------- */
  var VIEWS = ['home', 'send', 'history', 'alerts'];

  function showView(name) {
    VIEWS.forEach(function (v) { $(v + 'View').hidden = v !== name; });
    document.querySelectorAll('[data-view]').forEach(function (b) {
      b.classList.toggle('active', b.getAttribute('data-view') === name);
    });
    if (name === 'home') { loadAccounts(); }
    if (name === 'send') { prepareSend(); }
    if (name === 'history') { prepareHistory(); }
    if (name === 'alerts') { loadAlerts(); }
  }

  /* ---------- home ---------- */
  function loadAccounts() {
    return api('/accounts').then(function (r) { accounts = r.data || []; renderAccounts(); }, function (e) { toast(friendly(e), true); });
  }

  function renderAccounts() {
    var list = $('accountList');
    clear(list);
    if (!accounts.length) {
      list.appendChild(el('p', 'hint', 'You have no accounts yet. Open one to get started.'));
      return;
    }
    accounts.forEach(function (a) {
      var card = el('div', 'card');
      var top = el('div', 'account');
      var left = el('div');
      left.appendChild(el('div', 'acct-number', 'Account ' + a.accountNumber));
      left.appendChild(el('div', 'balance', money(a.balance)));
      top.appendChild(left);
      if (a.status === 'FROZEN') { top.appendChild(el('span', 'chip bad', 'Frozen')); }
      card.appendChild(top);

      var actions = el('div', 'row');
      var fund = el('button', 'secondary', 'Add test money');
      fund.type = 'button';
      fund.addEventListener('click', function () { fundAccount(a.accountNumber); });
      actions.appendChild(fund);
      var freeze = el('button', null, a.status === 'FROZEN' ? 'Unfreeze' : 'Freeze account');
      freeze.type = 'button';
      freeze.addEventListener('click', function () { toggleFreeze(a); });
      actions.appendChild(freeze);
      card.appendChild(actions);
      list.appendChild(card);
    });
  }

  function fundAccount(number) {
    api('/dev/accounts/' + number + '/fund', { method: 'POST', body: { amount: 50000 } }).then(function () {
      toast('Added ₦50,000.00 of test money.', false);
      loadAccounts();
    }, function (e) {
      toast(e.status === 404 ? 'Test money is switched off on this server.' : friendly(e), true);
    });
  }

  function toggleFreeze(a) {
    if (a.status === 'FROZEN') {
      var pin = window.prompt('Enter your 4-digit PIN to unfreeze this account');
      if (!pin) { return; }
      api('/accounts/' + a.accountNumber + '/unfreeze', { method: 'POST', body: { pin: pin } }).then(function () {
        toast('Account unfrozen.', false); loadAccounts();
      }, function (e) { toast(friendly(e), true); });
    } else if (window.confirm('Freeze this account? It will not be able to send money until you unfreeze it.')) {
      api('/accounts/' + a.accountNumber + '/freeze', { method: 'POST' }).then(function () {
        toast('Account frozen.', false); loadAccounts();
      }, function (e) { toast(friendly(e), true); });
    }
  }

  /* ---------- send money ---------- */
  function isExternal() {
    return document.querySelector('input[name="kind"]:checked').value === 'EXTERNAL';
  }

  function prepareSend() {
    sendKey = newKey();
    $('sendResult').hidden = true;
    $('resolved').hidden = true;
    $('quote').hidden = true;
    loadAccounts().then(function () {
      var sel = $('sendFrom');
      clear(sel);
      accounts.forEach(function (a) {
        var o = document.createElement('option');
        o.value = a.accountNumber;
        o.textContent = a.accountNumber + ' (' + money(a.balance) + ')';
        sel.appendChild(o);
      });
    });
    if (!banks.length) {
      api('/banks').then(function (r) {
        banks = r.data || [];
        var sel = $('sendBank');
        banks.forEach(function (b) {
          var o = document.createElement('option');
          o.value = b.code; o.textContent = b.name;
          sel.appendChild(o);
        });
      }, function () { /* the bank list is only needed for payouts */ });
    }
  }

  function onKindChange() {
    $('bankRow').hidden = !isExternal();
    $('resolved').hidden = true;
    scheduleQuote();
  }

  function tryResolve() {
    var box = $('resolved');
    box.hidden = true;
    if (!isExternal()) { return; }
    var bank = $('sendBank').value;
    var number = $('sendTo').value.trim();
    if (!bank || !/^\d{10}$/.test(number)) { return; }
    var ticket = ++resolveTicket;
    api('/banks/resolve', { method: 'POST', body: { bankCode: bank, accountNumber: number } }).then(function (r) {
      if (ticket !== resolveTicket) { return; }
      box.textContent = r.data.accountName;
      box.className = 'resolved';
      box.hidden = false;
    }, function (e) {
      if (ticket !== resolveTicket) { return; }
      box.textContent = friendly(e);
      box.className = 'resolved bad';
      box.hidden = false;
    });
  }

  function scheduleQuote() {
    clearTimeout(quoteTimer);
    quoteTimer = setTimeout(showQuote, 300);
  }

  function showQuote() {
    var amount = parseFloat($('sendAmount').value);
    var box = $('quote');
    if (!(amount > 0)) { box.hidden = true; return; }
    api('/transfers/quote', { method: 'POST', body: { type: isExternal() ? 'EXTERNAL' : 'INTERNAL', amount: amount } })
      .then(function (r) {
        var q = r.data;
        clear(box);
        [['Amount', q.amount], ['Fee', q.fee], ['VAT on fee', q.vat], ['Stamp duty', q.stampDuty]].forEach(function (row) {
          var line = el('div');
          line.appendChild(el('span', null, row[0]));
          line.appendChild(el('span', null, money(row[1])));
          box.appendChild(line);
        });
        var total = el('div', 'total');
        total.appendChild(el('span', null, 'Total to be debited'));
        total.appendChild(el('span', null, money(q.totalDebit)));
        box.appendChild(total);
        box.hidden = false;
      }, function () { box.hidden = true; });
  }

  function showResult(kind, text) {
    var box = $('sendResult');
    box.className = 'result' + (kind ? ' ' + kind : '');
    box.textContent = text;
    box.hidden = false;
  }

  function describe(t) {
    var where = t.destinationBankName ? t.destinationBankName + ' ' + t.destinationAccountNumber : t.destinationAccountNumber;
    if (t.status === 'SUCCESSFUL') { return 'Sent ' + money(t.amount) + ' to ' + where + '. Charges ' + money(Number(t.fee) + Number(t.vat) + Number(t.stampDuty)) + '. Reference ' + t.reference; }
    if (t.status === 'PENDING') { return 'Sent ' + money(t.amount) + ' to ' + where + '. Waiting for the bank to confirm. Reference ' + t.reference; }
    return 'The transfer of ' + money(t.amount) + ' failed and your money was returned. Reference ' + t.reference;
  }

  function resultKind(t) { return t.status === 'SUCCESSFUL' ? '' : (t.status === 'PENDING' ? 'wait' : 'bad'); }

  function pollPending(reference) {
    clearInterval(pollTimer);
    var tries = 0;
    pollTimer = setInterval(function () {
      tries += 1;
      api('/transfers/' + reference).then(function (r) {
        if (r.data.status !== 'PENDING') {
          clearInterval(pollTimer);
          showResult(resultKind(r.data), describe(r.data));
          loadAccounts().then(prepareFromSelect);
        } else if (tries >= 20) {
          clearInterval(pollTimer);
          showResult('wait', 'Still processing. We will notify you when the bank confirms. Reference ' + reference);
        }
      }, function () { /* try again on the next tick */ });
    }, 3000);
  }

  function prepareFromSelect() {
    var sel = $('sendFrom');
    var current = sel.value;
    clear(sel);
    accounts.forEach(function (a) {
      var o = document.createElement('option');
      o.value = a.accountNumber;
      o.textContent = a.accountNumber + ' (' + money(a.balance) + ')';
      sel.appendChild(o);
    });
    if (current) { sel.value = current; }
  }

  function onSend(event) {
    event.preventDefault();
    var button = $('sendButton');
    var amount = parseFloat($('sendAmount').value);
    var external = isExternal();
    var body = {
      sourceAccountNumber: $('sendFrom').value,
      destinationAccountNumber: $('sendTo').value.trim(),
      amount: amount,
      narration: $('sendNarration').value.trim() || null,
      pin: $('sendPin').value
    };
    if (external) {
      if (!$('sendBank').value) { toast('Choose a bank first.', true); return; }
      body.bankCode = $('sendBank').value;
    }
    button.disabled = true;
    // The SAME key is sent on every retry of this form. A new key is made only for a brand new transfer.
    api(external ? '/transfers/external' : '/transfers/internal', { method: 'POST', body: body, key: sendKey })
      .then(function (r) {
        showResult(resultKind(r.data), describe(r.data) + (r.replayed ? ' (this was already sent; nothing was charged twice)' : ''));
        $('sendPin').value = '';
        sendKey = newKey();                 // done: the next transfer is a new one
        if (r.data.status === 'PENDING') { pollPending(r.data.reference); }
        loadAccounts().then(prepareFromSelect);
        refreshBell();
      }, function (e) {
        $('sendPin').value = '';
        if (e.network || (e.status && e.status >= 500)) {
          // We do not know if it went through. Keep the key so pressing Send again is safe.
          showResult('wait', 'We are not sure whether this went through. Press Send again to retry safely: the same transfer will never be paid twice.');
        } else {
          showResult('bad', friendly(e));
          if (e.code !== 'PIN_INVALID' && e.code !== 'PIN_LOCKED') { sendKey = newKey(); }
        }
      })
      .then(function () { button.disabled = false; });
  }

  /* ---------- history and receipt ---------- */
  function prepareHistory() {
    $('receipt').hidden = true;
    loadAccounts().then(function () {
      var sel = $('historyAccount');
      var current = sel.value;
      clear(sel);
      accounts.forEach(function (a) {
        var o = document.createElement('option');
        o.value = a.accountNumber; o.textContent = a.accountNumber;
        sel.appendChild(o);
      });
      if (current) { sel.value = current; }
      loadHistory();
    });
  }

  function chip(status) {
    var cls = status === 'SUCCESSFUL' ? 'ok' : (status === 'PENDING' ? 'wait' : 'bad');
    return el('span', 'chip ' + cls, status);
  }

  function loadHistory() {
    var number = $('historyAccount').value;
    var list = $('historyList');
    clear(list);
    if (!number) { return; }
    api('/accounts/' + number + '/transfers').then(function (r) {
      var rows = r.data || [];
      if (!rows.length) { list.appendChild(el('li', 'hint', 'No transfers yet.')); return; }
      rows.forEach(function (t) {
        var outgoing = t.sourceAccountNumber === number;
        var li = el('li');
        var btn = el('button', 'item');
        btn.type = 'button';
        var main = el('div', 'main');
        main.appendChild(el('div', 'title', outgoing
          ? 'To ' + (t.destinationBankName ? t.destinationBankName + ' ' : '') + t.destinationAccountNumber
          : 'From ' + t.sourceAccountNumber));
        var sub = el('div', 'sub', new Date(t.createdAt).toLocaleString());
        main.appendChild(sub);
        main.appendChild(chip(t.status));
        var amt = el('div', 'amount', (outgoing ? '-' : '+') + money(outgoing ? t.totalDebit : t.amount));
        btn.appendChild(main);
        btn.appendChild(amt);
        btn.addEventListener('click', function () { showReceipt(t); });
        li.appendChild(btn);
        list.appendChild(li);
      });
    }, function (e) { toast(friendly(e), true); });
  }

  function showReceipt(t) {
    var box = $('receipt');
    clear(box);
    box.appendChild(el('h2', null, 'Receipt'));
    var dl = el('dl');
    var rows = [
      ['Reference', t.reference], ['Date', new Date(t.createdAt).toLocaleString()], ['Status', t.status],
      ['From', t.sourceAccountNumber],
      ['To', (t.destinationBankName ? t.destinationBankName + ' ' : '') + t.destinationAccountNumber],
      ['Recipient', t.destinationAccountName || '-'],
      ['Amount', money(t.amount)], ['Fee', money(t.fee)], ['VAT on fee', money(t.vat)], ['Stamp duty', money(t.stampDuty)],
      ['Total debited', money(t.totalDebit)], ['Narration', t.narration || '-']
    ];
    rows.forEach(function (r) {
      dl.appendChild(el('dt', null, r[0]));
      dl.appendChild(el('dd', null, r[1]));
    });
    box.appendChild(dl);
    var actions = el('div', 'row noprint');
    var print = el('button', 'secondary', 'Print');
    print.type = 'button';
    print.addEventListener('click', function () { window.print(); });
    var close = el('button', null, 'Close');
    close.type = 'button';
    close.addEventListener('click', function () { box.hidden = true; });
    actions.appendChild(print);
    actions.appendChild(close);
    box.appendChild(actions);
    box.hidden = false;
  }

  /* ---------- alerts ---------- */
  function refreshBell() {
    if (!token) { return; }
    api('/notifications/unread-count').then(function (r) {
      var n = r.data.unread;
      $('badge').textContent = n > 99 ? '99+' : String(n);
      $('badge').hidden = !(n > 0);
    }, function () { /* ignore */ });
  }

  function loadAlerts() {
    var list = $('alertList');
    clear(list);
    api('/notifications').then(function (r) {
      var rows = r.data || [];
      if (!rows.length) { list.appendChild(el('li', 'hint', 'Nothing here yet.')); return; }
      rows.forEach(function (n) {
        var li = el('li', n.read ? '' : 'unread');
        var main = el('div', 'main');
        main.appendChild(el('div', 'title', n.title));
        main.appendChild(el('div', 'sub', n.message));
        main.appendChild(el('div', 'sub', new Date(n.createdAt).toLocaleString()));
        li.appendChild(main);
        if (!n.read) {
          var mark = el('button', 'link', 'Mark read');
          mark.type = 'button';
          mark.addEventListener('click', function () {
            api('/notifications/' + n.id + '/read', { method: 'POST' }).then(function () { loadAlerts(); refreshBell(); });
          });
          li.appendChild(mark);
        }
        list.appendChild(li);
      });
      refreshBell();
    }, function (e) { toast(friendly(e), true); });
  }

  /* ---------- wiring ---------- */
  function wire() {
    $('tabLogin').addEventListener('click', function () { setAuthTab(true); });
    $('tabRegister').addEventListener('click', function () { setAuthTab(false); });

    $('loginForm').addEventListener('submit', function (e) {
      e.preventDefault();
      api('/auth/login', { method: 'POST', body: { email: $('loginEmail').value, password: $('loginPassword').value } })
        .then(function (r) { token = r.data.token; return loadMeAndEnter(); })
        .catch(function (err) { toast(friendly(err), true); });
    });

    $('registerForm').addEventListener('submit', function (e) {
      e.preventDefault();
      api('/auth/register', { method: 'POST', body: { fullName: $('regName').value, email: $('regEmail').value, password: $('regPassword').value } })
        .then(function () {
          return api('/auth/login', { method: 'POST', body: { email: $('regEmail').value, password: $('regPassword').value } });
        })
        .then(function (r) { token = r.data.token; return loadMeAndEnter(); })
        .catch(function (err) { toast(friendly(err), true); });
    });

    $('logout').addEventListener('click', function () { signOut('Signed out.'); });
    $('bell').addEventListener('click', function () { showView('alerts'); });
    document.querySelectorAll('[data-view]').forEach(function (b) {
      b.addEventListener('click', function () { showView(b.getAttribute('data-view')); });
    });

    $('pinForm').addEventListener('submit', function (e) {
      e.preventDefault();
      api('/customers/me/pin', { method: 'POST', body: { pin: $('pinNew').value } }).then(function () {
        me.pinSet = true; $('pinCard').hidden = true; $('pinNew').value = '';
        toast('PIN saved.', false);
      }, function (err) { toast(friendly(err), true); });
    });

    $('openAccount').addEventListener('click', function () {
      api('/accounts', { method: 'POST' }).then(function () { toast('Account opened.', false); loadAccounts(); },
        function (err) { toast(friendly(err), true); });
    });

    document.querySelectorAll('input[name="kind"]').forEach(function (r) { r.addEventListener('change', onKindChange); });
    $('sendBank').addEventListener('change', tryResolve);
    $('sendTo').addEventListener('input', tryResolve);
    $('sendAmount').addEventListener('input', scheduleQuote);
    $('sendForm').addEventListener('submit', onSend);
    $('historyAccount').addEventListener('change', loadHistory);
    $('readAll').addEventListener('click', function () {
      api('/notifications/read-all', { method: 'POST' }).then(function () { loadAlerts(); refreshBell(); });
    });
  }

  wire();
})();
