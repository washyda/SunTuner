# SunTuner

[简体中文](README.zh-CN.md)

SunTuner is an Android root app for CPU undervolting, frequency locking, and stress testing. The first verified device is the **OnePlus 13 (PJZ110, `sun`)**.

## How it works

We found that rerunning the firmware's ACD calibration from Android could cut the measured CPU clock roughly in half at some frequency points. The firmware trace showed that the routine was designed to run early in boot. Running it again later could apply a delay setting that stretched the clock.

SunTuner uses the original boot-time calibration. When you first enable undervolting from stock, it backs up the CDT partition, writes **Platform Key 7 = 1**, and verifies the write. On reboot, the firmware runs SLT and ACD and calculates the voltage for each frequency. To undo the change, restore the original CDT in the app and reboot.

## What we measured

On the OnePlus 13, the voltage plan inferred from SCMI dropped **8–12 mV across all 32 frequency points**. At **1,996.8 MHz** on CPU0–5, PMIC digital readback dropped from **624 to 612 mV**, while the measured clock stayed near **1,996.8 MHz**. Some low-frequency PMIC readings stayed the same. PMIC and clock measurements cover selected points.

The app can pin stress workers to selected cores and show PMU clocks and PMIC readings. During a fixed-frequency test it requests both frequency limits and reports the range the system actually applies.

## Build

Use Android SDK 36 and NDK 27.3.13750724.

```sh
./gradlew :app:assembleRelease :app:lintRelease
```

Sign the Release APK before installing. The repository includes the [kernel modules and build instructions](kernel/README.md).

SunTuner userspace code is [Apache-2.0](LICENSE). The kernel modules are [GPL-2.0-only](kernel/COPYING), and OpenBLAS retains its [BSD-3-Clause notices](app/src/main/assets/licenses/OpenBLAS.txt).
