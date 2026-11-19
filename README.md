# RosePods

为 HyperOS 设备提供 **弱水时砂 CERAMICS-U（琉璃U）** 耳机的系统级控制。

> System-level controller for roseselsa **CERAMICS-U** earbuds on Xiaomi HyperOS.
> 一个 LSPosed 模块（libxposed API 102）。协议由本项目独立逆向整理，**不依赖 RoseLink**。

<p align="left">
  <a href="https://github.com/shiwushuifeng/RosePods/releases/latest"><img alt="Release" src="https://img.shields.io/github/v/release/shiwushuifeng/RosePods?style=flat-square"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-GPL--3.0-blue?style=flat-square"></a>
  <img alt="Platform" src="https://img.shields.io/badge/Android-12%2B%20(HyperOS)-green?style=flat-square">
</p>

## ⚠️ 使用前必读

- 本项目是**个人非商业**开源项目，**与小米、弱水时砂及其关联公司无任何关系**，未获其授权或背书。
- 通过 Xposed 框架注入系统进程，**可能造成系统不稳定**，请自行承担风险。
- 请仅在**你自己拥有**的设备与耳机上使用。
- 本项目以 **GPL-3.0** 发布（因包含移植自 GPL-3.0 项目的代码）。
- 完整的许可、第三方归属与免责条款见 [LICENSE](LICENSE) 与 [NOTICE.md](NOTICE.md)。

## 功能

- **降噪模式**：降噪 / 普通 / 通透 / 风噪 / 自适应 / 深度 / 动态（7 种）
- **降噪强度**：轻度 / 均衡 / 深度
- **实时电量**：左耳 / 右耳 / 充电仓（含**每只耳机各自**的充电状态）
- **状态实时同步**：设备主动上报，无需轮询
- **系统级集成**（HyperOS 4 / Android 17 实测通过）：
  - **音量面板**：小米原生磨砂玻璃按钮，点击循环 降噪 → 通透 → 关闭
  - **系统设置**：点耳机名称弹出悬浮控制小窗（7 模式 + 3 强度 + 三格电量）
  - **通知栏**：小米原生样式耳机通知（左/右/仓分行 + 断开按钮）
  - **状态栏**：点亮小米耳机图标 `wireless_headset`
  - **快捷磁贴**：下拉通知栏一键切换降噪
- **框架状态检测**：主页显示框架 API 版本与各进程加载情况

## 安装

