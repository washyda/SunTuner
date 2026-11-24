# Kernel modules

The sources and Kbuild Makefile live in `src/`. The APK packages the
modules listed in Gradle from `prebuilt/`, grouped by SoC and, for SM8850,
by GKI generation and page size. Gradle keeps that directory structure
under `assets/backend/modules/`. Each module uses the same filename
across builds, with the platform and ABI identified by its directory. The modules are
distributed under [GPL-2.0-only](COPYING).

To rebuild, use the prepared ARM64 kernel build tree for the target device, including its symbol versions. Point `SCMI_PROTOCOL_DIR` at the matching kernel source directory containing `drivers/firmware/arm_scmi/protocols.h`.

```sh
make -C "$KDIR" M="$PWD/kernel/src" ARCH=arm64 LLVM=1 \
  SCMI_PROTOCOL_DIR="$KERNEL_SOURCE/drivers/firmware/arm_scmi" modules
```

Place the built modules in the matching prebuilt directory.

| Platform | Directory under `prebuilt/` |
| --- | --- |
| SM8750 | `sm8750/` |
| SM8850 Android 16 GKI generation 5, 4 KB | `sm8850/android16-5-4k/` |
| SM8850 Android 16 GKI generation 6, 4 KB (build 15865446) | `sm8850/android16-6-4k/` |

Package the SCMI and QoS modules as `qcom-scmi-ro.ko` and
`stress-qos.ko` respectively. For SM8850 builds, pass
`SUN_TUNER_PLATFORM=sm8850` to Kbuild. The SM8750 directory also
contains `acd-observer.ko` and `pvc-corr-ro.ko`.

The generation 5 modules use [Android common kernel b2a876903b49](https://android.googlesource.com/kernel/common/+/b2a876903b495c444a94b16f50d1463ffe953957)
with the ARM64 GKI configuration and Android Clang r536225. The symbol
versions come from the exported-symbol CRCs in that revision's
`gki/aarch64/abi.stg`. Both modules carry the generation 5
`module_layout` CRC `0xe976b219`.

The controller recognizes the legacy internal module and driver names
in the existing prebuilts. The ACD observer and PVC probe use SM8750 firmware addresses.

For SM8845 on OPD2515 with the Android 15 GKI build based on
[5d4cdf2ffe45](https://android.googlesource.com/kernel/common/+/5d4cdf2ffe45),
the controller reuses only the 4 KB SCMI reader from the SM8750 directory.
The SCMI protocol headers and all imported symbol CRCs match that kernel.
On OPD2515 firmware 17.0.1.201, PDP computes SCMI power in µW as
`floor(C × frequency_kHz × voltage_mV² / 1,000,000,000)`.
The internal CDB entry `K_DMIPS_HZ` supplies `C = 100` for PDP0 and
`C = 150` for PDP1. The app reverses the formula on the 4 mV grid
and shows estimated voltage only when every row reproduces its original
SCMI power. Otherwise it shows the raw plan frequency and estimated power.
