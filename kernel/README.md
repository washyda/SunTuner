# Kernel modules

The APK packages the prebuilt `.ko` files in this directory. The corresponding source files are distributed under [GPL-2.0-only](COPYING). Device support depends on the firmware and kernel ABI.

To rebuild, use the prepared ARM64 kernel build tree for the target device, including its symbol versions. Point `SCMI_PROTOCOL_DIR` at the matching kernel source directory containing `drivers/firmware/arm_scmi/protocols.h`.

```sh
make -C "$KDIR" M="$PWD/kernel" ARCH=arm64 LLVM=1 \
  SCMI_PROTOCOL_DIR="$KERNEL_SOURCE/drivers/firmware/arm_scmi" modules
```

For PMS110 with Google GKI build 15865446, use the matching ARM64 build tree
and add `SUN_TUNER_PLATFORM=sm8850`. Package the resulting QoS and SCMI PERF
modules under the `sm8850_` prefix. The internal module names stay the same
for cleanup. APNS and PVC still use SM8750 firmware addresses.
