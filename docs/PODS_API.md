# CeramicsUPods 接口说明（给前端/UI 的同学）

> 目的：协议层已经写好并实测过，**不要重复造轮子**。
> 需要联耳机的地方，直接调用 `moe.chenxy.rosepods.pods.CeramicsUPods`。
> 协议细节见 [PROTOCOL.md](PROTOCOL.md)，踩坑记录见 [FINDINGS.md](FINDINGS.md)。

## 唯一需要调用的类

```kotlin
val pods = CeramicsUPods(
    onBattery = { l, r, c, charging -> /* 更新电量 UI */ },
    onNoiseMode = { mode -> /* 更新模式高亮 */ },
    trace = { msg -> /* 日志 */ }
)
pods.adapter = bluetoothAdapter
pods.connect(device)                            // 连接（阻塞，放在子线程）
pods.setNoiseMode(CeramicsUPods.NOISE_ANC)      // 降噪
pods.setNoiseLevel(CeramicsUPods.LEVEL_MEDIUM)  // 强度
pods.close()                                    // 断开
```

### 回调（都在后台线程触发）

| 回调 | 参数 | 说明 |
| --- | --- | --- |
| `onBattery` | `left, right, case, charging` | 左/右/仓电量（0–100，未知为 0），`charging` 是左耳字节 bit7 |
| `onNoiseMode` | `mode` | 当前降噪模式，取值见下面的常量 |
| `trace` | `msg` | 收发帧与状态文本，直接塞日志框即可 |

> UI 线程更新请自己 `runOnUiThread { ... }`，协议层不碰线程模型。

### 方法

| 方法 | 说明 |
| --- | --- |
| `connect(device: BluetoothDevice)` | 阻塞式连接，内部最多重试 3 次；失败抛 `IOException` |
| `close()` | 断开并停止接收线程 |
| `setNoiseMode(mode: Int): Boolean` | 切降噪模式 |
| `setNoiseLevel(level: Int): Boolean` | 降噪强度 1–5 |
| `setTransparencyLevel(level: Int): Boolean` | 通透强度 1–5 |
| `requestInfo(): Boolean` | 主动拉一次电量/模式（连接后会自动调一次） |
| `sendRaw(hex: String): Boolean` | 试验用，发任意 BODY |
| `connected` | 只读，当前是否连着 |
| `onClosed` | 可选回调：链路异常断开时触发（用于 UI 复位/重连） |

### 常量

```kotlin
// 降噪模式
NOISE_ANC        = 0x01   // 降噪
NOISE_NORMAL     = 0x02   // 普通
NOISE_TRANSPARENT= 0x03   // 通透
NOISE_WIND       = 0x04   // 风噪
NOISE_ADAPTIVE   = 0x05   // 自适应
NOISE_DEEP       = 0x06   // 深度
NOISE_DYNAMIC    = 0x07   // 动态

// 强度
LEVEL_LIGHT  = 0x01
LEVEL_MEDIUM = 0x03
LEVEL_DEEP   = 0x05
```

## 三个必须知道的坑（务必转告）

1. **写命令不能加 `02` 前缀**
   写 `09 01` 生效；写成 `02 09 01` 设备会回 ACK `02`（拒绝），**不报错、不生效**。
   协议层已经处理好了，别自己拼帧。

2. **连接前必须停掉 RoseLink**
   耳机同一时间只接受一个 SPP 客户端。RoseLink 在跑时 `socket.connect()` 会一直阻塞，
   表现是"连不上"而不是报错：
   ```bash
   su -c 'am force-stop cn.ikaile.ruoshui.client'
   ```

3. **残留会话要把耳机取出来重连**
   异常断开后耳机侧会保留状态，后续连接全部失败。只开盖没用，必须取出充电仓重新连。

## 线程与生命周期

- `connect()` 是阻塞调用（内部有 3 次重试 + 每次 3 秒间隔），**必须放到子线程**。
- 协议层内部会起一个 `CeramicsU-rx` 守护线程收帧，`close()` 会停掉它。
- 同一个 `CeramicsUPods` 实例不要并发 `connect()`。
- 耳机只允许一个客户端：**App 和 LSPosed 系统侧不要同时各连一个**。
  系统侧接管时，App 应停止自己的连接（当前实现通过广播告知）。

## 系统侧（LSPosed）也在用同一个类

插件在 `com.android.bluetooth` 进程内用 `CeramicsUPods` 建链，电量写回系统蓝牙栈。
这条链路对 UI 透明：UI 只需要监听 `moe.chenxy.rosepods.action.STATE` 广播
（字段定义见 `core/PodsContract.kt`），不要自己再连一次耳机。

## 不要做的事

- 不要另写一套组帧/校验（`FF SEQ LEN BODY CS AA`，`CS = 求和 & 0xFF`）
- 不要轮询电量（设备会主动上报）
- 不要在 UI 线程调 `connect()`
- 不要把写命令加上 `FA`/`02` 前缀