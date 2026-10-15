#!/bin/bash
# One definition of "this looks like a real secret", shared by protect-secrets.sh and guard-bash.sh.
# Placeholders such as <your-flutterwave-test-key> do not match, because a value must start with a letter or digit.
SECRET_RE='sk_(live|test)_[A-Za-z0-9]{20,}|JWT_SECRET=[A-Za-z0-9+/]{16,}|FLW(SECK|PUBK)(_TEST)?-[A-Za-z0-9]{16,}|(FLUTTERWAVE_SECRET_KEY|FLUTTERWAVE_WEBHOOK_SECRET|PAYBRIDGE_SETTLEMENT_ADMIN_KEY)=[A-Za-z0-9][A-Za-z0-9+/_-]{15,}'