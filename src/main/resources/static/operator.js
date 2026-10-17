/* PayBridge operator screen: positions, batches, exceptions, sweeps (plan 002-004, steps C1 to C4).
   Plain JavaScript, no libraries. Text from the server only goes in with textContent (never innerHTML).
   The operator key lives in memory only and is sent as X-Admin-Key. */
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

    var adminKey = '';

    var MESSAGES = {
        ADMIN_KEY_REQUIRED: 'That operator key is not right.',
        NOT_FOUND: 'Settlement is switched off: no operator key is set on the server.',
        VALIDATION_FAILED: 'Please check what you typed and try again.'
    };

    function problem(err) {
        if (err && err.network) {
            return bridge.friendly(err);
        }

        if (err && err.code && MESSAGES[err.code]) {
            return MESSAGES[err.code];
        }

        return (err && err.message) || 'Something went wrong. Please try again.';
    }

    function call(path, options) {
        options = options || {};
        options.headers = { 'X-Admin-Key': adminKey };

        return api(path, options).then(function (r) {
            return r.data;
        });
    }

    function fail(err) {
        toast(problem(err), true);
    }

    /* Minor units to text, using the currency's own number of decimals. */
    function fmt(minor, code) {
        try {
            var f = new Intl.NumberFormat('en-US', { style: 'currency', currency: code });
            var digits = f.resolvedOptions().maximumFractionDigits;

            return f.format(Number(minor) / Math.pow(10, digits));
        } catch (e) {
            return code + ' ' + minor;
        }
    }

    function chip(text, kind) {
        return el('span', 'chip ' + kind, text);
    }

    function row(title, sub, right) {
        var li = el('li');
        var main = el('div', 'main');

        main.appendChild(el('div', 'title', title));

        if (sub) {
            main.appendChild(el('div', 'sub', sub));
        }

        li.appendChild(main);

        if (right) {
            li.appendChild(right);
        }

        return li;
    }

    function empty(list, text) {
        clear(list);
        list.appendChild(row(text));
    }

    /* ---------- positions (C1) ---------- */

    function loadPositions() {
        call('/admin/settlement/positions').then(function (report) {
            var warnings = $('opWarnings');
            var box = $('opPositions');

            clear(warnings);
            clear(box);

            report.warnings.forEach(function (w) {
                warnings.appendChild(row(w, null, chip('below minimum', 'bad')));
            });

            report.currencies.forEach(function (c) {
                box.appendChild(el('p', 'group', c.currency));

                var list = el('ul', 'list');

                c.positions.forEach(function (p) {
                    var right = el('div');

                    right.appendChild(el('div', 'amt', p.display));

                    if (p.belowThreshold) {
                        right.appendChild(chip('below minimum', 'bad'));
                    }

                    list.appendChild(row(p.name, p.account, right));
                });

                box.appendChild(list);
            });
        }).catch(fail);
    }

    /* ---------- batches (C2) ---------- */

    function statusKind(status) {
        if (status === 'SETTLED' || status === 'CLOSED') {
            return 'ok';
        }

        return status === 'EXCEPTION' ? 'bad' : 'wait';
    }

    function loadBatches() {
        var date = $('opDate').value;

        if (!date) {
            toast('Choose a day first.', true);
            return;
        }

        call('/admin/settlement/batches?date=' + encodeURIComponent(date)).then(function (batches) {
            var list = $('opBatches');

            if (!batches.length) {
                empty(list, 'No batches for ' + date + '.');
                return;
            }

            clear(list);

            batches.forEach(function (b) {
                var sub = b.itemCount + ' items. Ours ' + fmt(b.ourTotalMinor, b.currency)
                    + ', partner ' + fmt(b.partnerTotalMinor, b.currency)
                    + '. UTC ' + b.periodStart.slice(11, 16) + ' to ' + b.periodEnd.slice(11, 16);

                list.appendChild(row(b.partner + ' ' + b.currency, sub, chip(b.status, statusKind(b.status))));
            });
        }).catch(fail);
    }

    function runBatches() {
        var date = $('opDate').value;

        if (!date) {
            toast('Choose a day first.', true);
            return;
        }

        call('/admin/settlement/batches/run?date=' + encodeURIComponent(date), { method: 'POST' })
            .then(function (s) {
                toast('Run done: ' + s.batchesCreated + ' new, ' + s.batchesAlreadyThere + ' already there.', false);
                loadBatches();
                loadExceptions();
            }).catch(fail);
    }

    /* ---------- exceptions (C3) ---------- */

    function loadExceptions() {
        var path = '/admin/settlement/exceptions' + ($('opExcAll').checked ? '?includeResolved=true' : '');

        call(path).then(function (items) {
            var list = $('opExceptions');

            if (!items.length) {
                empty(list, 'No open exceptions.');
                return;
            }

            clear(list);

            items.forEach(function (x) {
                var amounts = 'Ours ' + (x.ourAmountMinor === null ? 'none' : fmt(x.ourAmountMinor, x.currency))
                    + ', partner ' + (x.partnerAmountMinor === null ? 'none' : fmt(x.partnerAmountMinor, x.currency));
                var sub = x.partner + ' ' + x.periodDate + '. ' + amounts;
                var right = el('div');

                if (x.resolvedAt) {
                    right.appendChild(chip('resolved', 'ok'));
                    sub += '. Note: ' + x.resolutionNote;
                } else {
                    var button = el('button', null, 'Resolve');

                    button.type = 'button';
                    button.addEventListener('click', function () {
                        resolve(x);
                    });
                    right.appendChild(button);
                }

                list.appendChild(row(x.type + ': ' + x.reference, sub, right));
            });
        }).catch(fail);
    }

    function resolve(x) {
        var note = window.prompt('Why is ' + x.reference + ' resolved? A note is required.');

        if (note === null) {
            return;
        }

        if (!note.trim()) {
            toast('A resolution note is required.', true);
            return;
        }

        call('/admin/settlement/exceptions/' + x.id + '/resolve', { method: 'POST', body: { note: note.trim() } })
            .then(function () {
                toast('Resolved.', false);
                loadExceptions();
                loadBatches();
            }).catch(fail);
    }

    /* ---------- sweeps (C4) ---------- */

    function runSweep() {
        var ccy = $('opSweepCcy').value.trim().toUpperCase();

        if (ccy && !/^[A-Z]{3}$/.test(ccy)) {
            toast('Use a three-letter currency code, for example NGN.', true);
            return;
        }

        if (!window.confirm('Sweep ' + (ccy || 'every currency') + ' now? This moves real ledger balances.')) {
            return;
        }

        var options = { method: 'POST' };

        if (ccy) {
            options.body = { currency: ccy };
        }

        call('/admin/settlement/sweeps', options).then(function (report) {
            var list = $('opSweepResult');

            clear(list);

            report.swept.forEach(function (s) {
                list.appendChild(row(s.source + ' to ' + s.destination, s.journalKey, el('div', 'amt', s.amountShown)));
            });

            report.failed.forEach(function (f) {
                list.appendChild(row(f.source, f.reason, chip('failed', 'bad')));
            });

            if (report.nothingToSweep.length) {
                list.appendChild(row(report.nothingToSweep.length + ' accounts had nothing to sweep.'));
            }

            if (!report.swept.length && !report.failed.length) {
                toast('Nothing to sweep.', false);
            } else {
                toast('Swept ' + report.swept.length + ' accounts.', false);
            }

            loadPositions();
        }).catch(fail);
    }

    /* ---------- wiring ---------- */

    $('opKeyForm').addEventListener('submit', function (e) {
        e.preventDefault();
        adminKey = $('opKey').value;
        $('opPanels').hidden = false;
        loadPositions();
        loadBatches();
        loadExceptions();
    });

    $('opPosLoad').addEventListener('click', loadPositions);
    $('opBatchLoad').addEventListener('click', loadBatches);
    $('opBatchRun').addEventListener('click', runBatches);
    $('opExcLoad').addEventListener('click', loadExceptions);
    $('opExcAll').addEventListener('change', loadExceptions);
    $('opSweepRun').addEventListener('click', runSweep);

    function setDate() {
        if (!$('opDate').value) {
            $('opDate').value = new Date(Date.now() - 86400000).toISOString().slice(0, 10);
        }
    }

    new MutationObserver(function () {
        if (!$('operatorView').hidden) {
            setDate();
        }
    }).observe($('operatorView'), { attributes: true, attributeFilter: ['hidden'] });

    window.PayBridgeOperator = {
        open: setDate,
        reset: function () {
            adminKey = '';
            $('opKey').value = '';
            $('opPanels').hidden = true;
        }
    };
})();