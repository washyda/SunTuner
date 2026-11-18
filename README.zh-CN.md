# SunTuner

[English](README.md)

SunTuner 是一款 Android Root 调校工具，支持 CPU 降压、锁频和烤机。首个实机验证的机型是 **一加 13（PJZ110，平台代号 `sun`）**。

## 降压怎么实现

我们最初在 Android 里重跑 ACD 校准，发现部分频点的实测主频掉到约一半。追到固件才发现，这段校准是为开机早期设计的，运行时重跑可能写入偏大的延迟码，导致时钟延展。

SunTuner 改走原厂启动校准流程。从原厂状态首次开启时，App 先备份 CDT，再写入 **Platform Key 7 = 1** 并回读校验。重启后，固件执行 SLT 和 ACD，自行算出每个频点的电压。需要撤销时，在 App 内还原原厂 CDT，再重启即可。

## 实测结果

一加 13 两簇共 **32 个频点**，从 SCMI 数据推算出的计划电压都低了 **8–12 mV**。以 CPU0–5 的 **1,996.8 MHz** 为例，PMIC 数字回读从 **624 mV 降到 612 mV**，实测主频仍接近 **1,996.8 MHz**。部分低频档的 PMIC 回读保持原值。PMIC 和主频只在部分频点做了实测。

烤机可以选核，查看 PMU 实测主频与 PMIC 回读。锁频会同时请求目标上下限，并显示系统实际生效的范围。

## 构建

需要 Android SDK 36 和 NDK 27.3.13750724。

```sh
./gradlew :app:assembleRelease :app:lintRelease
```

Release APK 需签名后安装。仓库包含[内核模块和构建说明](kernel/README.md)。

SunTuner 自有用户态代码采用 [Apache-2.0](LICENSE)，内核模块采用 [GPL-2.0-only](kernel/COPYING)，OpenBLAS 保留 [BSD-3-Clause 声明](app/src/main/assets/licenses/OpenBLAS.txt)。
