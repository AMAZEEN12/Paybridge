const fs = require('fs');
const path = 'C:\\Users\\NWAFOR BLESSING\\Downloads\\paybridge-1\\paybridge\\src\\main\\resources\\static\\app.js';

let content = fs.readFileSync(path, 'utf8');

// 1. Update renderAccounts to also call renderAllWalletsGrid and renderAllCurrenciesExplorer
const renderAllWalletsCode = `
    function renderAllWalletsGrid() {
        var grid = $('allWalletsGrid');
        var fullGrid = $('walletsFullGrid');
        if (!grid && !fullGrid) return;

        if (grid) clear(grid);
        if (fullGrid) clear(fullGrid);

        var activeCount = 0;
        var parkedCount = 0;

        wallets.forEach(function (w) {
            if (w.status === 'ACTIVE') activeCount++;
            if (w.status === 'PARKED') parkedCount++;

            var flag = FLAGS[w.currency] || '🌐';
            var cInfo = currencyList.filter(function (c) { return c.code === w.currency; })[0];
            var cName = cInfo ? cInfo.name : w.currency;

            // Build item card
            var card = el('div', 'wallet-item-card' + (w.status === 'ACTIVE' ? ' is-active' : ' is-parked'));
            var top = el('div', 'wallet-item-top');
            
            var flagName = el('div', 'wallet-flag-name');
            flagName.appendChild(el('span', 'wallet-flag-emoji', flag));
            var titleBox = el('div');
            titleBox.appendChild(el('div', 'wallet-title-bold', w.currency));
            titleBox.appendChild(el('div', 'hint', cName));
            flagName.appendChild(titleBox);
            top.appendChild(flagName);

            var badge = el('span', 'wallet-badge-status ' + (w.home ? 'home' : (w.status === 'ACTIVE' ? 'active' : 'parked')), 
                           w.home ? 'HOME' : w.status);
            top.appendChild(badge);
            card.appendChild(top);

            var bal = el('div', 'wallet-balance-amount', w.display || moneyIn(w.balance, w.currency));
            card.appendChild(bal);

            var actions = el('div', 'wallet-item-actions');
            var selectBtn = el('button', 'btn-wallet-action', w.currency === selectedCurrency ? 'Selected' : 'View in Hero');
            selectBtn.type = 'button';
            selectBtn.addEventListener('click', function (e) {
                e.stopPropagation();
                selectedCurrency = w.currency;
                renderWalletStrip();
                renderHeroBalance();
                renderAllWalletsGrid();
            });
            actions.appendChild(selectBtn);

            var addBtn = el('button', 'btn-wallet-action', '+ Test Money');
            addBtn.type = 'button';
            addBtn.addEventListener('click', function (e) {
                e.stopPropagation();
                if (accounts.length) {
                    if (w.currency === 'NGN') {
                        fundAccount(accounts[0].accountNumber);
                    } else {
                        fundWallet(accounts[0].accountNumber, w.currency);
                    }
                }
            });
            actions.appendChild(addBtn);

            card.appendChild(actions);

            card.addEventListener('click', function () {
                selectedCurrency = w.currency;
                renderWalletStrip();
                renderHeroBalance();
                renderAllWalletsGrid();
            });

            if (grid) grid.appendChild(card.cloneNode(true));
            if (fullGrid) fullGrid.appendChild(card);
        });

        // Re-attach event listeners on grid items if cloned
        if (grid) {
            clear(grid);
            wallets.forEach(function (w) {
                var flag = FLAGS[w.currency] || '🌐';
                var cInfo = currencyList.filter(function (c) { return c.code === w.currency; })[0];
                var cName = cInfo ? cInfo.name : w.currency;

                var card = el('div', 'wallet-item-card' + (w.status === 'ACTIVE' ? ' is-active' : ' is-parked'));
                var top = el('div', 'wallet-item-top');
                
                var flagName = el('div', 'wallet-flag-name');
                flagName.appendChild(el('span', 'wallet-flag-emoji', flag));
                var titleBox = el('div');
                titleBox.appendChild(el('div', 'wallet-title-bold', w.currency));
                titleBox.appendChild(el('div', 'hint', cName));
                flagName.appendChild(titleBox);
                top.appendChild(flagName);

                var badge = el('span', 'wallet-badge-status ' + (w.home ? 'home' : (w.status === 'ACTIVE' ? 'active' : 'parked')), 
                               w.home ? 'HOME' : w.status);
                top.appendChild(badge);
                card.appendChild(top);

                var bal = el('div', 'wallet-balance-amount', w.display || moneyIn(w.balance, w.currency));
                card.appendChild(bal);

                var actions = el('div', 'wallet-item-actions');
                var selectBtn = el('button', 'btn-wallet-action', w.currency === selectedCurrency ? 'Selected' : 'View in Hero');
                selectBtn.type = 'button';
                selectBtn.addEventListener('click', function (e) {
                    e.stopPropagation();
                    selectedCurrency = w.currency;
                    renderWalletStrip();
                    renderHeroBalance();
                    renderAllWalletsGrid();
                });
                actions.appendChild(selectBtn);

                var addBtn = el('button', 'btn-wallet-action', '+ Test Money');
                addBtn.type = 'button';
                addBtn.addEventListener('click', function (e) {
                    e.stopPropagation();
                    if (accounts.length) {
                        if (w.currency === 'NGN') {
                            fundAccount(accounts[0].accountNumber);
                        } else {
                            fundWallet(accounts[0].accountNumber, w.currency);
                        }
                    }
                });
                actions.appendChild(addBtn);

                card.appendChild(actions);

                card.addEventListener('click', function () {
                    selectedCurrency = w.currency;
                    renderWalletStrip();
                    renderHeroBalance();
                    renderAllWalletsGrid();
                });

                grid.appendChild(card);
            });
        }

        var capText = $('walletCapacityText');
        if (capText) {
            capText.textContent = 'Active slots: ' + activeCount + ' of 4 used';
        }

        var actStat = $('activeWalletsCount');
        if (actStat) actStat.textContent = activeCount;

        var parkStat = $('parkedWalletsCount');
        if (parkStat) parkStat.textContent = parkedCount;

        renderAllCurrenciesExplorer();
    }

    function renderAllCurrenciesExplorer() {
        var grid = $('allCurrenciesGrid');
        if (!grid || !currencyList.length) return;
        clear(grid);

        currencyList.forEach(function (c) {
            var flag = FLAGS[c.code] || '🌐';
            var myWallet = wallets.filter(function (w) { return w.currency === c.code; })[0];

            var card = el('div', 'currency-browse-card');
            var left = el('div', 'currency-browse-left');
            left.appendChild(el('span', 'wallet-flag-emoji', flag));
            var nameBox = el('div', 'currency-browse-name');
            nameBox.appendChild(el('span', 'currency-browse-code', c.code));
            nameBox.appendChild(el('span', 'currency-browse-full', c.name));
            left.appendChild(nameBox);
            card.appendChild(left);

            var statusPill;
            if (myWallet) {
                statusPill = el('span', 'wallet-badge-status ' + (myWallet.status === 'ACTIVE' ? 'active' : 'parked'), myWallet.status);
            } else {
                var swapBtn = el('button', 'btn-wallet-action', 'Activate');
                swapBtn.type = 'button';
                swapBtn.addEventListener('click', function () {
                    var sc = $('swapCard');
                    if (sc) {
                        sc.hidden = false;
                        sc.scrollIntoView({ behavior: 'smooth' });
                        $('swapCurrency').value = c.code;
                        onSwapCurrencyChange();
                    }
                });
                statusPill = swapBtn;
            }
            card.appendChild(statusPill);
            grid.appendChild(card);
        });
    }
`;

