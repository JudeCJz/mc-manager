# Tunes the phone (iQOO Neo 6 / Android 14) so Android does not kill the Minecraft server.
# Usage:  .\tools\optimize_phone.ps1            apply
#         .\tools\optimize_phone.ps1 -Revert    undo
#         .\tools\optimize_phone.ps1 -Status    show current values
# Needs USB/wireless debugging connected (adb devices shows the phone).
param([switch]$Revert, [switch]$Status)

$pkg = "com.mcmanager"
function A([string]$cmd) { Write-Host "> adb shell $cmd"; adb shell $cmd }

if ($Status) {
  A "settings get global settings_enable_monitor_phantom_procs"
  A "device_config get activity_manager max_phantom_processes"
  A "dumpsys deviceidle whitelist | grep $pkg"
  A "cmd appops get $pkg RUN_ANY_IN_BACKGROUND"
  A "am get-standby-bucket $pkg"
  return
}

if ($Revert) {
  A "settings delete global settings_enable_monitor_phantom_procs"
  A "device_config delete activity_manager max_phantom_processes"
  A "device_config set_sync_disabled_for_tests none"
  A "dumpsys deviceidle whitelist -$pkg"
  A "cmd appops set $pkg RUN_ANY_IN_BACKGROUND default"
  Write-Host "Reverted. Reboot the phone to be sure."
  return
}

# 1. Android 12+ "phantom process killer": kills child processes (our Java server) that use
#    lots of CPU in the background, and caps them at 32. Same as Developer options >
#    "Disable child process restrictions" on Android 14.
A "settings put global settings_enable_monitor_phantom_procs false"
A "device_config set_sync_disabled_for_tests persistent"
A "device_config put activity_manager max_phantom_processes 2147483647"

# 2. Battery optimisation (Doze) exemption + allow background running for the app.
A "dumpsys deviceidle whitelist +$pkg"
A "cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow"
A "am set-standby-bucket $pkg active"

Write-Host ""
Write-Host "Done. Also do this by hand on the phone (vivo/iQOO OriginOS, no adb switch for it):"
Write-Host "  Settings > Battery > Background power consumption management > MCManager > Allow"
Write-Host "  Settings > Apps > MCManager > (Autostart) on"
Write-Host "  Lock MCManager in Recents (swipe down on its card) so the cleaner skips it."
Write-Host "Keep the phone on the charger and out of a case while hosting: the SD870 throttles when hot."
