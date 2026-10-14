#!/usr/bin/env bash
set -euo pipefail

fail_if_found() {
  local pattern="$1"
  local message="$2"
  if grep -R -nE "$pattern" app/src/main/java app/src/main/aidl app/src/main/AndroidManifest.xml; then
    echo "SAFETY CHECK FAILED: $message" >&2
    exit 1
  fi
}

fail_if_found '"/system/bin/sh"|ProcessBuilder\([^)]*"sh"|Runtime\.getRuntime\(\)\.exec' 'arbitrary shell execution is forbidden'
fail_if_found '"setprop"|"settings[[:space:]]+(put|delete)"|"pm[[:space:]]+(grant|revoke)"|"mount"|"umount"|"reboot"|"setenforce"' 'mutating shell operations are forbidden'
fail_if_found 'android\.permission\.INTERNET' 'INTERNET permission is forbidden'
fail_if_found 'android\.permission\.(WRITE_EXTERNAL_STORAGE|READ_EXTERNAL_STORAGE|MANAGE_EXTERNAL_STORAGE|REQUEST_INSTALL_PACKAGES|WRITE_SECURE_SETTINGS|PACKAGE_USAGE_STATS)' 'dangerous/unneeded permission is forbidden'
fail_if_found 'BIND_ACCESSIBILITY_SERVICE|VpnService' 'accessibility/VPN components are forbidden'

if ! grep -q 'String exec(in String\[\] argv)' app/src/main/aidl/com/pcmlink/doctor/IRemoteShell.aidl; then
  echo 'SAFETY CHECK FAILED: IRemoteShell must remain argv-only' >&2
  exit 1
fi

if ! grep -q 'ShellCommandPolicy.requireAllowed(argv)' app/src/main/java/com/pcmlink/doctor/ShellUserService.java; then
  echo 'SAFETY CHECK FAILED: ShellUserService must enforce ShellCommandPolicy' >&2
  exit 1
fi

if ! grep -q 'Shizuku.getUid()' app/src/main/java/com/pcmlink/doctor/DiagnosticService.java; then
  echo 'SAFETY CHECK FAILED: pre-bind Shizuku UID verification is missing' >&2
  exit 1
fi

echo 'Static safety checks passed.'
