#!/usr/bin/env bash
# SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
# SPDX-License-Identifier: GPL-3.0-or-later

set -euo pipefail

export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-$HOME/.config/.android/avd}"

exec emulator -avd test-device -no-boot-anim -gpu host -qemu -enable-kvm "$@"
