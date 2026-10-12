#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
# SPDX-License-Identifier: GPL-3.0-or-later

set -euo pipefail

ABI="$(uname -m)"
if [ "$ABI" = "aarch64" ]; then
    ABI_VERSION="arm64-v8a"
else
    ABI_VERSION="x86_64"
fi

echo "no" | avdmanager create avd \
    -n test-device \
    -k "system-images;android-34;google_apis;${ABI_VERSION}" \
    --force
