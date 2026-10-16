# M1: alarm feasibility spike (go/no-go gate)

**Owner:** Android lead. QA runs the device tests. The product owner signs off the result.

**Status:** Spike built. **Result: PENDING.** No physical devices have been tested yet.

**Code:** `android/spike-alarm/`. This is throwaway code (plan §7), not part of the app. Delete it once the result is recorded.

## Pass conditions (copied from plan §7; do not edit)

The result is **Go** only if all four hold, on one Android 14+ device **and** one older device:

1. The alarm rings within 5 s of the set time in each case: app in the background, process killed with `am kill`, Doze forced on with `dumpsys deviceidle force-idle`, and after a reboot.
2. The sound plays on the alarm stream while media volume is at 0.
3. The alarm screen appears over the lock screen, or a documented heads-up-notification fallback works.
4. The permissions it needs can be obtained through the D1 distribution path (D1 = sideload), per current official policy (V2, V3 in `verification.md`).

A user force-stopping the app cancels its alarms by Android design. That case is out of scope.

## What the spike does

- **Scheduling:** `AlarmManager.setAlarmClock()` with `SCHEDULE_EXACT_ALARM` and the system grant flow.
  - A `SecurityException` is caught and logged, not crashed on. This settles the open V2 question of whether `setAlarmClock` needs the permission.
- **Re-registration:** the pending alarm is re-registered on `LOCKED_BOOT_COMPLETED`, `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, and when the exact-alarm grant changes.
  - Components are direct-boot aware, and state is kept in device-protected storage, so the reboot case works before the first unlock.
- **Firing:** a foreground service of type `systemExempted` (V3).
  - It plays the **system default alarm sound** on `USAGE_ALARM` (`alarm.wav` is not used, because its provenance is still open under G2).
  - It posts a full-screen-intent notification that opens `RingActivity` with show-when-locked and turn-screen-on.
- **Evidence:** every step is logged to logcat (tag `PicFrameSpike`) and to the in-app log, with timestamps.
  - `FIRED … delta_ms` is how late the receiver ran.
  - `SOUND PLAYING … delta_ms` is how late the sound started. **Condition 1 uses this sound-start delta.**
  - `RING SCREEN SHOWN overLockScreen=` records condition 3.
  - Each line includes `canExact`, `canFullScreen`, `notifications`, `locked`, `alarmVol` and `mediaVol` (conditions 2 and 4).

## Procedure

Use a fresh install on each device, with a screen lock (PIN) set.

```sh
cd android
./gradlew :spike-alarm:assembleDebug
adb install -r spike-alarm/build/outputs/apk/debug/spike-alarm-debug.apk
adb logcat -c
```

1. **Permissions (condition 4).**
   1. Open the app and tap **Log current state** *before* granting anything. This records the default grant state of a sideloaded install.
   2. Then tap **Allow notifications**, **Allow exact alarms** and **Allow full-screen alarms** (where shown), and grant each.
   3. Tap **Log current state** again.
2. **Volumes (condition 2).**
   1. Set the alarm volume to audible.
   2. Set media to 0: `adb shell cmd media_session volume --stream 3 --set 0`. Confirm `mediaVol=0/…` in the state line.
3. **Run each case** and record the result before starting the next:

| Case | Steps |
|---|---|
| A. Background | Tap **Ring in 3 min**, press Home, and lock the screen with the power button. |
| B. Process killed | Tap **Ring in 3 min**, press Home, then run `adb shell am kill io.github.rencevibe.picframe.spike`. Confirm `adb shell pidof io.github.rencevibe.picframe.spike` prints nothing. Lock the screen. |
| C. Doze | Tap **Ring in 10 min**, press Home, and turn the screen off. Run `adb shell dumpsys battery unplug`, then `adb shell dumpsys deviceidle force-idle`. Confirm `adb shell dumpsys deviceidle get deep` shows `IDLE`. Afterwards run `adb shell dumpsys deviceidle unforce` and `adb shell dumpsys battery reset`. |
| D. Reboot | Tap **Ring in 5 min**, then run `adb reboot`. **Do not unlock** after the reboot. |

4. **Collect evidence after each case.**
   - Save `adb logcat -d -s PicFrameSpike > m1-<device>-<case>.txt`.
   - Take a photo of the lock screen while it rings.
   - Note whether the alarm was audible with media volume at 0.

## Results

Fill in one row per device and case. A case passes when the sound delta is ≤ 5000 ms, the alarm is audible with media at 0, the alarm screen appears over the lock screen (or the heads-up fallback is documented), and the permission state shows the needed grants.

| Device (model / Android version) | Case | FIRED delta_ms | SOUND delta_ms | Audible, media 0 | Over lock screen | canExact / canFullScreen | Pass? | Evidence file |
|---|---|---|---|---|---|---|---|---|
| Android 14+: | A | | | | | | | |
| | B | | | | | | | |
| | C | | | | | | | |
| | D | | | | | | | |
| Older: | A | | | | | | | |
| | B | | | | | | | |
| | C | | | | | | | |
| | D | | | | | | | |

**Default grants on a fresh sideloaded install** (step 1.1): Android 14+: ___ ; Older: ___

**Result:** ☐ Go  ☐ No-go. Failed condition(s): ___  · Signed off by product owner: ___  · Date: ___

**If No-go:** the product owner picks fallback A, B or C from plan §7 *before M4 starts*. Work that depends on alarm feasibility (M4 alarm path and the §9 alarm checks) stays stopped until then.
