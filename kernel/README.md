# Kernel modules

The APK packages the prebuilt `.ko` files in this directory. The corresponding source files are distributed under [GPL-2.0-only](COPYING). Device support depends on the firmware and kernel ABI.

To rebuild, use the prepared ARM64 kernel build tree for the target device, including its symbol versions. Point `SCMI_PROTOCOL_DIR` at the matching kernel source directory containing `drivers/firmware/arm_scmi/protocols.h`.

```sh
make -C "$KDIR" M="$PWD/kernel" ARCH=arm64 LLVM=1 \
  SCMI_PROTOCOL_DIR="$KERNEL_SOURCE/drivers/firmware/arm_scmi" modules
```

For SM8750, package the SCMI build as `qcom-scmi-ro-sm8750.ko`. When
building for SM8850, add `SUN_TUNER_PLATFORM=sm8850` and use the matching
ARM64 GKI build tree. Package the SCMI and QoS builds as follows.

| SM8850 kernel | SCMI asset | QoS asset |
| --- | --- | --- |
| Android 16 GKI generation 6, 4 KB (build 15865446) | `qcom-scmi-ro-sm8850.ko` | `sm8850_stress_qos.ko` |
| Android 16 GKI generation 5, 4 KB | `qcom-scmi-ro-sm8850-gki5-4k.ko` | `sm8850_stress_qos-gki5-4k.ko` |

The generation 5 modules use [Android common kernel b2a876903b49](https://android.googlesource.com/kernel/common/+/b2a876903b495c444a94b16f50d1463ffe953957)
with the ARM64 GKI configuration and Android Clang r536225. The symbol
versions come from the exported-symbol CRCs in that revision's
`gki/aarch64/abi.stg`. Both modules carry the generation 5
`module_layout` CRC `0xe976b219`.

The controller recognizes the legacy internal module and driver names
in the existing prebuilts. APNS and PVC still use SM8750 firmware addresses.
