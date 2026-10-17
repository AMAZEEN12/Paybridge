/* PayBridge "Send abroad" screen: cross-currency transfers (A5) and bank payouts abroad (B3).
   Plain JavaScript, no libraries. Server text only goes in with textContent (never innerHTML).
   Each quote gets ONE Idempotency-Key, reused if the customer presses Confirm again. */
(function () {
    'use strict';

    var bridge = window.PayBridge;

    if (!bridge) {
        return;
    }

    var $ = function (id) { return document.getElementById(id); };
    var api = bridge.api;
    var el = bridge.el;
    var clear = bridge.clear;
    var toast = bridge.toast;

    var wallets = [];
    var dest = { countries: [], purposes: [] };
    var xc = { quote: null, key: null, deadline: 0, timer: null, busy: false };
    var po = { quote: null, key: null, deadline: 0, timer: null, busy: false, poll: null };

    var MESSAGES = {
        INSUFFICIENT_FUNDS: 'You do not have enough money in that wallet, including the fee.',
        PIN_INVALID: 'That PIN is not correct.',
        PIN_LOCKED: 'Too many wrong PIN attempts. Try again later.',
        ACCOUNT_FROZEN: 'This account is frozen. Unfreeze it first.',
        WALLET_PARKED: 'That wallet is parked. Activate it on the Home page first.',
        RECIPIENT_CANNOT_RECEIVE: 'That account cannot receive this currency. Choose one it accepts.',
        QUOTE_EXPIRED: 'That quote has expired. Get a new quote.',
        QUOTE_ALREADY_USED: 'That quote was already used. Check your history.',
        QUOTE_NOT_FOUND: 'We could not find that quote. Get a new quote.',
        NO_RATE: 'No exchange rate is available right now. Try again in a few minutes.',
        TOO_MANY_LOOKUPS: 'Too many lookups. Please wait a minute.'
    };

    function message(err) {
        if (err && err.network) {
            return bridge.friendly(err);
        }

        if (err && err.code && MESSAGES[err.code]) {
            return MESSAGES[err.code];
        }

        if (err && err.status === 404 && !err.code) {
            return 'This feature is not switched on yet.';
        }

        if (err && err.status === 503) {
            return 'Rates are not available right now. Try again in a few minutes.';
        }

        return (err && err.message && err.message !== 'Request failed') ? err.message : bridge.friendly(err);
    }

    function newKey() {
        if (window.crypto && window.crypto.randomUUID) {
            return window.crypto.randomUUID();
        }

        return 'k-' + Date.now() + '-' + Math.random().toString(16).slice(2);
    }

    /* The server sends decimal amounts, so format by the currency's own decimals. */
    function money(value, code) {
        try {
            return new Intl.NumberFormat('en-US', { style: 'currency', currency: code }).format(Number(value));
        } catch (e) {
            return code + ' ' + value;
        }
    }

    function activeWallets() {
        return wallets.filter(function (w) { return w.status === 'ACTIVE'; });
    }

    function fillWallets(select) {
        var before = select.value;

        clear(select);

        activeWallets().forEach(function (w) {
            var o = el('option', null, w.currency + ' (' + w.display + ')');

            o.value = w.currency;
            select.appendChild(o);
        });

        if (before) {
            select.value = before;
        }
    }

    function show(box, kind, text) {
        box.className = 'result' + (kind ? ' ' + kind : '');
        box.textContent = text;
        box.hidden = false;
    }

    /* rows: [label, value]; the last row is drawn as the total. */
    function drawLines(box, rows) {
        clear(box);

        rows.forEach(function (r, i) {
            var line = el('div', i === rows.length - 1 ? 'total' : null);

            line.appendChild(el('span', null, r[0]));
            line.appendChild(el('span', null, r[1]));
            box.appendChild(line);
        });

        box.hidden = false;
    }

    function startCountdown(state, label, button) {
        clearInterval(state.timer);

        function tick() {
            var left = Math.max(0, Math.round((state.deadline - Date.now()) / 1000));

            if (left > 0) {
                label.textContent = 'This quote is locked for '
                    + Math.floor(left / 60) + ':' + ('0' + (left % 60)).slice(-2) + '.';
            } else {
                label.textContent = 'This quote has expired. Get a new quote.';
            }

            button.disabled = left === 0 || state.busy;
        }

        label.hidden = false;
        tick();
        state.timer = setInterval(tick, 500);
    }

    function resetFlow(state, ids) {
        clearInterval(state.timer);
        state.quote = null;
        state.key = null;
        state.busy = false;
        ids.forEach(function (id) { $(id).hidden = true; });
    }

    /* ---------- cross-currency transfer ---------- */

    var XC_IDS = ['xcQuote', 'xcCountdown', 'xcActions', 'xcResult'];
    var xcTicket = 0;

    function xcReset() {
        resetFlow(xc, XC_IDS);
        $('xcPin').value = '';
    }

    function xcLookup() {
        var number = $('xcTo').value.trim();
        var box = $('xcWho');
        var ticket = ++xcTicket;

        box.hidden = true;
        clear($('xcReceive'));
        xcReset();

        if (!/^\d{10}$/.test(number)) {
            return;
        }

        api('/recipients/' + number).then(function (r) {
            if (ticket !== xcTicket) {
                return;
            }

            box.textContent = r.data.accountName + ' can receive: ' + r.data.receivableCurrencies.join(', ');
            box.className = 'resolved';
            box.hidden = false;

            r.data.receivableCurrencies.forEach(function (c) {
                var o = el('option', null, c);

                o.value = c;
                $('xcReceive').appendChild(o);
            });
        }, function (e) {
            if (ticket === xcTicket) {
                box.textContent = message(e);
                box.className = 'resolved bad';
                box.hidden = false;
            }
        });
    }

    function xcGetQuote(e) {
        e.preventDefault();
        xcReset();

        api('/transfers/cross-currency/quotes', {
            method: 'POST',
            body: {
                destinationAccountNumber: $('xcTo').value.trim(),
                sourceCurrency: $('xcFrom').value,
                receiveCurrency: $('xcReceive').value,
                amount: Number($('xcAmount').value)
            }
        }).then(function (r) {
            var q = r.data;

            xc.quote = q;
            xc.key = newKey();
            xc.deadline = Date.now() + q.ttlSeconds * 1000;

            drawLines($('xcQuote'), [
                ['Recipient', q.recipientName],
                ['You send', money(q.sourceAmount, q.sourceCurrency)],
                ['Fee (' + q.feeReason + ')', money(q.fee, q.sourceCurrency)],
                ['Converted', money(q.netAmount, q.sourceCurrency)],
                ['Rate', '1 ' + q.sourceCurrency + ' = ' + q.rate + ' ' + q.receiveCurrency],
                ['They receive', q.receiveDisplay]
            ]);

            $('xcActions').hidden = false;
            startCountdown(xc, $('xcCountdown'), $('xcConfirm'));
        }).catch(function (err) {
            toast(message(err), true);
        });
    }

    function xcConfirm() {
        var pin = $('xcPin').value;

        if (!xc.quote || !/^\d{4}$/.test(pin)) {
            toast('Enter your 4-digit PIN.', true);
            return;
        }

        xc.busy = true;
        $('xcConfirm').disabled = true;

        api('/transfers/cross-currency', {
            method: 'POST',
            key: xc.key,
            body: { quoteId: xc.quote.id, pin: pin }
        }).then(function (r) {
            var t = r.data;
            var text = (r.replayed ? 'Already sent. ' : 'Sent. ') + 'They received '
                + money(t.targetAmount, t.targetCurrency) + '. You paid '
                + money(t.totalDebit, t.currency) + '. Reference ' + t.reference + '.';

            clearInterval(xc.timer);
            $('xcActions').hidden = true;
            $('xcCountdown').hidden = true;
            show($('xcResult'), '', text);
            $('xcPin').value = '';
        }).catch(function (err) {
            show($('xcResult'), 'bad', message(err) + ' Nothing was sent unless it says Sent.');
        }).then(function () {
            xc.busy = false;
        });
    }

    /* ---------- bank payout abroad ---------- */

    var PO_IDS = ['poQuote', 'poCountdown', 'poActions', 'poResult'];

    function poReset() {
        resetFlow(po, PO_IDS);
        clearInterval(po.poll);
        $('poPin').value = '';
    }

    function country() {
        var code = $('poCountry').value;

        return dest.countries.filter(function (c) { return c.code === code; })[0];
    }

    function drawBankFields() {
        var box = $('poFields');
        var c = country();

        clear(box);

        if (!c) {
            return;
        }

        c.bankFields.forEach(function (f) {
            var label = el('label', null, f.label + (f.required ? '' : ' (optional)'));
            var input = el('input');

            input.type = 'text';
            input.maxLength = 40;
            input.setAttribute('data-field', f.name);
            input.autocomplete = 'off';
            input.required = f.required;
            label.appendChild(input);
            box.appendChild(label);
        });

        $('poVerified').hidden = true;
        poReset();
    }

    /* Everything the server needs about the bank, from the form. bankCode may be a listed field or the plain input. */
    function bankDetails() {
        var fields = {};
        var bankCode = $('poBankCode').value.trim() || null;

        document.querySelectorAll('#poFields [data-field]').forEach(function (i) {
            var v = i.value.trim();

            if (!v) {
                return;
            }

            if (i.getAttribute('data-field') === 'bankCode') {
                bankCode = v;
            } else {
                fields[i.getAttribute('data-field')] = v;
            }
        });

        return { bankCode: bankCode, bankFields: fields };
    }

    function loadDestinations() {
        return api('/payouts/destinations').then(function (r) {
            dest = r.data;

            clear($('poCountry'));
            dest.countries.forEach(function (c) {
                var o = el('option', null, c.name + ' (' + c.currency + ')');

                o.value = c.code;
                $('poCountry').appendChild(o);
            });

            clear($('poPurpose'));
            dest.purposes.forEach(function (p) {
                var o = el('option', null, p.replace(/_/g, ' ').toLowerCase());

                o.value = p;
                $('poPurpose').appendChild(o);
            });

            drawBankFields();
        }, function (e) {
            toast(message(e), true);
        });
    }

    function poVerify() {
        var c = country();
        var b = bankDetails();
        var box = $('poVerified');

        if (!c || !$('poAccount').value.trim()) {
            toast('Choose a country and enter the account number.', true);
            return;
        }

        api('/payouts/verify-account', {
            method: 'POST',
            body: {
                country: c.code, currency: c.currency, bankCode: b.bankCode,
                accountNumber: $('poAccount').value.trim(), bankFields: b.bankFields
            }
        }).then(function (r) {
            var v = r.data;

            box.textContent = v.accountName + ' (' + v.accountMasked + '). ' + v.label;
            box.className = v.verified ? 'resolved' : 'resolved bad';
            box.hidden = false;
        }).catch(function (err) {
            box.textContent = message(err);
            box.className = 'resolved bad';
            box.hidden = false;
        });
    }

    function poGetQuote(e) {
        e.preventDefault();
        poReset();

        var c = country();
        var b = bankDetails();

        api('/payouts/quotes', {
            method: 'POST',
            body: {
                sourceCurrency: $('poFrom').value, amount: Number($('poAmount').value),
                country: c.code, bankCode: b.bankCode, accountNumber: $('poAccount').value.trim(),
                bankFields: b.bankFields, recipientType: $('poType').value, purpose: $('poPurpose').value
            }
        }).then(function (r) {
            var q = r.data;
            var d = q.destination;

            po.quote = q;
            po.key = newKey();
            po.deadline = Date.now() + q.ttlSeconds * 1000;

            drawLines($('poQuote'), [
                ['Recipient', d.accountName + ' (' + d.accountMasked + ')'],
                ['Name check', d.nameLabel],
                ['You send', money(q.sourceAmount, q.sourceCurrency)],
                ['Fee', money(q.fee, q.sourceCurrency)],
                ['Converted', money(q.netAmount, q.sourceCurrency)],
                ['Rate (' + q.rateMode + ')', '1 ' + q.sourceCurrency + ' = ' + q.rate + ' ' + q.payoutCurrency],
                ['They receive', money(q.payoutAmount, q.payoutCurrency)]
            ]);

            $('poActions').hidden = false;
            startCountdown(po, $('poCountdown'), $('poConfirm'));
        }).catch(function (err) {
            toast(message(err), true);
        });
    }

    function poStatusText(p) {
        if (p.status === 'COMPLETED') {
            return 'Paid. ' + money(p.payoutAmount, p.payoutCurrency) + ' reached the recipient. Reference ' + p.reference + '.';
        }

        if (p.status === 'FAILED') {
            return 'The payout failed and your money was returned. ' + (p.failureReason || '') + ' Reference ' + p.reference + '.';
        }

        return 'Sent to the bank network. Waiting for confirmation. Reference ' + p.reference + '.';
    }

    function poWatch(reference) {
        var tries = 0;

        clearInterval(po.poll);
        po.poll = setInterval(function () {
            tries++;

            api('/payouts/' + reference).then(function (r) {
                var p = r.data;

                show($('poResult'), p.status === 'FAILED' ? 'bad' : (p.status === 'PENDING' ? 'wait' : ''), poStatusText(p));

                if (p.status !== 'PENDING' || tries >= 40) {
                    clearInterval(po.poll);
                    loadPayouts();
                }
            }).catch(function () {
                clearInterval(po.poll);
            });
        }, 3000);
    }

    function poConfirm() {
        var pin = $('poPin').value;

        if (!po.quote || !/^\d{4}$/.test(pin)) {
            toast('Enter your 4-digit PIN.', true);
            return;
        }

        po.busy = true;
        $('poConfirm').disabled = true;

        api('/payouts', {
            method: 'POST',
            key: po.key,
            body: { quoteId: po.quote.quoteId, pin: pin }
        }).then(function (r) {
            clearInterval(po.timer);
            $('poActions').hidden = true;
            $('poCountdown').hidden = true;
            $('poPin').value = '';
            show($('poResult'), r.data.status === 'FAILED' ? 'bad' : 'wait', poStatusText(r.data));
            loadPayouts();

            if (r.data.status === 'PENDING') {
                poWatch(r.data.reference);
            }
        }).catch(function (err) {
            show($('poResult'), 'bad', message(err) + ' If you are unsure, check My payouts before trying again.');
        }).then(function () {
            po.busy = false;
        });
    }

    function loadPayouts() {
        api('/payouts').then(function (r) {
            var list = $('poList');

            clear(list);

            if (!r.data.length) {
                var none = el('li');

                none.appendChild(el('div', 'main', 'No payouts yet.'));
                list.appendChild(none);
                return;
            }

            r.data.forEach(function (p) {
                var li = el('li');
                var main = el('div', 'main');
                var kind = p.status === 'COMPLETED' ? 'ok' : (p.status === 'FAILED' ? 'bad' : 'wait');

                main.appendChild(el('div', 'title', money(p.sourceAmount, p.sourceCurrency)
                    + ' to ' + money(p.payoutAmount, p.payoutCurrency)));
                main.appendChild(el('div', 'sub', p.reference + '. ' + (p.failureReason || p.purpose || '')));
                li.appendChild(main);
                li.appendChild(el('span', 'chip ' + kind, p.status));
                list.appendChild(li);
            });
        }, function (e) {
            toast(message(e), true);
        });
    }

    /* ---------- wiring ---------- */

    $('xcTo').addEventListener('input', xcLookup);
    $('xcForm').addEventListener('input', function (e) {
        if (e.target.id !== 'xcTo') {
            xcReset();
        }
    });
    $('xcForm').addEventListener('submit', xcGetQuote);
    $('xcConfirm').addEventListener('click', xcConfirm);
    $('xcCancel').addEventListener('click', xcReset);

    $('poCountry').addEventListener('change', drawBankFields);
    $('poForm').addEventListener('input', function (e) {
        if (e.target.id !== 'poCountry') {
            poReset();
        }
    });
    $('poForm').addEventListener('submit', poGetQuote);
    $('poVerifyBtn').addEventListener('click', poVerify);
    $('poConfirm').addEventListener('click', poConfirm);
    $('poCancel').addEventListener('click', poReset);
    $('poListBtn').addEventListener('click', loadPayouts);

    window.PayBridgeAbroad = {
        open: function () {
            xcReset();
            poReset();

            api('/wallets').then(function (r) {
                wallets = r.data || [];
                fillWallets($('xcFrom'));
                fillWallets($('poFrom'));
            }, function (e) {
                toast(message(e), true);
            });

            loadDestinations();
            loadPayouts();
        }
    };
})();