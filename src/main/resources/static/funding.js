/* PayBridge wallet account details: the account number and bank details issued for one wallet.
   SANDBOX: the details are simulated. Server text only goes in with textContent (never innerHTML). */
(function () {
    'use strict';

    var bridge = window.PayBridge;

    if (!bridge) {
        return;
    }

    var api = bridge.api;
    var el = bridge.el;
    var toast = bridge.toast;

    function closePanel() {
        var old = document.getElementById('fundingOverlay');

        if (old) {
            old.parentNode.removeChild(old);
        }
    }

    function copy(text) {
        try {
            navigator.clipboard.writeText(text).then(function () {
                toast('Copied.', false);
            }, function () {
                toast('Could not copy. Select the text instead.', true);
            });
        } catch (e) {
            toast('Could not copy. Select the text instead.', true);
        }
    }

    function row(label, value) {
        if (!value) {
            return null;
        }

        var line = el('div');
        line.style.cssText = 'display:flex;justify-content:space-between;align-items:center;gap:12px;'
            + 'padding:10px 0;border-bottom:1px solid var(--border-subtle);';

        var left = el('div');
        var name = el('div', 'hint', label);
        var val = el('div', null, value);
        val.style.cssText = 'font-weight:600;word-break:break-all;';
        left.appendChild(name);
        left.appendChild(val);

        var btn = el('button', 'btn-wallet-action', 'Copy');
        btn.type = 'button';
        btn.addEventListener('click', function () {
            copy(value);
        });

        line.appendChild(left);
        line.appendChild(btn);
        return line;
    }

    function render(panel, currency, accountNumber, d, onChange) {
        panel.textContent = '';

        var title = el('h3', null, currency + ' account details');
        title.style.margin = '0 0 4px';
        panel.appendChild(title);

        var note = el('p', 'hint', d.note);
        note.style.cssText = 'margin:0 0 8px;color:#b45309;';
        panel.appendChild(note);

        [
            row('Account name', d.accountName),
            row('Bank', d.bankName + ' (' + d.bankCountry + ')'),
            row('Account number', d.accountNumber),
            row(d.routingLabel, d.routingValue),
            row('IBAN', d.iban),
            row('SWIFT / BIC', d.swiftBic),
            row('Payment reference', d.reference)
        ].forEach(function (line) {
            if (line) {
                panel.appendChild(line);
            }
        });

        var test = el('div');
        test.style.cssText = 'margin-top:14px;padding-top:12px;border-top:1px dashed var(--border-subtle);';
        test.appendChild(el('div', 'hint', 'Test only: pretend someone paid into these details'));

        var amount = el('input');
        amount.type = 'number';
        amount.min = '0';
        amount.step = '0.01';
        amount.value = '100';
        amount.setAttribute('aria-label', 'Amount in ' + currency);
        amount.style.cssText = 'width:120px;margin:6px 8px 0 0;padding:8px;';

        var send = el('button', 'btn-wallet-action', 'Simulate incoming transfer');
        send.type = 'button';
        send.addEventListener('click', function () {
            var value = Number(amount.value);

            if (!(value > 0)) {
                toast('Enter an amount above zero.', true);
                return;
            }

            send.disabled = true;
            api('/dev/accounts/' + accountNumber + '/fund', {
                method: 'POST',
                body: { amount: value, currency: currency }
            }).then(function () {
                send.disabled = false;
                toast('Received ' + value + ' ' + currency + ' into your wallet.', false);

                if (onChange) {
                    onChange();
                }
            }, function (e) {
                send.disabled = false;
                toast(e && e.status === 404 ? 'Test deposits are switched off on this server.' : bridge.friendly(e), true);
            });
        });

        test.appendChild(amount);
        test.appendChild(send);
        panel.appendChild(test);

        var close = el('button', 'btn-wallet-action', 'Close');
        close.type = 'button';
        close.style.marginTop = '14px';
        close.addEventListener('click', closePanel);
        panel.appendChild(close);
    }

    function show(currency, accountNumber, onChange) {
        closePanel();

        var overlay = el('div');
        overlay.id = 'fundingOverlay';
        overlay.style.cssText = 'position:fixed;inset:0;background:rgba(7,12,24,.6);z-index:1000;display:flex;'
            + 'align-items:center;justify-content:center;padding:16px;';
        overlay.addEventListener('click', function (e) {
            if (e.target === overlay) {
                closePanel();
            }
        });

        var panel = el('div');
        panel.style.cssText = 'background:var(--bg-surface);color:var(--text-dark);border-radius:16px;'
            + 'padding:18px;width:100%;max-width:460px;max-height:90vh;overflow:auto;';
        panel.appendChild(el('p', 'hint', 'Loading…'));

        overlay.appendChild(panel);
        document.body.appendChild(overlay);

        api('/wallets/' + encodeURIComponent(currency) + '/funding-details').then(function (d) {
            render(panel, currency, accountNumber, d, onChange);
        }, function (e) {
            panel.textContent = '';
            panel.appendChild(el('p', null, bridge.friendly(e)));

            var close = el('button', 'btn-wallet-action', 'Close');
            close.type = 'button';
            close.addEventListener('click', closePanel);
            panel.appendChild(close);
        });
    }

    window.PayBridgeFunding = { show: show };
})();