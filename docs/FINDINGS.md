# 逆向过程记录

本文记录 CERAMICS-U 协议的完整逆向过程，包括思路、证据和踩过的坑，供后续维护参考。

## 环境

| 项目 | 值 |
| --- | --- |
| 耳机 | roseselsa CERAMICS-U（`ROSE Ceramics U`，`84:9D:4B:3D:7F:53`） |
| 手机 | Xiaomi 15 Pro / HyperOS 4 / Android 17 |
| Root | KernelSU |
| App | RoseLink 3.8.4（`cn.ikaile.ruoshui.client`） |

## 一、静态分析

RoseLink 是 **Flutter** 应用，协议在 `libapp.so` 的 Dart AOT 快照里，Java 层无逻辑。

从 `libapp.so` 提取到的线索：

- 服务 UUID：`0CF12D31-FAC3-4553-BD80-D6832E7B3931`
- 机型配置类的组织方式：`rose_bluetooth/config/{f8, rs_common, rs_common_v2, zt}/`
- 与 CERAMICS-U 相关的类名：`ceramics_u_device_config.dart`
- 载荷熵约 3.6 bit/byte（随机数据为 8），**判定为明文协议**

内置机型白名单 137 条，覆盖 OPPO / OnePlus / realme（与另一类耳机模块无关，此处仅记录）。

## 二、抓包

### HCI snoop

```bash
su -c 'setprop persist.bluetooth.btsnooplogmode full'
svc bluetooth disable; svc bluetooth enable
# 操作 App 后
cp /data/misc/bluetooth/logs/btsnoop_hci_*.log /sdcard/
```

解析要点：

- btsnoop 的时间戳纪元不是 1970，需以文件首记录为基准做相对换算
- L2CAP 长包会分片（PB 标志位 1），需要重组
- RFCOMM 帧头 `37 EF` / `35 FF` 分别对应不同 DLCI，不属于应用层数据

### 关键发现

App 与耳机存在两条连接：

| 连接 | 帧头 | 用途 |
| --- | --- | --- |
| 第一条 | `55 07` | 握手、设备信息 |
| 第二条 | `35 EF` / `37 FF` | 状态上报、实时控制 |

应用层实际字节（剥离 RFCOMM 头后）：

```
ff <seq> <len> <cmd> <data> <cs> aa    手机 → 耳机
dd <seq> <len> <cmd> <data> <cs> aa    耳机 → 手机
```

## 三、动态验证（Xposed hook）

写了一个 Xposed 模块注入 RoseLink，hook `BluetoothOutputStream.write` / `BluetoothInputStream.read`，
把单向数据落到文件，得到不带 RFCOMM 头的纯应用层帧。

这解决了两个问题：

1. 确认应用层帧的精确边界
2. 找到校验算法

### 校验算法

对所有字节求和取低 8 位：

```
CS = (HEADER + SEQ + LEN + BODY...) & 0xFF
```

验证样本（全部命中）：

```
dd 10 04 0c 39 39 3a a9 aa    ← 电量上报，sum=a9 ✓
ff 08 02 09 02 14 aa          ← 设普通，sum=14 ✓
ff 01 03 fa 45 2f 71 aa       ← 心跳，sum=71 ✓
```

## 四、独立 App 实现

### 连接

文档给出的 SPP UUID `0CF12D31-...` 直接可用：

```kotlin
device.createInsecureRfcommSocketToServiceRecord(
    UUID.fromString("0CF12D31-FAC3-4553-BD80-D6832E7B3931")
)
```

### 最大的坑

**写命令不能带 `02` 前缀**。

参考文档里 `setNoiseModeAnc` 的 Body 写作 `09 01`，但 V2 协议族的读取命令形如
`FA 01 07 08…`（带 `FA` 前缀），容易让人以为写命令也要一个前缀。

实测结果：

| 发送内容 | 设备应答 | 结果 |
| --- | --- | --- |
| `02 09 01` | `dd .. 02 fe 02 ..` | 被拒（状态码 02） |
| `09 01` | `dd .. 02 fe 00 ..` | 成功（状态码 00） |

**状态码 `00` = 成功，`02` = 拒绝**。设备不会返回错误信息，只是静默不生效，
这一点很容易误判为「帧没发出去」。

## 五、其他注意事项

### SPP 单客户端

耳机同一时间只接受一个 SPP 客户端。RoseLink 在运行时，新连接会一直阻塞在
`socket.connect()`，表现为「连不上」而不是明确报错。

处理方式：

1. 先 `am force-stop cn.ikaile.ruoshui.client`
2. 若之前有异常断开，残留会话会导致后续连接全部失败
3. 此时需要**把耳机取出充电仓**重新连接，才能清掉耳机侧状态

### 充电仓电量缓存

充电仓电量只在耳机回仓时更新。耳机取出后，该字节保持最后一次读数。

### 协议主动上报

设备会主动推送模式与电量变化，不需要轮询：

```
dd 07 02 09 03 f2 aa    模式变为通透
dd 0a 02 09 05 f7 aa    模式变为自适应
```

这是实现实时状态的基础。

## 六、证据等级

| 结论 | 等级 |
| --- | --- |
| 帧格式、校验算法 | 实机验证 |
| 降噪 7 种模式 | 实机验证（深度降噪已听感确认） |
| 电量三字节解析 | 实机验证（与 RoseLink 显示一致） |
| 强度命令 `2C NN` / `2D NN` | 帧格式确认，听感未逐个验证 |
| 其他 RS Common V2 型号 | 未验证 |