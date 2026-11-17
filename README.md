# RosePods

为 HyperOS 设备提供 **弱水时砂 CERAMICS-U（琉璃U）** 耳机的系统级控制。

> System-level controller for roseselsa **CERAMICS-U** earbuds on Xiaomi HyperOS.
>
> 一个 LSPosed 模块（libxposed API 102）。协议由本项目逆向整理，**不依赖 RoseLink**，
> 详见 [docs/PROTOCOL.md](docs/PROTOCOL.md)。

## ⚠️ 使用前必读

- 本项目是**个人非商业**开源项目，**与小米、弱水时砂及其关联公司无任何关系**，
  未获其授权或背书。
- 通过 Xposed 框架注入系统进程，**可能造成系统不稳定**，请自行承担风险。
- 请仅在**你自己拥有**的设备与耳机上使用。
- 本项目以 **GPL-3.0** 发布（因包含移植自 GPL-3.0 项目的代码）。
- 完整的许可、第三方归属与免责条款见 [LICENSE](LICENSE) 与 [NOTICE.md](NOTICE.md)。

## 功能

- **降噪模式**：降噪 / 普通 / 通透 / 风噪 / 自适应 / 深度 / 动态
- **降噪强度**：轻度 / 中度 / 深度
- **通透强度**
- **实时电量**：左耳 / 右耳 / 充电仓（含**每只耳机各自**的充电状态）
- **状态实时同步**：设备主动上报，无需轮询
- **系统级集成**（平板 HyperOS 4 / Android 17 实测通过）：
  - 设置页蓝牙设备显示实时电量
  - 通知栏弹出**小米原生样式**耳机通知（左/右/仓分行 + 断开按钮）
  - 状态栏点亮小米耳机图标 `wireless_headset`
  - **音量面板里控制降噪**：与 [HyperVolumeAnc](https://github.com/zhhhyyyyyy/HyperVolumeAnc)
    对接后，小米原生音量面板出现「降噪 / 通透 / 关闭」控制行，点击即切换
- **快捷开关**：下拉通知栏的「RosePods」磁贴直接切换降噪模式

> 已在 Xiaomi Pad（25091RP04C，HyperOS 4.0 / Android 17，KernelSU + Vector）
> 真机验证：注入 → 建链 → 电量 → 设置页 → 通知 → 状态栏图标 全链路通过。

## 两种工作模式

| 模式 | 建链位置 | 适用场景 |
| --- | --- | --- |
| **系统模式** | LSPosed 插件注入的 `com.android.bluetooth` 进程 | 模块已启用：耳机连上即自动工作，App 只当遥控器 |
| **本地模式** | App 自己的进程 | 模块未启用：App 直接连耳机，功能与旧版一致 |

> ⚠️ **耳机同一时间只接受一个 SPP 客户端**。系统模式就是为了避免 App 与插件抢连接：
> 建链只在蓝牙进程里做一次，App 通过广播收发状态与命令。

## 兼容性

| 项目 | 要求 |
| --- | --- |
| 耳机 | roseselsa CERAMICS-U（设备名 `ROSE Ceramics U`） |
| 手机 | Xiaomi HyperOS 3/4，Android 15+（本机实测 Android 17 / HyperOS 4.0.3） |
| 框架 | LSPosed / Vector Framework（经典 Xposed API） |
| 蓝牙 | 经典蓝牙 SPP / RFCOMM |

## 使用

1. 安装 APK（`tools/deploy.sh` 会自动编译、安装、启用模块、设置作用域）
2. 在 LSPosed / Vector 中启用模块，作用域勾选 **蓝牙**（`com.android.bluetooth`）
3. 重启蓝牙进程（或重启手机）让模块注入生效
4. 系统蓝牙里连接耳机 —— 插件自动连上 SPP 并同步电量/模式

验证：

```bash
adb logcat -s RosePods:*        # 看到 SystemBridge attached / connecting / 电量 即成功
```

打开 设置 → 蓝牙 → ROSE Ceramics U 可看到电量；下拉快捷开关可添加「RosePods」磁贴。

## 系统集成是怎么做的

插件在 `com.android.bluetooth` 进程内（`AdapterService.onCreate` 之后）接管 SPP：

| 目标 | 做法 |
| --- | --- |
| 系统电量 | `AdapterService.setBatteryLevel(device, level, fromBas)` → 蓝牙栈广播 `ACTION_BATTERY_LEVEL_CHANGED` |
| 分体电量 | `BluetoothDevice.setMetadata()` 写 `METADATA_UNTETHERED_*_BATTERY`（左/右/仓） |
| 低电量提示 | `AdapterService.notifyOrCancelBluetoothBattery()`（HyperOS 蓝牙免提电量） |
| App 遥控 | 显式包名广播：`moe.chenxy.rosepods.action.CMD` / `.STATE` / `.STATE_REQ` |
| 快捷开关 | `QuickTileService`，点击循环「深度降噪 → 通透 → 普通」 |

> 这些是隐藏 API，全部通过反射调用并包了 try/catch：即使某个方法在当前 HyperOS 版本不存在，
> 也只是少一个功能，不会影响蓝牙正常工作。

## 编译

需要 **JDK 21** + **Android SDK compileSdk 36** + **AGP 9.3.1**：

```powershell
cd RosePods
$env:ANDROID_HOME="C:\Android\sdk"
$env:ANDROID_SDK_ROOT="C:\Android\sdk"
gradle assembleDebug
```

> 工程未附带 gradle wrapper，请使用本机已安装的 Gradle（验证版本：9.6.1）。
> 注意 AGP 9 起 Kotlin 插件已内置，不能再单独 apply，否则编译直接失败。
> 详见 [HANDOFF.md](HANDOFF.md)。

一键部署（编译 + 安装 + 启用 + 重启蓝牙 + 打日志）：

```bash
tools/deploy.sh
```

## 与 HyperVolumeAnc 对接（音量面板控制）

HyperVolumeAnc 把「降噪 / 通透 / 关闭」注入 HyperOS 原生音量面板。RosePods **不需要修改它**，
只要按它的广播契约说话（原设计对接 OppoPods）：

| 方向 | Action | Extra |
| --- | --- | --- |
| 面板 → 我们 | `chen.action.oppopods.anc_select` | `status`(1关/2降噪/3通透) + `address` |
| 我们 → 面板 | `io.github.zhhhyyyyyy.hypervolumeanc.action.OPPO_STATE` | `status` + `address` |

我们每 30 秒心跳一次（它的状态记录 120 秒过期）。
源码与说明见 [HANDOFF.md](HANDOFF.md) 第十四节，上游源码放在 `third_party/HyperVolumeAnc/`。

## 测试

协议层是纯 Kotlin（`core/PodsCodec.kt`），不依赖手机即可测试：

```bash
gradle testDebugUnitTest     # 10 个用例，含文档里的真实抓包帧
```

App 界面与系统模式遥控链路可以在模拟器上验证（hook 需要真机 + LSPosed/Vector）：

```bash
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell am start -n moe.chenxy.rosepods/.MainActivity
# 模拟系统侧上报状态，App 应切到「系统模式」
adb -s emulator-5554 shell am broadcast -a moe.chenxy.rosepods.action.STATE \
  -p moe.chenxy.rosepods --ez system true --ez connected true \
  --ei left 88 --ei right 76 --ei case 45 --ei mode 6
```

## 项目结构

```
app/src/main/java/moe/chenxy/rosepods/
├── MainActivity.kt            控制界面（系统模式遥控 / 本地模式直连）
├── QuickTileService.kt        快捷设置磁贴
├── core/
│   ├── PodsContract.kt        App ↔ 蓝牙进程的广播契约
│   └── PodsState.kt           状态模型
├── hook/                      Xposed 层（经典 API）
│   ├── HookEntry.kt           模块入口
│   ├── BluetoothSystemHook.kt 挂 AdapterService.onCreate
│   ├── SystemBridge.kt        系统侧控制器：建链、遥控、状态广播
│   └── BatteryReporter.kt     电量写回系统蓝牙栈 + 元数据
└── pods/
    └── CeramicsUPods.kt       ★ 协议实现（连接 / 组帧 / 校验 / 解析）
docs/
├── PROTOCOL.md                协议规格（帧格式、命令表、校验）
└── FINDINGS.md                逆向过程与证据
tools/deploy.sh                一键部署脚本
HANDOFF.md                     交接文档（先读这个）
```

## 协议速查

```
帧格式： FF SEQ LEN BODY... CS AA
校验：   CS = (FF + SEQ + LEN + BODY 各字节) & 0xFF

写命令（不带前缀）：  09 01 降噪  09 02 普通  09 03 通透  09 04 风噪
                     09 05 自适应  09 06 深度  09 07 动态
读命令（带 FA 前缀）：FA 09 读模式   FA 0C 读电量
```

**最容易踩的坑**：写命令若误加 `02` 前缀（写成 `02 09 01`），设备会返回
ACK 状态 `02`（拒绝），不报错也不生效。

## 致谢

本项目能完成，离不开以下项目与作者：

- **[HyperPods](https://github.com/Art-Chen/HyperPods)** by [Art_Chen](https://github.com/Art-Chen)
  —— 本项目的**目录结构、Xposed 集成思路与工程组织**均参考自此项目；
  其使用的 YukiHookAPI + Compose + Miuix 技术栈也影响了本项目的设计方向（见 HANDOFF.md 说明）。

- **[roseselsa-RoseLink-Protocol](https://github.com/IRDecoyFlare/roseselsa-RoseLink-Protocol)**
  by [IRDecoyFlare](https://github.com/IRDecoyFlare)
  —— 提供了 RS Common V2 协议族的基础文档（帧格式、命令体、电量解析），
  是本次逆向的关键起点。本项目在其基础上补充了手机端的实测验证与写命令前缀问题的修正。

- **[OppoPods](https://github.com/1812z/OppoPods)** by [1812z](https://github.com/1812z)
  —— 第三方耳机接入 HyperOS 的完整实现参考，本项目在工程组织上受益于它。

- **[YukiHookAPI](https://github.com/HighCapable/YukiHookAPI)** by HighCapable
  —— 现代化 Xposed 开发框架。

- **[Miuix](https://github.com/YuKongA/miuix)** by YuKongA
  —— HyperOS 风格 Compose 组件库。

- **[LSPosed](https://github.com/LSPosed/LSPosed)** —— 模块运行框架。

以及 [LibXposed](https://github.com/LSPosed/LSPosed) 社区在模块 API 上的持续工作。

## 免责声明

本项目为个人逆向学习成果，与 roseselsa 官方无关。使用风险自负。

## 许可

GPL-3.0