// Insert renderAllWalletsGrid before renderAccounts
content = content.replace('function renderAccounts() {', renderAllWalletsCode + '\n    function renderAccounts() {');

// Call renderAllWalletsGrid inside renderAccounts
content = content.replace('renderTransactionFeed();', `renderTransactionFeed();
        renderAllWalletsGrid();`);

// Wire up the new buttons
const wireAdditions = `
        if ($('actionBtnAbroad')) {
            $('actionBtnAbroad').addEventListener('click', function () {
                showView('abroad');
            });
        }
        if ($('btnOpenSwapModal')) {
            $('btnOpenSwapModal').addEventListener('click', function () {
                var sc = $('swapCard');
                if (sc) {
                    sc.hidden = false;
                    sc.scrollIntoView({ behavior: 'smooth' });
                }
            });
        }
        if ($('btnSwapFromWalletsTab')) {
            $('btnSwapFromWalletsTab').addEventListener('click', function () {
                showView('home');
                var sc = $('swapCard');
                if (sc) {
                    sc.hidden = false;
                    sc.scrollIntoView({ behavior: 'smooth' });
                }
            });
        }
        if ($('btnCloseSwap')) {
            $('btnCloseSwap').addEventListener('click', function () {
                var sc = $('swapCard');
                if (sc) sc.hidden = true;
            });
        }
`;

content = content.replace(`$('tabLogin').addEventListener('click', function () {`, wireAdditions + `\n        $('tabLogin').addEventListener('click', function () {`);

// Update showView to handle .nav-tab active class switching
const showViewTarget = `document.querySelectorAll('.tab').forEach(function (t) {
            t.classList.toggle('active', t.getAttribute('data-view') === name);
        });`;

const showViewReplacement = `document.querySelectorAll('.tab, .nav-tab').forEach(function (t) {
            t.classList.toggle('active', t.getAttribute('data-view') === name);
        });

        if (name === 'wallets') {
            renderAllWalletsGrid();
        }`;

content = content.replace(showViewTarget, showViewReplacement);

// Update heroActiveChip inside renderHeroBalance
const heroBalTarget = `if (accounts.length && acctNum) {
            acctNum.textContent = 'Account •••• ' + accounts[0].accountNumber.slice(-4);
        }`;

const heroBalReplacement = `if (accounts.length && acctNum) {
            acctNum.textContent = 'Account •••• ' + accounts[0].accountNumber.slice(-4);
        }
        var chip = $('heroActiveChip');
        if (chip) {
            var flag = FLAGS[selectedCurrency] || '🌐';
            chip.textContent = flag + ' ' + selectedCurrency + ' Active';
        }`;

content = content.replace(heroBalTarget, heroBalReplacement);

fs.writeFileSync(path, content, 'utf8');
console.log('Successfully updated app.js with desktop wallets features!');
