/* PayBridge convert screen. Plain JavaScript, no libraries, no build step.
   Loaded after app.js, which shares what this file needs through window.PayBridge.
   Same rules as app.js: text from the server only ever goes in with textContent (never innerHTML). */
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

    var wallets = [];
    var quote = null;
    var deadline = 0;
    var countdownTimer = null;
    var autoRenewals = 0;     // how many times a quote was renewed on its own since the customer last asked
    var confirming = false;
    var ticket = 0;           // an answer that belongs to an older request is ignored

    var MESSAGES = {
        INSUFFICIENT_FUNDS: 'You do not have enough money in that wallet, including the fee.',
        INVALID_AMOUNT: 'Enter an amount greater than zero.',
        INVALID_CURRENCY: 'Choose two different currencies.',
        NO_RATE: 'No exchange rate is available for that pair right now. Try again in a few minutes.',
        CURRENCY_NOT_SUPPORTED: 'That currency is not supported.',
        WALLET_NOT_FOUND: 'We could not find that wallet.',
        WALLET_PARKED: 'That wallet is parked. Activate it on the Home page first.',
        ACCOUNT_FROZEN: 'This account is frozen. Unfreeze it to convert money.',
        AMOUNT_TOO_SMALL: 'That amount is too small once the fee is taken off.',
        LIMIT_EXCEEDED: 'This conversion is over your limit. Try a smaller amount.',
        TOO_MANY_ACTIVE_QUOTES: 'You have too many open quotes. Wait a minute and try again.',
        QUOTE_EXPIRED: 'That quote has expired. Get a new quote.',
        QUOTE_ALREADY_USED: 'That quote was already used. Check your balances.',
        QUOTE_FAILED: 'That quote failed and cannot be reused. Get a new quote.',
        QUOTE_NOT_FOUND: 'We could not find that quote. Get a new quote.'
    };

    function message(err) {
        if (err && err.code && MESSAGES[err.code]) {
            return MESSAGES[err.code];
        }

        if (err && err.status === 404 && !err.code) {
            return 'Conversion is not switched on yet.';
        }

        if (err && err.status === 503) {
            return 'Exchange rates are not available right now. Try again in a few minutes.';
        }

        return bridge.friendly(err);
    }

    function showResult(kind, text) {
        var box = $('convResult');

        box.className = 'result' + (kind === 'bad' ? ' bad' : (kind === 'wait' ? ' wait' : ''));
        box.textContent = text;
        box.hidden = false;
    }

    function activeWallets() {
        return wallets.filter(function (w) {
            return w.status === 'ACTIVE';
        });
    }

    /* ---------- the two pickers ---------- */

    function fillFrom() {
        var pick = $('convFrom');
        var before = pick.value;

        clear(pick);

        activeWallets().forEach(function (w) {
            var o = el('option', null, w.currency + ' (' + w.display + ')');

            o.value = w.currency;
            pick.appendChild(o);
        });

        if (before) {
            pick.value = before;
        }

        if (!pick.value && pick.options.length) {
            pick.selectedIndex = 0;
        }

        fillTo();
    }

    function fillTo() {
        var pick = $('convTo');
        var before = pick.value;
        var from = $('convFrom').value;

        clear(pick);

        activeWallets().forEach(function (w) {
            if (w.currency === from) {
                return;
            }

            var o = el('option', null, w.currency + ' (' + w.display + ')');

            o.value = w.currency;
            pick.appendChild(o);
        });

        if (before) {
            pick.value = before;
        }

        if (!pick.value && pick.options.length) {
            pick.selectedIndex = 0;
        }

        $('convCcy').textContent = from ? '(' + from + ')' : '';
    }

    function loadWallets() {
        return api('/wallets').then(function (r) {
            wallets = r.data || [];
            fillFrom();

            var enough = activeWallets().length >= 2;

            $('convertForm').hidden = !enough;
            $('convHint').hidden = enough;
            $('convHint').textContent = 'You need at least two active wallets to convert. '
                + 'Activate another one on the Home page with "Swap a wallet".';
        }, function (e) {
            bridge.toast(message(e), true);
        });
    }

    /* ---------- the quote ---------- */

    function formatRate(rate) {
        var n = Number(rate);

        if (!isFinite(n) || n === 0) {
            return String(rate);
        }

        if (n >= 1000) {
            return n.toLocaleString('en-US', { maximumFractionDigits: 2 });
        }

        return String(Number(n.toPrecision(6)));
    }

    function stopCountdown() {
        clearInterval(countdownTimer);
        countdownTimer = null;
    }

    function resetQuote() {
        quote = null;
        stopCountdown();

        $('convQuote').hidden = true;
        $('convCountdown').hidden = true;
        $('convActions').hidden = true;

        clear($('convQuote'));
    }

    function renderQuote(q) {
        var box = $('convQuote');
        var feeText = q.feeReason === 'PROMO'
            ? q.feeDisplay + ' (waived for your first 30 days)'
            : q.feeDisplay;

        var rows = [
            ['You convert', q.sourceDisplay],
            ['Fee', feeText],
            ['Converted after fee', q.netDisplay],
            ['Rate (' + q.rateLabel + ')', '1 ' + q.sourceCurrency + ' = ' + formatRate(q.rate) + ' ' + q.targetCurrency]
        ];

        clear(box);

        rows.forEach(function (row) {
            var line = el('div');

            line.appendChild(el('span', null, row[0]));
            line.appendChild(el('span', null, row[1]));
            box.appendChild(line);
        });

        var total = el('div', 'total');

        total.appendChild(el('span', null, 'You receive'));
        total.appendChild(el('span', null, q.targetDisplay));
        box.appendChild(total);

        box.hidden = false;
    }

    function tick() {
        // The customer left this screen or signed out: stop quietly.
        if ($('appView').hidden || $('convertView').hidden) {
            ticket++;
            resetQuote();
            return;
        }

        var left = Math.ceil((deadline - Date.now()) / 1000);

        if (left > 0) {
            $('convCountdown').textContent = 'This quote is locked for ' + left + ' second' + (left === 1 ? '' : 's') + '.';
            return;
        }

        stopCountdown();

        // One automatic renewal, so a quote left open cannot keep creating new ones forever.
        if (autoRenewals < 1) {
            autoRenewals++;
            requestQuote(true);
            return;
        }

        resetQuote();
        showResult('wait', 'That quote expired. Get a new quote when you are ready.');
    }

    function showQuote(q, renewed) {
        quote = q;
        renderQuote(q);

        deadline = Date.now() + q.ttlSeconds * 1000;

        $('convCountdown').hidden = false;
        $('convActions').hidden = false;
        $('convConfirm').disabled = false;
        $('convCancel').disabled = false;

        tick();
        stopCountdown();
        countdownTimer = setInterval(tick, 500);

        if (renewed) {
            showResult('wait', 'Your last quote expired, so this is a new one. Check the amount you receive.');
        } else {
            $('convResult').hidden = true;
        }
    }

    function requestQuote(renewed) {
        var from = $('convFrom').value;
        var to = $('convTo').value;
        var amount = Number($('convAmount').value);

        if (!from || !to) {
            return;
        }

        if (!isFinite(amount) || amount <= 0) {
            showResult('bad', MESSAGES.INVALID_AMOUNT);
            return;
        }

        var mine = ++ticket;

        resetQuote();
        $('convQuoteButton').disabled = true;

        api('/conversions/quotes', {
            method: 'POST',
            body: { sourceCurrency: from, targetCurrency: to, amount: amount }
        }).then(function (r) {
            if (mine === ticket) {
                showQuote(r.data, renewed);
            }
        }, function (e) {
            if (mine === ticket) {
                showResult('bad', message(e));
            }
        }).then(function () {
            $('convQuoteButton').disabled = false;
        });
    }

    function confirm() {
        if (!quote || confirming) {
            return;
        }

        var id = quote.id;

        confirming = true;
        stopCountdown();
        $('convConfirm').disabled = true;
        $('convCancel').disabled = true;

        api('/conversions', {
            method: 'POST',
            body: { quoteId: id }
        }).then(function (r) {
            var c = r.data;

            resetQuote();
            $('convAmount').value = '';

            showResult('ok', 'Done. You converted ' + c.sourceDisplay + ' and received ' + c.targetDisplay
                + '. Fee: ' + c.feeDisplay + '.');
            bridge.toast('Conversion complete.', false);
        }, function (e) {
            resetQuote();

            if (e && e.network) {
                showResult('bad', 'We could not confirm whether the conversion went through. '
                    + 'Check your balances before you try again.');
            } else {
                showResult('bad', message(e));
            }
        }).then(function () {
            confirming = false;
            loadWallets();
        });
    }

    /* ---------- wiring ---------- */

    function cancel() {
        ticket++;
        resetQuote();
        $('convResult').hidden = true;
    }

    // Any change to the form makes the old quote wrong, so it is dropped.
    function formChanged() {
        if (quote && !confirming) {
            cancel();
        }
    }

    $('convertForm').addEventListener('submit', function (e) {
        e.preventDefault();
        autoRenewals = 0;
        requestQuote(false);
    });

    $('convFrom').addEventListener('change', function () {
        formChanged();
        fillTo();
    });

    $('convTo').addEventListener('change', formChanged);
    $('convAmount').addEventListener('input', formChanged);
    $('convConfirm').addEventListener('click', confirm);
    $('convCancel').addEventListener('click', cancel);

    window.PayBridgeConvert = {
        open: function () {
            ticket++;
            autoRenewals = 0;
            resetQuote();
            $('convResult').hidden = true;
            loadWallets();
        }
    };
})();