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

## GitHub 自动发布

在 **Settings → Environments** 创建名为 `release` 的 Environment，建议设置 Required reviewers；再将以下值添加为该 Environment 的 Secrets。不要把密钥提交到仓库、写进 Gradle 配置或放在普通 Actions 变量里：

- `RELEASE_KEYSTORE_BASE64`：PKCS12 keystore 文件的 Base64 内容
- `RELEASE_KEY_ALIAS`：签名别名
- `RELEASE_STORE_PASSWORD`：keystore 密码
- `RELEASE_KEY_PASSWORD`：签名密钥密码

在 **Actions → Android release → Run workflow** 输入版本号（如 `0.4.0beta`）和递增的 Android `versionCode`。工作流会构建并验证 arm64 APK，再创建 `v<版本号>` tag 和 GitHub Release。也可以先推送 `v*` tag 触发发布。缺少任一签名 Secret 时，发布会失败，不会上传未签名 APK。

发布新标签需要在 `release` 环境中添加 `RELEASE_PUBLISH_TOKEN` Secret。使用仅授权 SunTuner 仓库的细粒度令牌，并授予 **Contents** 和 **Workflows** 读写权限。

本次更新见 [CHANGELOG](CHANGELOG.md)。

SunTuner 自有用户态代码采用 [Apache-2.0](LICENSE)，内核模块采用 [GPL-2.0-only](kernel/COPYING)，OpenBLAS 保留 [BSD-3-Clause 声明](app/src/main/assets/licenses/OpenBLAS.txt)。
