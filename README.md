<!-- Modified by Tadolio contributors (2026) -->
# Tadolio

**免费就能用。** Tadolio 是装在 Android 手机上的 agent，通过社区算力工作，不用自己配模型账号，也可以接自己的模型。
它在后台虚拟屏上替你操作手机，你照常用手机。告诉它要做什么，操作过程和需要你确认的事会显示在对话里。

[English](README.en.md)

## 能做什么

- 用自然语言安排手机任务，操作 App 和网页。
- 用 Tadolio 号让其他人的 agent 联系你，重要的事再交给你决定。
- 在 Tadolio 网络里用金币付款、打赏和担保交易。金币不承诺价格或收益，免费社区算力的使用本身不产生金币。
- 付款约定写清交付内容和条件，带约定的付款会向卖家发出提案，由卖家同意或拒绝。

## 安全与隐私

付钱、对外发消息等不可撤回操作，每次都先问你。免费算力来自社区成员，他们能看到任务内容和屏幕。包括你让它打开的聊天、银行等 App 的画面。介意的话，请用自己的模型。
社区算力通过加密通道传输，矿工能看到模型输入，使用云端矿工时还会经过其模型服务平台。
请按系统引导授权无障碍、通知和后台运行权限。

## 下载

[下载 Android APK](https://tadolio.com/download/tadolio.apk)

安装时允许来自此来源的应用。部分手机重装后需要手动重新打开无障碍。
[源码仓库](https://github.com/tadolio/tadolio) · [发布记录](https://github.com/tadolio/tadolio/releases)

## 从源码构建

准备 JDK 17 和 Android SDK（compileSdk 36），配置 `ANDROID_HOME` 或本地 SDK 路径，在 `mika` 目录执行。

```sh
./gradlew :app:assembleDebug
./gradlew :app:assembleDebug -PhubUrl=https://hub.tadolio.com -PpointsUrl=https://coins.tadolio.com
./gradlew :app:assembleDebug -PrepoUrl=https://github.com/tadolio/tadolio
```

Windows 使用 `gradlew.bat`。调试包默认服务地址为空，连接社区服务时传入上面的公网参数。
`repoUrl` 留空会隐藏仓库、反馈和更新入口，发布时传入实际 GitHub 仓库地址。
正式包默认连接公网服务。复制 `keystore.properties.example` 为 `keystore.properties`，由开发者填写并保管自己的签名配置和密钥。

```sh
./gradlew :app:assembleRelease -PrepoUrl=https://github.com/tadolio/tadolio
```

App 及其 Tadolio 号、金币、社区算力客户端一起开源。后端服务独立维护。
默认 APK 关闭 Linux 沙箱。可选沙箱构建与对应源码交付见 `tools/sandbox/README.md`。

## 致谢与许可证

Tadolio 是 Mika 的修改版，沿用 Apache-2.0。感谢 wangzan101 与 Mika 贡献者。
修改包括社区算力、Tadolio 号、金币与付款约定、引导和发行配置。版权与第三方材料见 `NOTICE`、`LICENSE` 和 `licenses/THIRD_PARTY_NOTICES.md`。

上游项目 https://github.com/wangzan101/Mika

[官网](https://tadolio.com) · [X @tadolio_](https://x.com/tadolio_)
