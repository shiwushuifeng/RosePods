# 交接文档 · RosePods

> 写于 2026-11-17。给后续接手的你（或未来的我）。
> 目标：弱水时砂 CERAMICS-U（琉璃U）耳机在 HyperOS 上的第三方控制。

---

## 一、现在到哪一步了

**协议已经完全打通，并且实测有效。**

2026-11-17 16:21 实测记录：发送 `09 01` 后耳机切到深度降噪，用户听感确认。

已可控制的能力：

| 能力 | 状态 |
| --- | --- |
| 连接耳机（SPP） | ✅ 实测通过 |
| 降噪 7 种模式 | ✅ 实测通过（深度降噪已听感确认） |
| 降噪强度 1–5 | ✅ 帧已通，听感未逐个确认 |
| 通透强度 | ⬜ 帧格式已知，未测 |
| 电量（左/右/仓） | ✅ 实测通过，与官方 App 一致 |
| 状态主动上报 | ✅ 实测通过 |

2026-11-17 下午（本轮）：**LSPosed 插件已经写出来并编译通过**，把 SPP 建链搬到
`com.android.bluetooth` 进程里，电量回写系统蓝牙栈，App 改成遥控器，另加快捷开关磁贴。
真机验证还没做完——写代码时手机从 USB 上掉了，见第九节。

| 能力 | 状态 |
| --- | --- |
| 系统进程内建链（LSPosed） | ✅ 已实现，编译通过，待真机验证 |
| 电量写回系统蓝牙栈 | ✅ 已实现（`setBatteryLevel` + 元数据），待验证 |
| HyperOS 低电量提示 | ✅ 已实现（`notifyOrCancelBluetoothBattery`），待验证 |
| App 遥控 / 本地回退 | ✅ 已实现 |
| 快捷设置磁贴 | ✅ 已实现 |

**还没做的**：

- 真机验证（装机、启用模块、看设置页电量）
- 融合设备中心 / 超级岛的设备卡片（需要进一步逆向小米的卡片数据源）
- 强度档位的听感验证
- 其他 roseselsa 型号的兼容性测试

---

## 二、协议核心（务必先看这个）

完整规格见 `docs/PROTOCOL.md`，这里只列最容易出错的：

### 帧格式

```
FF SEQ LEN BODY... CS AA     手机 → 耳机
DD SEQ ...                   耳机 → 手机（LTV 结构）
```

- `LEN` = `len(BODY)`
- `CS` = `(FF + SEQ + LEN + BODY 全部字节) & 0xFF`

### 最大的坑：写命令不能带前缀

```
✅ 09 01        降噪
❌ 02 09 01     设备会回 ACK 状态 02（拒绝），不报错、不生效
```

读命令才带 `FA` 前缀（`FA 09` 读模式、`FA 0C` 读电量）。
这个差异坑了很久——设备拒绝时不报错，只回一个状态码，非常容易误判成"帧没发出去"。

### ACK 状态码

| 值 | 含义 |
| --- | --- |
| `00` | 成功 |
| `02` | 拒绝 |

### 连接

```kotlin
UUID.fromString("0CF12D31-FAC3-4553-BD80-D6832E7B3931")
device.createInsecureRfcommSocketToServiceRecord(uuid)
```

---

## 三、环境要求（复现要点）

| 项目 | 说明 |
| --- | --- |
| JDK | **21**（AGP 9.3.1 / Kotlin 内置） |
| Android SDK | compileSdk **36**，build-tools 36.0.0 |
| Gradle | 项目里没有 wrapper，用本地 `gradle-9.6.1` |
| AGP | **9.3.1** —— 见下方说明 |

### 为什么没用 HyperPods 那套（重要）

HyperPods 用 **YukiHookAPI + KSP + Compose + Miuix**，需要的组合是
AGP 8.13 + Kotlin 2.2.20 + Gradle 9.x。

但本机 Gradle 缓存里只有 **AGP 9.3.1**，而 **AGP 9 起 Kotlin 插件已内置**，
再 apply `org.jetbrains.kotlin.android` 会直接报错，KSP 也就挂不上，
YukiHookAPI / Compose 全部无法使用。

所以本工程的现状是：

| 层 | 现状 |
| --- | --- |
| 目录结构 | 保持 HyperPods 风格（hook / pods） |
| Xposed 层 | **经典 API**（`IXposedHookLoadPackage`） |
| 界面 | **原生 View**（无 Compose 依赖） |
| 协议层 | 完整，`CeramicsUPods.kt` 可直接复用 |

**如果你想用回 HyperPods 那套**，需要升工具链：
在 `gradle/libs.versions.toml` 把 AGP 改成 8.13.0、加回 Kotlin 插件与 KSP，
然后联网让 Gradle 下载对应 AGP（本机缓存里没有，要能连 Google Maven）。

本机可用路径（如果还是这台电脑）：

```
C:\Android\jdk-21
C:\Android\sdk
C:\Users\Qi\.gradle\wrapper\dists\gradle-9.6.1-bin\4ticwg1pgcbps2hj28r8so764\gradle-9.6.1\bin\gradle.bat
```

编译命令：

```powershell
cd <项目目录>
$env:ANDROID_HOME="C:\Android\sdk"
$env:ANDROID_SDK_ROOT="C:\Android\sdk"
<上面的gradle.bat> assembleDebug
```

> ⚠️ **AGP 版本要和本机缓存一致**。本机 `.gradle` 缓存里只有 AGP 9.3.1，换版本会触发下载。
> 网络需要能通 Google Maven（挂代理可用）。

---

## 四、真机测试的几个坑

### 1. 耳机只接受一个 SPP 客户端

RoseLink 在跑的时候连接会**一直阻塞**在 `socket.connect()`，表现是"连不上"而非报错。

```bash
su -c 'am force-stop cn.ikaile.ruoshui.client'
```

### 2. 残留会话

异常断开后，耳机侧会保留状态，后续连接全部失败。
**必须把耳机取出充电仓**重新连接才能清掉。只开盖不行。

### 3. 充电仓电量是缓存值

耳机取出后，充电仓字节保持最后一次读数，不会再更新。

### 4. 安装 APK 的限制

这台手机的 DSH 工具会拦截带 `-r` 的命令。绕过方式：

```bash
su -c 'pm install-create'                    # 记下 session id
su -c 'pm install-write <id> base /data/local/tmp/x.apk'
su -c 'pm install-commit <id>'
```

另外 `pm install` 不能直接读 `/sdcard`（FUSE 限制），必须先 `cp` 到 `/data/local/tmp/`。

### 5. Xposed 模块更新后必须重启目标进程

`pm install` 装完，正在跑的进程用的还是旧 dex，必须 `am force-stop` 再启动。

---

## 五、代码结构

