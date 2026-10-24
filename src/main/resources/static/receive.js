/* PayBridge "Receive money" panel: the bank details of each wallet, so money can be sent straight into it.
   Plain JavaScript, loaded after app.js, which shares what this file needs through window.PayBridge.
   Text from the server only ever goes in with textContent (never innerHTML). */
(function () {
    'use strict';

    var bridge = window.PayBridge;
    if (!bridge) {
        return;
    }

    var api = bridge.api;
    var el = bridge.el;
    var clear = bridge.clear;
    var toast = bridge.toast;
    var friendly = bridge.friendly;

    var FLAGS = { GBP: '🇬🇧', USD: '🇺🇸', EUR: '🇪🇺', NGN: '🇳🇬' };

    function field(label, value) {
        if (!value) {
            return null;
        }
        var row = el('div', 'receive-row');
        row.appendChild(el('span', 'receive-label', label));
        row.appendChild(el('span', 'receive-value', value));
        var copy = el('button', 'receive-copy', 'Copy');
        copy.type = 'button';
        copy.addEventListener('click', function () {
            var done = function () { toast(label + ' copied'); };
            if (navigator.clipboard && navigator.clipboard.writeText) {
                navigator.clipboard.writeText(value).then(done, function () { toast('Copy failed. Select the text instead.', true); });
            } else {
                toast('Copy is not available here. Select the text instead.', true);
            }
        });
        row.appendChild(copy);
        return row;
    }

    function routingLabel(type) {
        if (type === 'SORT_CODE') return 'Sort code';
        if (type === 'ABA_ROUTING') return 'Routing number (ABA)';
        if (type === 'BANK_CODE') return 'Bank code';
        return 'Routing';
    }

    function card(d, host) {
        var c = el('div', 'receive-card');
        var head = el('div', 'receive-head');
        head.appendChild(el('span', 'receive-flag', FLAGS[d.currency] || '🌐'));
        head.appendChild(el('span', 'receive-title', d.currency + ' account'));
        c.appendChild(head);
        c.appendChild(el('p', 'hint', 'Money sent to these details is added to your ' + d.currency + ' wallet only.'));

        [
            field('Account name', d.accountName),
            field('Bank', d.bankName),
            field('Account number', d.accountNumber),
            field(routingLabel(d.routingType), d.routingValue),
            field('IBAN', d.iban),
            field('SWIFT / BIC', d.bic)
        ].forEach(function (row) {
            if (row) c.appendChild(row);
        });

        var sim = el('div', 'receive-sim');
        sim.appendChild(el('span', 'hint', 'Demo only: pretend a bank just sent money here.'));
        var amount = el('input');
        amount.type = 'number';
        amount.min = '0.01';
        amount.step = '0.01';
        amount.value = '100';
        amount.setAttribute('aria-label', 'Amount to simulate in ' + d.currency);
        var go = el('button', 'btn-wallet-action', 'Simulate deposit');
        go.type = 'button';
        go.addEventListener('click', function () {
            go.disabled = true;
            api('/dev/funding-details/deposit', {
                method: 'POST',
                body: { accountNumber: d.accountNumber, amount: amount.value }
            }).then(function (r) {
                toast((r.data && r.data.message) || 'Deposit received');
                if (bridge.loadAccounts) bridge.loadAccounts();
            }, function (e) {
                toast(friendly(e), true);
            }).then(function () {
                go.disabled = false;
            });
        });
        sim.appendChild(amount);
        sim.appendChild(go);
        c.appendChild(sim);
        host.appendChild(c);
    }

    function open() {
        var host = document.getElementById('receivePanel');
        if (!host) {
            return;
        }
        clear(host);
        api('/wallets/funding-details').then(function (r) {
            var list = r.data || [];
            if (!list.length) {
                host.appendChild(el('p', 'hint', 'No receiving bank details are available for your wallets yet.'));
                return;
            }
            list.forEach(function (d) { card(d, host); });
        }, function (e) {
            host.appendChild(el('p', 'hint', friendly(e)));
        });
    }

    window.PayBridgeReceive = { open: open };
})();