1. 从 [Releases](https://github.com/shiwushuifeng/RosePods/releases/latest) 下载最新 APK
2. 安装（**如果装过旧包名的版本，请先卸载**）
3. 在 LSPosed / Vector 中**启用模块** —— 作用域已静态声明，无需手动勾选
4. 重启设备（或软重启 `su -c 'stop; start'`）

静态作用域包含 4 个进程：

```
com.android.bluetooth     蓝牙栈，建立 SPP 连接
com.xiaomi.bluetooth      小米蓝牙扩展，向系统提供数据
com.android.settings      设置，弹出控制小窗
com.android.systemui      系统界面，音量面板控制行
```

### 环境要求

| 项目 | 要求 |
| --- | --- |
| 系统 | Android 12+ 的 HyperOS（小米/红米/POCO） |
| 框架 | LSPosed（libxposed API 102+）或 Vector |
| 耳机 | ROSE Ceramics U（弱水时砂 琉璃U） |

## 两种工作模式

| 模式 | 触发条件 | 行为 |
| --- | --- | --- |
| **系统模式** | LSPosed 插件已在蓝牙进程建链 | App 只发广播，实际连接由系统侧负责；通知/状态栏/设置页由系统侧维护 |
| **本地模式** | 插件未启用 | App 自己连 SPP，功能受限（无系统集成） |

系统模式下，跨进程通过显式包名广播通信：

```
io.github.shiwushuifeng.rosepods.action.CMD        下发命令
io.github.shiwushuifeng.rosepods.action.STATE      状态上报
io.github.shiwushuifeng.rosepods.action.STATE_REQ  请求刷新
```

## 系统集成是怎么做的

<details>
<summary>展开：技术实现要点（含踩坑）</summary>

### 1. 让系统把耳机当小米 TWS

在 `com.xiaomi.bluetooth` 进程 hook `BluetoothHeadsetService$HeadsetBinder`：

```
checkSupport / getDeviceInfo → 返回能力串 "01010607,000000000000000010000000"
isMiTWS / checkIsMiTWS       → true      ← 关键：系统据此判定"这是小米 TWS"
getRingFindState             → false
setCommonCommand             → 接管系统下发命令
register(callback)           → 记住 IMiuiHeadsetCallback，用 refreshStatus 推状态
```

拿到 callback 后用 16 字段逗号分隔串推状态：`[0]左耳 [1]右耳 [2]仓 [7]ANC码`。

### 2. 音量面板控制行

完整移植 [HyperVolumeAnc](https://github.com/zhhhyyyyyy/HyperVolumeAnc)：

- `MiuiRingerModeLayout` 在**插件 APK**（`miui.systemui.plugin`）里，主 ClassLoader 找不到
- 正解：读 `PluginInstanceInjector.sClassLoaders` 静态 Map 直接拿插件 ClassLoader
- 注入时 **必须同时挂 8 个几何/动画 hook**，否则会把音量条、状态栏、媒体条挤掉

### 3. Vector 在 systemui 进程报的 packageName 是错的

实测会报成 `com.tencent.mm`、`com.xiaomi.misettings` 等。**必须用进程名判断**：

```kotlin
Class.forName("android.app.ActivityThread")
    .getMethod("currentProcessName").invoke(null) as? String
```

而且**只能调一次**，第二次可能返回空导致静默失败。

### 4. 小米耳机页是固定模板，只能绕过

`MiuiHeadsetActivity` 只有通透/降噪/关闭三个按钮（没有风噪），还带虚拟环绕声、佩戴检测等
我们耳机没有的功能。所以在 `onCreate` 起点拦截，直接打开我们自己的控制页。

</details>

## 编译

```bash
# 需要 Android SDK（compileSdk 36）、JDK 21
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk-21

./gradlew assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`

> `app/libs/libxposed-api.jar` 已随仓库提供（从 `io.github.libxposed:api:102.0.0` 提取）。

## 项目结构

```
app/src/main/java/moe/chenxy/rosepods/
├── MainActivity.kt              主页（设备卡片 + 框架状态）
├── DeviceControlActivity.kt     悬浮控制小窗
├── SettingsActivity.kt          设置页
├── AboutActivity.kt             关于页
├── QuickTileService.kt          快捷磁贴
├── core/
│   ├── PodsCodec.kt             帧编解码（LTV 解析、校验和）
│   ├── PodsContract.kt          动作/常量
│   ├── PodsState.kt             状态模型
│   └── AppSettings.kt           设置存储
├── pods/CeramicsUPods.kt        SPP 协议客户端
├── hook/
│   ├── HookEntry.kt             模块入口（按进程名分发）
│   ├── SystemBridge.kt          系统侧控制器（蓝牙进程）
│   ├── BatteryReporter.kt       电量注入系统
│   ├── MiuiHeadsetUi.kt         原生通知与状态栏图标
│   ├── MiuiHeadsetBridge.kt     伪装小米 TWS（xiaomi.bluetooth 进程）
│   ├── MiuiHeadsetFragmentHook.kt  设置页数据注入
│   ├── SettingsRedirectHook.kt  拦截小米页 → 打开我们的控制页
│   └── SettingsBridge.kt        设置同步与进程上报
└── hva/                         移植自 HyperVolumeAnc（Apache-2.0）
    ├── HvaVolumeEntry.java      音量面板入口
    ├── VolumeButtonInjector.java 原生布局注入
    └── ...
```

## 协议速查

完整字段说明见 [docs/PROTOCOL.md](docs/PROTOCOL.md)（只描述帧结构与字段语义，不含原始抓包）。

```
FF SEQ LEN BODY... CS AA      手机 → 耳机
DD SEQ ...                    耳机 → 手机（LTV 结构）
CS = (FF + SEQ + LEN + BODY 全部字节) & 0xFF
```

| 功能 | BODY |
| --- | --- |
| 读全部信息 | `FA 01 07 08 09 0C 0D 0E 12 2A 2B ...` |
| 读降噪模式 | `FA 09` |
| 写降噪模式 | `09 <mode>`（1降噪 2普通 3通透 4风噪 5自适应 6深度 7动态） |
| 写降噪强度 | `2C <level>`（1轻 3中 5深） |
| 电量上报 | 设备推送 `0C <左> <右> <仓>`，bit7 = 充电中，`0xFF` = 未上报 |

> 注意：读全部的应答里 `LEN` 字段不可信（是条目数量而非字节数），必须以 `CS..AA` 为 body 边界解析。

## 致谢

- [OppoPods](https://github.com/1812z/OppoPods)（1812z / Leaf-lsgtky，**GPL-3.0**）——
  小米耳机原生设置页、蓝牙 `HeadsetBinder` 接管思路与部分实现
- [HyperVolumeAnc](https://github.com/zhhhyyyyyy/HyperVolumeAnc)（zhhhyyyyyy，**Apache-2.0**）——
  音量面板原生布局注入（`hva/` 目录）
- [LSPosed](https://github.com/LSPosed/LSPosed) / [libxposed](https://github.com/libxposed/api) —— 模块框架与 API

## 免责声明

本项目仅供**学习与个人使用**。通过 Xposed 框架修改系统行为存在风险，作者不对设备损坏、
数据丢失或任何其他损害承担责任。与小米、弱水时砂均无隶属关系，详见 [NOTICE.md](NOTICE.md)。

## 许可

**GPL-3.0**。因包含移植自 OppoPods（GPL-3.0）的代码，本项目整体必须同许可开源。
分发衍生作品时须同样提供完整源码。

完整条款见 [LICENSE](LICENSE)。