```
RosePods/
├── app/src/main/java/moe/chenxy/rosepods/
│   ├── hook/
│   │   ├── HookEntry.kt            模块入口（经典 Xposed API）
│   │   ├── BluetoothSystemHook.kt  挂 AdapterService.onCreate
│   │   ├── SystemBridge.kt         系统侧控制器：建链 / 遥控 / 状态广播
│   │   └── BatteryReporter.kt      电量写回系统蓝牙栈 + 元数据
│   ├── core/
│   │   ├── PodsContract.kt         App ↔ 蓝牙进程广播契约
│   │   └── PodsState.kt            状态模型
│   ├── pods/
│   │   └── CeramicsUPods.kt        ★ 协议核心，最值钱的文件
│   ├── QuickTileService.kt         快捷设置磁贴
│   └── MainActivity.kt             界面（系统模式遥控 / 本地模式直连）
├── docs/
│   ├── PROTOCOL.md                 协议规格
│   └── FINDINGS.md                 逆向过程与证据
├── tools/deploy.sh                 一键部署（编译+安装+启用+重启蓝牙）
└── README.md
```

**`CeramicsUPods.kt` 是核心**，连接、组帧、校验、收发、解析都在里面，
复用时可原样拿走，只改包名。

---

## 六、接手后建议的顺序

1. **先跑通编译**，确认 `assembleDebug` 能出包
2. **跑 `tools/deploy.sh`**：装包 + 在 Vector/LSPosed 里启用 + 勾作用域 + 重启蓝牙
3. **看日志**：`adb logcat -s RosePods:*`，应出现 `SystemBridge attached`
4. 连接耳机，确认日志出现 `connecting` / 电量 / 模式上报
5. 打开 设置 → 蓝牙 → ROSE Ceramics U，验证电量显示
6. 系统级「设备卡片」是下一步（见第九节的风险与方向）

---

## 七、参考项目

| 项目 | 用途 |
| --- | --- |
| [HyperPods](https://github.com/Art-Chen/HyperPods) | 工程框架、YukiHookAPI 用法、Compose UI |
| [roseselsa-RoseLink-Protocol](https://github.com/IRDecoyFlare/roseselsa-RoseLink-Protocol) | 协议基础文档 |
| [OppoPods](https://github.com/1812z/OppoPods) | 第三方耳机接入 HyperOS 的完整实现 |
| [YukiHookAPI](https://github.com/HighCapable/YukiHookAPI) | Xposed 框架 |

逆向过程中的原始材料（抓包、hook 模块源码）不在本目录，
如需追溯见 `docs/FINDINGS.md` 的说明。

---

## 八、一句话总结

**协议已经解决了，剩下的是工程活。**

`CeramicsUPods.kt` 直接可用，别再走弯路去 hook RoseLink 或猜帧格式——
所有坑都写在 `docs/PROTOCOL.md` 和本文档里了。

---

## 九、LSPosed 插件（2026-11-17 本轮实现）

### 为什么把 SPP 放进蓝牙进程

耳机只接受一个 SPP 客户端。之前 App 自己连，App 不开就没数据；插件要显示系统电量，
就必须有一个**常驻**的建链者。`com.android.bluetooth` 正好：常驻、系统权限齐全、
耳机连上就能感知（ACL 广播）。

### 结构

```
AdapterService.onCreate  ──hook──▶  SystemBridge.attach(service)
                                      │
              ┌───────────────────────┼─────────────────────────┐
              ▼                       ▼                         ▼
      ACL_CONNECTED 广播        App 命令广播              SPP 数据回调
      （匹配耳机名称）        （setMode/setLevel/…）     （电量 / 降噪模式）
              │                       │                         │
              └──────────▶ CeramicsUPods ◀───────────────────────┘
                                      │
                              电量 ──▶ BatteryReporter
                                      │
                     ┌────────────────┼─────────────────┐
                     ▼                ▼                 ▼
       setBatteryLevel()      setMetadata()      notifyOrCancel…
       （系统电量/设置页）    （左/右/仓 分体电量）  （HyperOS 低电量提示）
```

### 用到的隐藏 API（全部反射 + try/catch）

| 调用 | 作用 |
| --- | --- |
| `AdapterService.setBatteryLevel(BluetoothDevice,int,boolean)` | 写设备电量，蓝牙栈随后广播 `ACTION_BATTERY_LEVEL_CHANGED` |
| `BluetoothDevice.setMetadata(int,byte[])` | `METADATA_UNTETHERED_*_BATTERY`（10/11/12）、`*_CHARGING`（13/14/15） |
| `AdapterService.notifyOrCancelBluetoothBattery(boolean,int)` | HyperOS 蓝牙免提电量（0–9 档，低电量时下发） |

抓取证据：`Settings.apk` 的 `CachedBluetoothDevice` 走 `BluetoothDevice.getBatteryLevel()`
→ `AdapterServiceBinder.getBatteryLevel`；`AdapterService$Injector` 里能看到小米的
`android.intent.action.BLUETOOTH_HANDSFREE_BATTERY_CHANGED`。

### App ↔ 蓝牙进程广播契约

| 方向 | Action | Extra |
| --- | --- | --- |
| App → 系统 | `io.github.shiwushuifeng.rosepods.action.STATE_REQ` | —（探测系统侧是否在线） |
| App → 系统 | `io.github.shiwushuifeng.rosepods.action.CMD` | `cmd`（ping/setMode/setLevel/cycle/requestInfo/disconnect/setTarget）+ `arg` |
| 系统 → App | `io.github.shiwushuifeng.rosepods.action.STATE` | `system`/`connected`/`left`/`right`/`case`/`charging`/`mode`/`address`/`name` |

App 启动时发 `STATE_REQ`，800ms 内收到 `STATE` 即进入系统模式（遥控），否则回退本地直连。

### 现状与风险

- **已做**：编译通过，APK 结构正常（`xposed_init`、`xposedscope`、磁贴 service 都在）。
- **未做**：真机验证。写到这里时手机从 USB 上掉了（`adb devices` 空），
  装机 / 启用模块 / 看设置页电量都还没跑。
- **风险 1**：`setBatteryLevel` 的第三个参数语义（`fromBas`）。BAS 服务传 `true`，
  我们传 `false`（按经典 A2DP 处理）。若设置页没显示电量，先试着改成 `true`。
- **风险 2**：`setMetadata` 在某些版本可能不可用——只影响左右耳/仓的分体显示，
  总电量走 `setBatteryLevel`，不受影响。
- **风险 3**：蓝牙进程被注入后如果抛异常，可能影响系统蓝牙。所有 hook 入口都包了
  try/catch 与超时（35s 放弃建链、最多重试 10 次）。
- **风险 4**：系统侧广播没做发送方校验，理论上其它 App 也能发命令改降噪模式（无破坏性）。

### 下一步（设备卡片）

- `com.android.systemui.devicenotification.MiuiDeviceNotificationProxy`（SystemUI 里）
- 资源 overlay `com.miui.systemui.overlay.devices.android`
- 蓝牙栈里的 `HyperAdapterService` / `HyperHeadsetService`（小米扩展）
- 小米耳机的设备卡片大概率走**厂商通道 + 白名单**，不是标准 AOSP 电量路径；
  继续做需要抓 HyperOS 与自家耳机的交互，或直接 hook SystemUI 的卡片渲染。

### 真机验证清单

```bash
tools/deploy.sh                     # 编译 + 安装 + 启用 + 重启蓝牙
adb logcat -s RosePods:*            # 看 SystemBridge attached / connecting / 电量
```

1. `SystemBridge attached` 出现 → 注入成功
2. 连耳机后出现 `connecting …` 与 `电量 左=… 右=… 仓=…`
3. 设置 → 蓝牙 → ROSE Ceramics U 显示电量
4. 快捷开关加「RosePods」磁贴，点击切模式
5. 若没反应：`vector-cli modules ls` 确认模块已启用、`vector-cli scope ls` 确认含
   `com.android.bluetooth/0`

---

## 十、迁移到 libxposed 新 API（2026-11-17 傍晚）

### 为什么必须迁移

真机（Vector Framework 2.2 / API 102）实测：**经典 `de.robv.android.xposed` 模块完全不会被调用**。
框架日志里只有 `Loading module` / `Loaded module`，入口类从未实例化，也没有任何报错。

对照设备上正常工作的 HyperCeiler：它用的是 **libxposed 新 API**
（`io.github.libxposed.api.XposedModule` + `META-INF/xposed/*` 声明 + `META-INF/xposed/java_init.list`）。

### 现在的模块结构

```
app/libs/libxposed-api.jar                  从 Maven io.github.libxposed:api:102.0.0 的 aar 里取出
app/src/main/resources/META-INF/xposed/
├── module.prop        minApiVersion=101 / targetApiVersion=101 / staticScope=false
├── java_init.list     io.github.shiwushuifeng.rosepods.hook.HookEntry
└── scope.list         com.android.bluetooth
```

`HookEntry : XposedModule()`，在 `onPackageLoaded` / `onPackageReady` 里
`hook(AdapterService.onCreate).intercept(...)`；不再依赖 `XposedHelpers`，
隐藏 API 改由 `core/Reflect.kt`（纯反射）调用。

### 仍未解决的问题（下次接手先看这里）

真机上框架能加载模块并派发 `dispatchPackageReady`，但：

- 用**真类名**时：没有 `Failed to instantiate` 报错，但入口构造日志（`entry constructed`）**从未出现**
- 用**假类名**做对照时：立刻出现 `Failed to instantiate class ... ClassNotFoundException`

也就是说「假类会报错、真类不报错但也不构造」，怀疑是 libxposed 的入口实例化走的是
另一条路径（例如按 `moduleClassNames` 逐个 `Class.forName` 后再 `newInstance`，
而我们的类在 `init` 阶段抛了异常被吞掉）。下一步建议：

1. 在 `HookEntry` 构造函数里 **故意抛异常**，看框架是否打出 "Failed to instantiate"
   （能区分「没实例化」和「实例化后异常被吞」）
2. 对比一个自己写的**最小 libxposed 模块**（只 log 一行）是否能被实例化，
   排除 `module.prop` / `java_init.list` 的声明问题
3. 注意 Vector 的 `staticScope` / scope 配置：模块即使在作用域内，也可能因为
   `scope.list` 与框架配置不一致而不派发

### 已完成的验证（不依赖真机）

| 验证 | 方式 | 结果 |
| --- | --- | --- |
| 协议编解码 | JVM 单测（用文档里的真实抓包帧） | ✅ 10 passed |
| 帧切分（粘包/半包） | JVM 单测 | ✅ 修掉「AA 在缓冲区末尾漏帧」的 bug |
| 解析结构 | JVM 单测 | ✅ 修掉「把 `dd 10 04 0C …` 当 LTV 解析」的 bug |
| App 启动 | Pixel_8_API_36 模拟器 | ✅ 无崩溃，界面正常 |
| 系统模式遥控链路 | 模拟器发 STATE 广播 | ✅ 电量/模式渲染、按钮让位、日志接管 |
| 快捷开关磁贴 | 模拟器控制中心 | ✅ 磁贴出现并可添加 |

> 模拟器没有 Vector / Zygisk，**hook 本身无法在模拟器上验证**；
> 上面两项 bug 是这轮单测额外抓出来的，属于真实修复。

---

## 十一、HyperOS「像小米耳机一样」的方案（2026-11-17 晚）

### 结论：别去啃 MiLink，抄 HyperPods 的路子

我先把「融合设备中心」挖了一遍：

| 层 | 位置 | 说明 |
| --- | --- | --- |
| 卡片 UI | `miui.systemui.plugin` → `miui/systemui/controlcenter/panel/main/devicecenter/` | `DeviceCenterCardController` / `DeviceItem$DeviceInfoItem` |
| 数据 | `miui/systemui.devicecenter.DeviceCenterController` | `updateDeviceList(List<DeviceInfo>, boolean)` |
| 模型 | `com.miui.circulate.device.api.DeviceInfo`（来自 `com.milink.service`） | Parcelable，含 battery/icon/state 等 |
| 依赖 | SystemUI `uses-library com.miui.system` | 该类在 SystemUI 进程里可见 |

这条路**可行但很重**：要伪造 MiLink 设备、还得让卡片点击走到我们的控制通道。

**HyperPods 的现成答案**（源码：`github.com/Art-Chen/HyperPods`，用 jsdelivr 拉到的）：
它根本不碰 MiLink，而是在 **`com.android.bluetooth` 进程里直接伪造小米耳机的原生通知 + 状态栏图标 + 强提示**。

关键文件与做法：

| HyperPods 文件 | 做什么 |
| --- | --- |
| `hook/HookEntry.kt` | `loadApp("com.android.bluetooth", HeadsetStateDispatcher)` + `loadApp("com.xiaomi.bluetooth", MiBluetoothToastHook)` |
| `hook/HeadsetStateDispatcher.kt` | hook `A2dpService.handleConnectionStateChanged`；连上后开 BLE 扫描取电量，并 `StatusBarManager.setIconVisibility("wireless_headset", true)` |
| `hook/MiBluetoothToastHook.kt` | hook `com.android.bluetooth.ble.app.MiuiBluetoothNotification` 构造器拿到 Context；发原生通知 + 强提示 |
| `utils/SystemApisUtils.kt` | 反射封装 `notifyAsUser` / `cancelAsUser` / `setIconVisibility` / `getMetadata` |
| `utils/miuiStrongToast/*` | 小米「强提示」弹窗（胶囊/大卡片） |

### 通知的确切参数（可直接照抄）

```
channel : "BTHeadset$address"（IMPORTANCE_MIN，名字用设备别名）
tag/id  : "BTHeadset$address" / 10003
title   : 设备别名
text    : 用 com.xiaomi.bluetooth 的资源拼：
          miheadset_notification_Box / _LeftEar / _RightEar
          → "充电仓：45%\n左耳：88% | 右耳：76%"
smallIcon: android.R.drawable.stat_sys_data_bluetooth
extras  : Bundle{ miui.showAction=true,
                  miui.appIcon=Icon.createWithResource(ctx, ic_headset_notification) }
action  : "断开" → 广播 com.android.bluetooth.headset.notification
color   : android:system_notification_accent_color
```

手机/平板侧要用的隐藏 API（都要反射）：
`NotificationManager.notifyAsUser`、`cancelAsUser`、`StatusBarManager.setIconVisibility`。

### 我们怎么落地（RosePods 版）

我们比 HyperPods 更有优势：**电量是我们自己 SPP 读出来的**，不需要 BLE 扫描。

1. `SystemBridge` 已经在蓝牙进程里、已有 L/R/仓电量与模式 → 直接复用
2. 新增 `hook/MiuiHeadsetUi.kt`：
   - `postNotification(device, left, right, case)`：按上面的参数发原生通知
   - `setHeadsetIcon(show)`：`StatusBarManager.setIconVisibility("wireless_headset", show)`
   - `cancel(device)`：断开时清理
3. 在 `onBattery()` 里刷新通知；ACL 断开时取消
4. 可选二期：强提示弹窗（`MiuiStrongToastUtil` 那套，需要把 PNG/MP4 放进来并 grant 给 SystemUI）
5. 三期再考虑融合中心卡片（MiLink 路线，成本高）

### 已验证的平台事实（平板 25091RP04C / HyperOS 4 / Android 17）

```
entry constructed
packageLoaded: com.android.bluetooth (first=true)
hooked AdapterService.onCreate (api 102)
SystemBridge attached (adapter=A4:C3:BE:0C:21:21)
acl connected: 84:9D:4B:3D:7F:53
connecting 84:9D:4B:3D:7F:53 (acl)
spp 已连接 ceramics-u-insecure
TX <读全部请求帧>  （原始帧见 private/captures/handoff-raw-frames.txt，不公开）
RX <设备应答帧>  （原始帧见 private/captures/handoff-raw-frames.txt，不公开）
RX <设备应答帧>  （原始帧见 private/captures/handoff-raw-frames.txt，不公开）
```

—— 插件在平板上**完整跑通**：注入成功、ACL 触发建链、SPP 连上、读全部信息有回应。

**遗留**：读全部应答 `dd 01 15 …`（LEN=0x15=21，实际 body 73 字节）的结构还没解析出来，
电量/模式因此没进 UI。下一步用新增的调试命令逐条探测：

```bash
adb shell am broadcast -a io.github.shiwushuifeng.rosepods.action.CMD -p com.android.bluetooth \
  --es cmd raw --es hex FA0C     # 单独读电量
```

---

## 十二、电量打通了（2026-11-17 18:12 里程碑）

### 实测证据（平板 25091RP04C）

```
RX <设备应答帧>  （原始帧见 private/captures/handoff-raw-frames.txt，不公开）
spp 电量 左=93 右=94 仓=44 充电=false (raw 5d/5e/2c)
battery -> system: 44% (L93 R94 C44) readBack=44
```

`readBack` 是我们注入后立刻用 `BluetoothDevice.getBatteryLevel()` 读回来的值，
说明**确实进了系统蓝牙栈**。

然后往系统里注入测试值 37：

```
test battery inject: 37
battery -> system: 37% (L37 R37 C37) readBack=37
```

打开 设置 → 蓝牙，设备行显示：

> ROSE Ceramics U ｜ 已连接 | **电量为 37%** | 使用中（媒体和通话）

**结论：第三方耳机在 HyperOS 系统设置里显示实时电量，已跑通。**

### 电量语义（重要）

- 每个电量字节：bit7 = 充电中，bit6..0 = 百分比
- **`0xFF` / 0x7F 表示该耳未上报**（关机、脱离、在仓未唤醒）→ 代码里按 `-1`（未知）处理，
  不能当 127%
  - 实测：右耳关机时右耳字节是 `0xFF`，之前被算成 127%，后来按未知处理后数据就对了
- 充电仓电量只在耳机回仓时更新，之后保持不变（不是 bug）
- 注入系统时取三个有效值里的**最小值**作为总电量（与系统聚合习惯一致）

### 调试命令（都在 SYSTEM 侧执行）

```bash
# 直接发一条 BODY（协议探测）
adb shell am broadcast -a io.github.shiwushuifeng.rosepods.action.CMD -p com.android.bluetooth \
  --es cmd raw --es hex FA0C

# 注入指定电量，验证系统 UI 是否跟着变
adb shell am broadcast -a io.github.shiwushuifeng.rosepods.action.CMD -p com.android.bluetooth \
  --es cmd testBattery --ei arg 37
```

### 还差的

- 设置页目前只显示**一个总电量**。要显示「左耳 / 右耳 / 仓」三个数字，
  需要 HyperPods 那套**原生通知**（`miheadset_notification_LeftEar/_RightEar/_Box` 文案）
  或走 LE Audio 多电量通道
- 读全部应答 `dd 01 15 …` 仍未解析（周期上报的单帧已够用，属次要）

---

## 十三、系统 UI 接通了（2026-11-17 18:56 第二个里程碑）

### 现在的完整链路（连上耳机后 1 秒内全部完成）

```
18:56:05  spp 已连接 ceramics-u-insecure
18:56:06  statusbar icon wireless_headset = true          ← 状态栏耳机图标
18:56:06  spp 电量 左=96 右=96 仓=57 充电(L=false R=false C=false)
18:56:06  电池细节 L96(-) R96(-) C57(-)
18:56:06  notification posted: 充电盒：57%               ← 小米原生样式通知
```

通知栏实拍（截图已确认）：

> 🎧 **ROSE Ceramics U**
> 充电盒：57%
> 左: 97%  右: 97%              ［断开连接］

文案与图标都来自 `com.xiaomi.bluetooth`（`miheadset_notification_*` / `ic_headset_notification`），
所以看起来和真正的小米耳机通知一模一样。

### 新增代码

| 文件 | 作用 |
| --- | --- |
| `hook/MiuiHeadsetUi.kt` | 发小米样式通知、点状态栏 `wireless_headset` 图标、断开时清理 |
| `hook/SystemBridge.kt` | 电量回调里刷新通知；连接成功点亮图标；断开/通知按钮清理 |
| `hook/BatteryReporter.kt` | 注入后 `readBack` 校验；低电量提示 |
| `core/PodsCodec.kt` | 帧解析（关键：不依赖 LEN 字段） |

### 解析器的重要修正（踩坑记录）

**接收帧的 `LEN` 字段不可靠**：

| 帧类型 | LEN 的含义 |
| --- | --- |
| 单条上报 `dd 03 04 0C 5D 5E 2C …` | body 长度（4） |
| 读全部应答 `dd 04 15 01 01 04 …` | **条目数（21）**，而 body 实际 73 字节 |

所以 `parseEntries` 现在**直接用 CS 与 AA 之间的全部内容当 body**，
先尝试完整 LTV（`L 类型 值…`，L 含类型字节），失败再按「类型+值」解析。
这样「读全部」应答里一次就能拿到：电量(`0C`)、模式(`09`)、降噪强度(`2C`)、通透强度(`2D`) 等。

已加单测：`parseEntries_readAllResponse_containsBatteryAndMode`（用真实抓包帧）。

### 电量语义实测修正（重要）

| 项 | 结论 |
| --- | --- |
| `0xFF` / `0x7F` | 该耳**未上报**（关机/脱离），按未知处理，不是 127% |
| 右耳充电位 | 右耳字节**也有** bit7 充电标志（`5d/de` = 左93%未充、右94%充电中） |
| 仓电量 | **实时**的，耳机在仓充电时持续下降（82→81→79→78→77→76） |

### 调试命令

```bash
# 注入指定电量，验证系统 UI
adb shell am broadcast -a io.github.shiwushuifeng.rosepods.action.CMD -p com.android.bluetooth \
  --es cmd testBattery --ei arg 37

# 直接发 BODY（协议探测）
adb shell am broadcast -a io.github.shiwushuifeng.rosepods.action.CMD -p com.android.bluetooth \
  --es cmd raw --es hex FA0C
```

### 已知限制

1. 通知里的「断开连接」目前只停插件侧 SPP + 熄图标，
   **不会断开系统音频**（`disconnectAllEnabledProfiles` 未生效，待查正确方法名）。
   之后 App 点「刷新状态」会自动重连（`ACTION_STATE_REQ` 里有恢复路径）。
2. 设置页仍只显示一个总电量（三处最小值）。
3. 「强提示」弹窗（`MiuiStrongToastUtil`）尚未移植。

### 通知动作区只有一格（实测，2026-11-17 19:07）

想要"把降噪各个选项塞进通知"，实测结论是**做不到（走标准 action）**：

| `miui.showAction` | 加入 4 个 action 后的实际渲染 |
| --- | --- |
| `true` | **只显示第 1 个**按钮（胶囊），后面 3 个被吞掉 |
| `false` | **一个按钮都不显示** |

所以当前采用**单按钮循环**：按钮标题 = 当前模式（深度/通透/普通…），点一下切下一个
（`深度 → 通透 → 普通`），并乐观点更新 UI，避免连点重复发同一条。

要做"选项全摆出来"，只有三条路：

| 方案 | 代价 |
| --- | --- |
| ① 自定义 RemoteViews 通知布局（一排 4 个按钮） | 样式不再是小米原生；跨包 RemoteViews 需从 App 进程发通知 |
| ② 点通知按钮 → 打开半屏悬浮控制面板（Activity，7 模式 + 3 档强度） | 成本低，等于"控制台"搬到系统 UI 之上 |
| ③ 真·融合设备中心卡片（注入 MiLink `DeviceInfo`） | 最原生，但要接 SystemUI 注入 + 点击控制，工作量最大 |

---

## 十四、复用 HyperVolumeAnc：音量面板里控制耳机（2026-11-17 19:43 里程碑）

### 结论

**HyperVolumeAnc 一行代码没改**，RosePods 按它的广播契约说话，
音量面板里就出现了「降噪 / 通透 / 关闭」控制，并且**真的切换了耳机**。

### 上游项目

- 仓库：**https://github.com/zhhhyyyyyy/HyperVolumeAnc**
- 本地源码：`third_party/HyperVolumeAnc/HyperVolumeANC-1.8.1/`（用户提供的 1.8.1 源码 zip）
- 它把 ANC 控件注入 SystemUI 音量面板（`com.android.systemui.miui.volume.MiuiRingerModeLayout`，
  这个类在插件 `miui.systemui.plugin` 的 classes.dex 里）

### 对接方式：伪装成 OppoPods

HyperVolumeAnc 原本通过广播与「OppoPods 模块」对接。关键代码在 `AncController`：

```java
MODE_OFF=0, MODE_NOISE_CANCELLING=1, MODE_TRANSPARENCY=2
BLUETOOTH_PROCESS_PACKAGE = "com.android.bluetooth"

// 控制下发（SystemUI → 设备模块）
OPPO_ANC_ACTION     = "chen.action.oppopods.anc_select"
  extra: "status"(1=关/2=降噪/3=通透) + "address"
OPPO_REFRESH_ACTION = "chen.action.oppopods.refresh_status"

// 状态上报（设备模块 → SystemUI）
ACTION_OPPO_STATE   = "io.github.zhhhyyyyyy.hypervolumeanc.action.OPPO_STATE"
  extra: "status" + "address"
```

**名字不是门槛**：源码注释明确写了 headset 名字可以被改，
真正决定是否接管的是「模块有没有按地址上报状态」（`noteOppoReport` + 120s 新鲜度）。

### 我们的实现（`hook/SystemBridge.kt` + `core/PodsContract.kt`）

| 方向 | 我们做什么 |
| --- | --- |
| 收到控制 | 注册 `chen.action.oppopods.anc_select` → status 翻译成我们的模式 → `setNoiseMode()` |
| 上报状态 | 连接成功 / 模式变化 / **每 30 秒心跳** 广播 `...action.OPPO_STATE` 到 `com.android.systemui` |
| 模式映射 | status 1(关)→普通(2)、2(降噪)→降噪(1)、3(通透)→通透(3)；反向同理 |

> 30 秒心跳是必须的：HyperVolumeAnc 的状态记录 **120 秒**过期，不重发就会把行收掉。

### 平板实测（25091RP04C）

```
19:40:55 Vector: Loaded module io.github.zhhhyyyyyy.hypervolumeanc   (systemui)
19:40:57 Vector: Loaded ... (com.android.bluetooth / com.xiaomi.bluetooth)
19:41:12 HyperVolumeANC: ANC row joined native show/hide animation sequence
19:42:22 RosePods: HVA anc_select status=2 -> mode=1
19:42:22 RosePods: spp TX <命令帧>        ← 09 01 降噪
19:42:22 RosePods: spp 降噪模式=1                ← 设备回报：已切换
19:42:23 HVA status=3 -> mode=3 → TX 09 03 → 模式=3（通透）
19:42:28 HVA status=2 -> mode=1 → TX 09 01 → 模式=1（降噪）
```

**闭环：音量面板点击 → 广播 → SPP 命令 → 设备切换 → 状态回报。**

### 踩坑记录

1. **新模块必须重启设备**：`vector-cli modules enable` 之后 `killall systemui` / `force-stop`
   都无效，Vector 的日志里没有加载记录；**重启平板后三个进程才全部加载**。
   （`staticScope=true` 的模块尤其如此。）
2. **SystemUI 只被注入一次**：重启前 `/proc/<systemui>/maps` 里没有 `zygisk_vector`，
   说明 Vector 根本没注入——不是 HVA 的问题。
3. HyperVolumeAnc 作用域写死在 module.prop：`com.android.systemui`、`com.xiaomi.bluetooth`、
   `com.miui.misound`，**不含 com.android.bluetooth**；所以我们直接发到 SystemUI，
   绕过它的 OppoPodsBridge（那个桥跑在 com.xiaomi.bluetooth 里）。

---

## 十五、正式包验收（2026-11-17 20:01）

### 产物

`app/build/outputs/apk/release/app-release.apk`（2.19 MB，release.keystore 签名，versionName 1.2）

> 签名换了以后要先 `adb uninstall` 再装，否则 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。
> 重装后 UID 会变，Vector 里模块会变 **disabled**，需要重新
> `vector-cli modules enable` + `scope set`。

### 电量（双耳 + 仓）

```
spp 电量 左=90 右=90 仓=57 充电(L=false R=false C=false) raw 5a/5a/39
电池细节 L90(-) R90(-) C57(-)
notification posted: 充电盒：57%
```

三个电量与各自充电位都正常 ✓（`raw 5a/5a/39`：左 0x5A、右 0x5A、仓 0x39）

### 模式切换

7 种模式逐条下发，**全部被设备 ACK 接受**（`RX dd..02 fe 00 ..`）：

| 模式 | 下发 | 设备 ACK | 设备主动回报 |
| --- | --- | --- | --- |
| 1 降噪 | `09 01` | 00 ✓ | 未回报 |
| 2 普通 | `09 02` | 00 ✓ | 未回报 |
| 3 通透 | `09 03` | 00 ✓ | 模式=3 ✓ |
| 4 风噪 | `09 04` | 00 ✓ | 模式=4 ✓ |
| 5 自适应 | `09 05` | 00 ✓ | 模式=5 ✓ |
| 6 深度 | `09 06` | 00 ✓ | 模式=6 ✓ |
| 7 动态 | `09 07` | 00 ✓ | 模式=**4** ⚠️ |

两个需要知道的实测结论（都不是代码问题）：

1. **设备不是每次都回报模式**：1/2 只 ACK 不推送。所以 UI 用「乐观更新」是对的
   （发完立刻更新 state，回调来了再校正）。
2. **模式 7（动态）在琉璃U 上落点是 4（风噪）**：命令被接受，但固件把它当风噪处理。
   如果用户点「动态」听感上像风噪，就是这个原因——属于耳机固件行为，不是协议错误。

### 其他入口（同日已验证）

- 通知：标题/电量/循环按钮都在（`notification posted: 充电盒：57%`）
- 状态栏：`statusbar icon wireless_headset = true`
- HyperVolumeAnc 音量面板：点击 → 广播 → SPP → 设备切换 → 回报（第十四节）

---

## 十六、设置页为什么只有一个电量（2026-11-17 20:21 结论）

### 实测结论

平板 HyperOS 4 的「设置 → 蓝牙」里：

| 位置 | 显示内容 |
| --- | --- |
| 设备列表行 | `已连接 \| 电量为 57% \| 使用中` —— **只有一个数字位** |
| 设备详情页 | 重命名 / 取消配对 / 设备类型 / 用于（通话·媒体音频）/ 属性（音量同步·LDAC）—— **完全没有电量区块** |

也就是说：**这个系统的设置页根本没有显示分体电量的地方**，小米自家耳机连上来同样是一个总电量。
（列表页那个数字取的是三处最小值，我们现在注入的就是 min(L,R,C) = 57。）

### 试过的方案（已实现但用不上）

`hook/SettingsBatteryHook.kt`：在 `com.android.settings` 进程 hook
`CachedBluetoothDevice.getBatteryFromBluetoothService()`，用蓝牙栈里的元数据
（`METADATA_UNTETHERED_LEFT/RIGHT/CASE_BATTERY` = 10/11/12）构造 `BatteryLevelsInfo` 返回。

实测结果：hook 装上了（日志 `SettingsBatteryHook installed`），但**从未被调用**
（没有 `settings battery L… R… C…` 日志）——因为详情页压根不查分体电量。
代码保留着，将来系统 UI 变了可能有用。

### 分体电量的正确位置

**通知栏的小米原生通知**（第十四节）：左 / 右 / 仓分行显示，还带各自充电状态。
这是这台设备上唯一原生支持分体电量的地方。

### 如果一定要塞进设置页

只有一条路：hook `com.android.settings.bluetooth.BluetoothDeviceDetailsFragment`
（页面类已确认存在，另有 `Settings$BluetoothDeviceDetailActivity`），
往它的 preference 列表里插一条自定义项。属于纯 UI 改造，成本高、收益一般。

---

## 十七、原生小米耳机页打通了（2026-11-17 21:16 里程碑）

### 结果

设置里点 `ROSE Ceramics U`，打开的是**小米耳机的原生控制页**：

| 区块 | 状态 |
| --- | --- |
| 耳机产品图 | ✅ 系统渲染 |
| **左 / 右 / 充电盒 电量** | ✅ 82% / 83% / 57%（我们注入的） |
| **通透 / 降噪 / 关闭** | ✅ 可点，点了真的切换耳机 |
| **强度档（办公 / 日常 / 航旅）** | ✅ 可拖，对应我们的强度命令 |
| 重命名 / 检查更新 | ✅ |
| 通话 / 媒体音频 / 通讯录 | ✅ |
| 音效设置 → 虚拟环绕声 | ✅ |
| 手势操作：佩戴检测 / 双设备 / 自动接听 / 通知栏显示 / 自动升级 / 耳塞贴合 | ✅ |

点按钮的实测日志：

```
fragment updateAncMode mi=1 -> pods=1
  TX <命令帧>         ← 09 01 降噪，设备执行
fragment updateAncLevel 0101 -> pods=1
  TX <命令帧>         ← 2C 01 轻度
```

### 原理（全部移植自 OppoPods 2.2.0，GPL-3.0）

源码放在 `third_party/OppoPods/OppoPods-2.2.0/`。关键三步：

**① 在 `com.xiaomi.bluetooth` 进程伪造"小米 TWS"**

`hook/MiuiHeadsetBridge.kt` hook `BluetoothHeadsetService$HeadsetBinder`：

```
checkSupport / getDeviceInfo → "01010607,000000000000000010000000"
isMiTWS / checkIsMiTWS       → true      ← 这一句让系统当成自家耳机
getRingFindState             → false
setCommonCommand             → 接管
register(callback)           → 记住 IMiuiHeadsetCallback
```

拿到 callback 后用 `refreshStatus(address, payload)` 推状态，payload 是
**16 字段逗号分隔**：`[0]左耳 [1]右耳 [2]仓 [7]ANC 码 [8]"true"`。

**② 在 settings 进程打通控件**

`hook/MiuiHeadsetFragmentHook.kt` hook `com.android.settings.bluetooth.MiuiHeadsetFragment`：

```
onCreateView / onServiceConnected → 拿 fragment，注入
updateAtUiInfo("模式|档位列表|电量|00")  ← 竖线格式，和上面那套不同
updateAncUi(level, false)
updateAncMode(int, boolean)   ← 用户点模式，转成我们的 setNoiseMode
updateAncLevel(String,boolean)← 用户拖强度，转成 setNoiseLevel
```

另有 `hook/MiuiSettingsProxyHook.kt` hook AIDL 代理
`IMiuiHeadsetService$Stub$Proxy`（保底路径）。

**③ 跨进程状态通道**

SPP 在 `com.android.bluetooth`，耳机服务在 `com.xiaomi.bluetooth`，页面在
`com.android.settings`。用广播 `rosepods.action.STATUS_TO_XBT` 把
电量/模式从蓝牙进程推给另外两个进程（`SystemBridge.broadcastState()`）。

### 两个坑

1. **每个进程都要单独重启**才加载新代码：
   `com.android.bluetooth` / `com.xiaomi.bluetooth` / `com.android.settings`
   三个都 force-stop 过才算全生效（我漏了 `com.xiaomi.bluetooth`，导致映射改了不生效）。
2. **点列表行 = 断开连接**，不是进详情页；要进原生耳机页要点右侧箭头，
   或者直接：
   ```bash
   adb shell am start -n com.android.settings/.bluetooth.MiuiHeadsetActivity \
     --es bluetoothaddress 84:9D:4B:3D:7F:53 --es COME_FROM MIUI_BLUETOOTH_SETTINGS
   ```

### 风噪的归属（用户要求）

风噪（我们的模式 4）在 MIUI 的三个按钮里没有对应项，按要求**归到「降噪」**：

```
1, 4, 5, 6, 7 → "1"（降噪）   3 → "2"（通透）   其他 → "0"（关闭）
```

对应 ANC 码：`6→0102`、`1,4,5,7→0100`、`3→0200`、其他`0000`。

---

## 十八、改用自己的控制页 + 悬浮小窗（2026-11-17 22:06）

### 为什么放弃小米那个页面

小米耳机页是**固定模板**：只有通透/降噪/关闭三个按钮（没有风噪），
还带虚拟环绕声、佩戴检测、双设备连接、自动升级、耳塞贴合等**我们耳机没有的功能**，
强度档也是小米语义（办公/日常/航旅）。硬套的结果是"假选项 + 真功能进不去"。

所以：**拦住小米页面，换成我们自己的控制页。**

### 实现

`hook/SettingsRedirectHook.kt`（装在 `com.android.settings`）：

- hook `MiuiHeadsetActivity` 和 `MiuiHeadsetActivityPlugin` 的
  `onCreate` / `onStart` / `onResume`
- 命中后：启动 `DeviceControlActivity` → `finish()` → **返回 null，不执行原方法**
  （小米页面根本不渲染）
- `ONLY_OUR_DEVICE = false`：这个页面只有小米耳机才会被打开，直接全拦

日志确认：
```
SettingsRedirectHook installed in com.android.settings, hooked=3
blocked com.android.settings.bluetooth.MiuiHeadsetActivity (MiuiHeadsetActivity)
```

### 两个页面

| Activity | 用途 |
| --- | --- |
| `MainActivity` | 桌面图标打开的**主页**：设备卡片、电量/模式摘要、系统集成状态 |
| `DeviceControlActivity` | 设置里点耳机弹出的**悬浮小窗**（控制台） |

### 悬浮小窗的样子

`res/values/styles.xml` 的 `Theme.RosePods.Floating`：
`windowIsTranslucent` + 透明背景 + `backgroundDimAmount=0.6` + `windowCloseOnTouchOutside`。

- 宽度：`min(屏宽-48dp, 屏高/3*0.96)`
- 高度：**wrap_content 贴合内容**（不再固定 1/3 屏，避免大片空白）
- 固定尺寸撑高会留白，这条踩过坑
- 点卡片外面 → 关闭

内容（一屏装得下）：

```
[←  设备控制      ]
[🎧 ROSE Ceramics U]
[   已连接         ]
[77% 左][78% 右][57% 仓]
[通透][降噪][普通][风噪]     ← 第一排 4 个
    [自适应][深度][动态]       ← 第二排 3 个居中（两侧 0.5 权重占位）
[轻度][均衡][深度]            ← 强度
```

### 交互规则（实测确认）

- **7 种模式各自独立高亮**，不做归并（风噪/深度/自适应/动态 都是独立模式，
  选中时只有自己亮，降噪不跟着亮）
- **强度只在降噪类模式可调**（降噪/风噪/自适应/深度/动态）；
  通透/普通下三档置灰、不可点，并提示「当前模式下不支持调节强度」
- 点按钮**立即本地高亮**，不等设备回报（设备不总是回报模式）；
  系统侧 `sendMode` 也做乐观更新并广播

### 图标

全部矢量 drawable（`res/drawable/`）：
`ic_buds_hero`（耳机+仓）、`ic_mode_transparency/anc/off/wind/adaptive/deep/dynamic`、
`ic_battery`、`ic_back`。

---

## 十九、纯框架实现音量面板控制行（2026-11-17 23:00 里程碑）

### 结论

**不需要 HyperVolumeAnc，也不需要 root** —— 我们自己的模块直接把控制行画进
系统音量面板：

```
22:46:40  RosePods: volume panel ANC row injected
```

### 关键坑一：Vector 在 systemui 里报的 packageName 是错的

`onPackageReady` / `onPackageLoaded` 的 `param.packageName` 在 systemui 进程里报成

```
pkg=com.tencent.mm          proc=com.android.systemui
pkg=com.xiaomi.misettings   proc=com.android.systemui
pkg=io.github.shiwushuifeng.rosepods     proc=com.android.systemui
```

按 packageName 分发会永远走错分支。**必须用进程名判断**：

```kotlin
private fun currentProcess(): String = runCatching {
    Class.forName("android.app.ActivityThread")
        .getMethod("currentProcessName").invoke(null) as? String ?: ""
}.getOrDefault("")
```

两个注意点：
1. **只能调一次**，把结果传下去（调两次第二次可能返回空，导致静默 return）
2. 所有 return 分支都要打日志，否则出错时完全看不到

### 关键坑二：`MiuiRingerModeLayout` 在插件 APK 里

`MiuiRingerModeLayout` / `miui_ringer_btn_layout` / `dnd_layout` **只在**
`MIUISystemUIPlugin.apk`（包名 `miui.systemui.plugin`）里，systemui 主包没有。
普通 `Class.forName` 找不到，hook `ClassLoader.loadClass` 也抓不到（插件加载早于模块）。

**正解**（从 HyperVolumeAnc 源码里找到的）：

```java
Class<?> injector = Class.forName(
    "com.miui.systemui.plugin.PluginInstanceInjector", false, loader);
Field field = injector.getDeclaredField("sClassLoaders");   // 静态 Map
Map map = (Map) field.get(null);
ClassLoader pluginLoader = (ClassLoader) map.get("miui.systemui.plugin");
```

`PluginInstanceInjector.sClassLoaders` 是静态 Map，插件加载完就缓存在里面，
**不用等任何回调**。配合 500ms × 24 次轮询即可稳定命中。

> 注意传给 `Class.forName` 的 loader 不一定对，要挨个试：
> `param.classLoader` → `ActivityThread.currentApplication().classLoader`
> → `mInitialApplication.classLoader` → `systemClassLoader`。

### 关键坑三：软重启就够了

```
adb shell su -c 'stop; sleep 2; start'
```

zygote 重启会重新加载所有模块，**不用重启整机**，比整机快得多（约 40 秒）。

### 注入点

```kotlin
hook(MiuiRingerModeLayout#onFinishInflate) {
    // 找到 miui_ringer_btn_layout 和 dnd_layout
    // 在 dnd_layout 后面插我们的一行
}
```

行内容：**通透 / 降噪 / 关闭** 三个胶囊按钮（图标 + 文字），
选中态蓝底白字（`#2C6BED`），未选中深灰（`#2A2A2A`），未连接时整体 45% 透明。

图标通过 `context.createPackageContext("io.github.shiwushuifeng.rosepods", 0)` 从我们自己 APK 取。

点击 → 广播 `ACTION_CMD` 到蓝牙进程 → SPP 发命令，并本地乐观高亮。

---

## 二十、设置 / 关于 / 框架自检（2026-11-17 23:06）

### 去掉了「一键集成」

KernelSU 在某些配置下**不弹授权窗**，App 侧 `su` 直接失败，
所以"App 自动装 HVA"这条路不通。改为：

- **纯框架检测**：模块每加载进一个进程就往 App 广播一次
  （`ACTION_MODULE_ONLINE` + 进程名），App 收集后显示 ✓/✗
- 钩子每 15 秒心跳一次，App 任何时候打开都能收集到
- 完全不依赖 root

四个必需进程：

| 进程 | 职责 |
| --- | --- |
| `com.android.bluetooth` | 蓝牙栈 · 建立 SPP |
| `com.xiaomi.bluetooth` | 小米蓝牙扩展 · 供数据给系统 |
| `com.android.settings` | 设置 · 弹窗控制页 |
| `com.android.systemui` | 系统界面 · 音量面板控制行 |

### 设置项（改完即时同步，不用重启）

| 开关 | 作用 |
| --- | --- |
| 音量面板降噪控制行 | 控 `VolumePanelAncRow` 是否注入 |
| 耳机通知 | 控 `MiuiHeadsetUi.post` |
| 状态栏耳机图标 | 控 `setIconVisibility` |
| 设置页弹窗控制 | 控 `SettingsRedirectHook` |

同步方式：App 写 SharedPreferences → 广播 `ACTION_SETTINGS` 到四个包 →
钩子里的 `SettingsBridge` 更新内存标志。钩子侧读取：
`SettingsBridge.volumeRow` / `.notification` / `.statusbar` / `.popup`。

### 关于页

- 作者：**shiwushuifeng**
- 致谢：OppoPods（1812z / Leaf-lsgtky）、HyperVolumeAnc（zhhhyyyyyy）、LSPosed / Vector
- **协议：GPL-3.0** —— 移植了上面两个 GPL 项目的思路与实现，按协议要求本项目同样开源

### 已移除

- `IntegrationHelper.kt`（root 一键安装）
- `assets/hypervolumeanc.apk`
- 对 HyperVolumeAnc 的运行时依赖

APK 从 2.19MB 降到 2.29MB（加了图标和页面，但去掉了 2.1MB 的内置 APK）。

---

## 二十一、音量面板按钮：从三个胶囊改成单个循环按钮（2026-11-17 23:37）

### 踩到的坑：插错容器导致按钮被压成细条

第一版把控制行插到 `miui_ringer_btn_layout`（铃铛/勿扰那排的容器），
实测**那个容器只有 150px 宽**，三个按钮各被压到 22px（8dp）：

```
volume row size=150x144  cells=[22x103, 23x103, 23x103]   ← 8dp 宽的细条
```

**修法**：改插 `miui_ringer_state_layout`（一个 FrameLayout，宽度能给满），
并把它的高度改成 `wrap_content` 腾空间（这步来自 HyperVolumeAnc 的
`setWrapContentHeight`，漏了会出现"面板上半一片空白 + 按钮被挤压"）。

```
修复后: volume row size=592x144  cells=[170x103, 170x103, 170x103]   ← 64dp
```

> 调试技巧：别依赖截图/UI dump（音量面板约 2 秒自动收起，抓不到）。
> 在 `row.post { }` 里把实测 `width x height` 打到日志，比截图可靠。

### 最终形态：单个原生样式按钮

用户要求"像小米原生那一个按钮"，且担心多控件容易出 bug，所以做成：

```
[ 🎧 降噪 ]   ← 图标 + 当前模式名，点击循环
```

- 循环顺序：**降噪 → 通透 → 普通 → 降噪**
- 内部只有一个状态（`currentMode`），`normalize()` 把设备的 7 种模式归到三态
- 未连接时整体 45% 透明
- 实测尺寸 283×134px（按钮 223×103px，约 84×39dp）

三个模式**直接点选**的入口保留在控制小窗里（`DeviceControlActivity`），
音量面板只放最常用的循环开关，减少状态同步的出错面。

### 检查清单（这次确认过的）

| 项 | 证据 |
| --- | --- |
| 行注入成功 | `volume panel ANC row injected into FrameLayout` |
| 按钮尺寸正常 | `volume row size=283x134 cells=[223x103]` |
| 设置开关可关 | `SettingsBridge.volumeRow` 为 false 时直接 return |
| 图标取自本 APK | `context.createPackageContext("io.github.shiwushuifeng.rosepods", 0)` |

---

## 二十二、完整移植 HyperVolumeAnc 音量面板（2026-11-18 00:40 最终版）

### 结论

**完整搬 HVA 的 SystemUI 侧代码**（11 个类 + 8 个挂载点），音量面板里出现
**小米原生的磨砂玻璃按钮**，点击直接切我们的耳机：

```
ANC button click received
toggle -> target=0 current=2 device=ROSE Ceramics U(84:9D:4B:3D:7F:53)
sent setMode wire=2 to com.android.bluetooth
TX <命令帧>  （原始帧见 private/captures/handoff-raw-frames.txt，不公开）
spp 降噪模式=3          ← 耳机回报已切到通透
```

### 为什么之前"只搬注入器"会把面板搞坏

`VolumeButtonInjector` 不只是插按钮，它还会改面板几何（`setWrapContentHeight`、
阴影 padding、`timer` 容器、展开高度补偿）。**这些必须和另外 7 个动画/几何 hook
一起工作**，否则：状态栏消失、媒体条消失、音量条被挤出、音量键失灵。

**教训：这类深度耦合系统 UI 的代码，要么完整搬，要么别碰。**

### 完整清单

放在 `hook/hva/` 子包（避免和我们自己的类重名）：

| 文件 | 作用 |
| --- | --- |
| `HvaVolumeEntry.java` | 入口，改造自 HVA 的 HookEntry（只留 SystemUI 分支） |
| `VolumeButtonInjector.java` | 注入器，inflate 系统布局 + NativeButton |
| `AncController.java` | 控制层（**已改成发我们的广播**） |
| `AirPodsModeDrawable.java` | 图标绘制 |
| `MediaVolumeEntry.java` | 媒体音量入口 |
| 其余 6 个 | HookLog / HookDiagnostics / Settings / OppoPodsBridge / ModeIslandNotifier / HookHeartbeat |

**8 个挂载点**（缺一个就可能坏布局）：

```
installPluginDiscovery → installLayoutHook
  ├── onFinishInflate         注入原生布局
  ├── updateExpandedH         展开/收起
  ├── updateResources         样式刷新
  ├── onMaterialModeChanged   材质模式
  ├── collapsedPreparation    收起准备
  ├── expandedHeight          展开高度补偿
  ├── ShowHideAnimator        显隐动画
  ├── ExpandCollapsedAnimator 展开收起动画
  └── DialogHooks             对话框
```

### 改造点：把 toggle 接到我们的链路

HVA 原本通过 Binder 调小米的 `changeMode`。改成直接发我们的广播：

```java
void toggle() {
    // 算下一个模式 → 发广播给 com.android.bluetooth → 它经 SPP 下发
    sendModeToRosePods(target);
    publish(device, target);   // 乐观更新按钮状态
}
```

### 静态作用域声明（用户不用手动勾选）

`META-INF/xposed/module.prop`：
```
minApiVersion=101
targetApiVersion=102
staticScope=true
```

`META-INF/xposed/scope.list`：
```
com.android.bluetooth
com.xiaomi.bluetooth
com.android.settings
com.android.systemui
```

> 装完在框架里启用即可，四个作用域自动带好。

### 其他改动

- **移除框架检测 UI**（主页只剩设备卡片 + 设置/关于）
- **图标重做**：深蓝渐变 + 左上柔光 + 白色耳机组合，盒盖留一道缝
- 版本 **1.3 / versionCode 4**