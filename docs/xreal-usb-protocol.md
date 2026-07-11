# XREAL USB protocol notes

本文记录 Sensor Probe 已实现的 XREAL USB 协议。重点是经过 XBX A01
(`3318:0440`, Helen) 真机冷启动验证的 MCU 与 IMU 协议；其他 XREAL
型号的 VID/PID 和接口编号来自官方驱动表，不能假定都使用完全相同的启动时序。

## 设备与接口

XBX A01 暴露三个 HID interrupt interface：

| interface | OUT | IN | 用途 |
|---|---:|---:|---|
| 0 | `0x03` | `0x82` | MCU 命令、响应、硬件事件、heartbeat |
| 1 | `0x05` | `0x84` | Helen IMU 控制、校准数据、64 字节 IMU 报告 |
| 2 | `0x07` | `0x86` | 辅助事件；具体用途尚未完整确认 |

所有 endpoint 的 max packet size 均为 64 字节。Android 侧先通过
`UsbManager` 获取授权和文件描述符，再用 `libusb_wrap_sys_device()` 接管传输。

官方驱动表中已知的 `0x3318` application PID：

| PID | 型号/代号 | official kernel/app type | IMU logical type | VSync type | 传输 |
|---:|---|---:|---:|---:|---|
| `0424` | Air / Air | 5 | 1 | 2 | HID if3，MCU if4 |
| `0426` | Air 2 Ultra / Flora | 35 | 31 | 32 | kernel HID if1，MCU if0 |
| `0428` | Air 2 / P55 | 23 | 19 | 20 | HID if3，MCU if4 |
| `0432` | Air 2 Pro / P55E | 29 | 25 | 26 | HID if3，MCU if4 |
| `0436` | One Pro / Gina | 41 | 37 | 38 | USB Ethernet，MCU if0 |
| `0438` | One / GF | 47 | 43 | 44 | USB Ethernet，MCU if0 |
| `043a` | Hylla | 53 | 49 | 50 | kernel HID if1，MCU if0 |
| `043c` | CP（官方内部代号） | 65 | — | — | USB Ethernet，MCU if0 |
| `043e` | One S / GS | 71 | 未在本版常量中命名 | 未命名 | USB Ethernet，MCU if0 |
| `0440` | XBX A01 / Helen | 83 | 79 | 80 | Helen HID if1，MCU if0 |
| `0442` | XBX A01 Plus / Helen Pro | 89 | 84 | 85 | Helen HID if1，MCU if0 |

另有 VIDDA/XREAL `109b:6002` boot 和 `109b:6003` kernel（official type
58/59，IMU/VSync logical type 55/56），以及 Flora `5343:0200`、Gina
`4142:9411` recovery 身份。Sensor Probe 会识别这些状态，但不会向 boot/recovery
设备发送运行期传感器命令。

相邻的奇数 PID 是对应 bootloader 身份，不应发送普通传感器命令。

## 与 ar-drivers-rs 旧型号协议的边界

[`ar-drivers-rs`](https://github.com/badicsalex/ar-drivers-rs) 覆盖的 XREAL
设备主要是 Air、Air 2、Air 2 Pro、Air 2 Ultra 和 Light/OV580 等较早驱动族。
这些实现仍然是 Sensor Probe 支持旧型号的重要来源，但不能直接用于 Helen/XBX。

| 项目 | 旧 Air / Air 2 驱动族 | XBX A01 / Helen |
|---|---|---|
| USB 组织 | 较直接的 HID 传感器/控制接口 | MCU interface 0 与 IMU interface 1 协同 |
| 初始化 | 旧 HID 启动流程 | 严格的 MCU → SDK 握手 → IMU 时序 |
| MCU 封包 | 旧型号控制协议 | `0xfd`、request ID、单调时钟、CRC32 |
| SDK 版本握手 | 旧开源流程没有 Helen 握手 | 必须发送 `0x31 / "3.1.1"` |
| IMU 控制 | 旧 Air HID 命令 | Helen `0xaa` 命令 `0x19/0x14/0x15/0x1a` |
| 校准读取 | 型号相关的旧格式 | 启动前顺序读取 55,026 字节 blob |
| 实时收包 | 普通 HID 读取模型 | 单个 `0x84` URB 串行 resubmit |
| 保活 | 无 Helen heartbeat 要求 | interface 0 持续发送 `0x1a` heartbeat |
| IMU report | 旧 Air 报告布局 | 带逐帧缩放参数的 Helen 64 字节布局 |

因此代码必须保留两条独立实现路径：

- Air、Air 2、Air 2 Pro、Light/OV580 使用公开旧协议对应的实现。
- Flora/Hylla 使用官方标记的 kernel IMU 分派和版本化 64-byte schema；其启动不套用
  Helen 的 MCU heartbeat 时序。它们仍使用官方通用 AA 控制层，在同一个 interface
  claim 和单个异步 URB 生命周期内执行 stop、读取完整校准 blob、sync、start。
- Helen/Helen Pro 使用本文记录的官方 AR Launcher 初始化与报告协议。
- Gina/GF/GS/CP/VIDDA 的 IMU 是 USB Ethernet 路径；HID/MCU interface 仍独立读取，
  不能因为启动网络 reader 就提前结束 USB session。

设备同为 `0x3318` VID 并不表示协议兼容。必须先按 PID/官方 driver family
路由，不能因为 endpoint 都是 HID interrupt 就向 XBX 发送旧 Air 启动命令，或向
旧 Air 发送 Helen 的 MCU/校准序列。

## MCU FD frame

MCU request 和 response 都以 `0xfd` 开头，整数为 little-endian：

| offset | size | 字段 |
|---:|---:|---|
| 0 | 1 | magic `0xfd` |
| 1 | 4 | CRC32 |
| 5 | 2 | body length，等于 `17 + payload length` |
| 7 | 4 | request ID |
| 11 | 4 | 单调时钟低 32 位 |
| 15 | 2 | command |
| 17 | 5 | 保留，当前请求填零 |
| 22 | N | payload |

CRC 使用标准 CRC-32/ISO-HDLC 多项式，初值和终值异或均为
`0xffffffff`；覆盖从 offset 5 开始的 `body length` 个字节。response 复用
request ID 和 command。已观察到的普通 ACK 在 offset 22 返回状态，`0` 表示成功。

### 已验证的 MCU 命令

| command | payload | 含义/用途 |
|---:|---|---|
| `0x26` | 空 | 官方启动序列第 1 步，具体语义未命名 |
| `0x57` | 空 | 官方启动序列第 2 步，具体语义未命名 |
| `0x12` | LE32 `1` | 官方启动配置 |
| `0x02` | LE32 `1` | 官方启动配置 |
| `0x34` | 空 | 官方启动序列 |
| `0x35` | 空 | 官方启动序列 |
| `0x31` | ASCII `3.1.1` | 客户端 SDK 版本握手；XBX 开始上报 IMU 的必要步骤 |
| `0x1a` | LE64 monotonic timestamp | heartbeat |
| `0x07` | 空 | 查询显示模式 |
| `0x08` | 1 字节 mode | 设置显示模式 |

当前显示模式值为：`1` 镜像 2D、`2` Half SBS、`3` Full SBS、`4`
高刷新 SBS。切换显示模式会直接改变眼镜状态，应用不应在被动探测时自动发送。

MCU 会在 `0x82` 主动上报 `0x6cxx` 事件。当前解析的事件包括镜腿/额头
温度 (`0x6c02`, `0x6c12`)、接近/佩戴 (`0x6c04`)、按键 (`0x6c05`)、
睡眠 (`0x6c07`)、VSync (`0x6c0b`)、屏幕状态 (`0x6c18`)、过温
(`0x6c19`) 和温漂校准结果 (`0x6c1a`)。

## Helen IMU AA frame

IMU 控制帧以 `0xaa` 开头：

| offset | size | 字段 |
|---:|---:|---|
| 0 | 1 | magic `0xaa` |
| 1 | 4 | CRC32 |
| 5 | 2 | body length，等于 `3 + payload length` |
| 7 | 1 | command |
| 8 | N | payload |

CRC 算法和覆盖规则与 FD frame 相同。

| command | request payload | 用途 |
|---:|---|---|
| `0x19` | `00` / `01` | 停止 / 启动 IMU 流 |
| `0x14` | 空 | 查询校准 blob 总长度 |
| `0x15` | 空 | 顺序读取下一段校准 blob |
| `0x1a` | 空 | IMU 同步 |

XBX A01 实测校准 blob 为 55,026 字节。读取时必须复用同一个 interface 1
claim 和同一个 `0x84` 异步 transfer，不能在初始化中途释放、重新 claim，或预提交
一批并行 URB。官方实现始终只维护一个 64 字节 URB，callback 完成后串行 resubmit。

## XBX 冷启动时序

以下顺序已经在手机重启、未安装/未运行官方服务的情况下由 Sensor Probe 独立验证：

1. claim interface 0，并准备接收 `0x82` response。
2. 依次发送 `0x26`, `0x57`, `0x12(1)`, `0x02(1)`, `0x34`, `0x35`。
3. 发送 `0x31`，payload 为 ASCII `3.1.1`。
4. 至少发送两次 MCU heartbeat `0x1a`，此后保持约 100 ms 周期。
5. 只有完成上述 MCU 初始化后，才 claim interface 1。
6. 在 `0x84` 提交一个 64 字节异步 interrupt transfer。
7. 发送 `0x19(0)` 停止旧 IMU 流。
8. `0x14` 查询校准长度，循环 `0x15` 读取完整校准 blob。
9. 发送 `0x1a` 同步。
10. 发送 `0x19(1)` 启动；继续 heartbeat，并在同一个 `0x84` URB 上接收报告。

两个容易被 ACK 掩盖的错误是：过早 claim interface 1，以及遗漏 `0x31 / 3.1.1`
握手。前者会破坏官方 MCU→IMU 的初始化时序；后者会出现 stop、校准、sync、start
全部 ACK，但眼镜仍不产生实时 IMU 报告。

## 64 字节 IMU report

有效报告以 `01 02` 开头：

| offset | size | 类型 | 字段 |
|---:|---:|---|---|
| 0 | 2 | bytes | magic `01 02` |
| 2 | 2 | i16 | 温度 raw |
| 4 | 8 | i64 | 设备时间戳，实测单位 ns |
| 12 | 2 | u16 | gyro scale numerator |
| 14 | 4 | i32 | gyro divisor |
| 18 | 9 | 3×i24 | gyro X/Y/Z |
| 27 | 2 | u16 | accel scale numerator |
| 29 | 4 | i32 | accel divisor |
| 33 | 9 | 3×i24 | accel X/Y/Z |
| 42 | 2 | i16 | magnetometer offset |
| 44 | 4 | i32 | magnetometer divisor |
| 48 | 6 | 3×i16 | magnetometer X/Y/Z |
| 54 | 10 | bytes | 状态/序号，尚未完整命名 |

换算与坐标变换：

```text
gyro_raw[i] = i24(18 + 3*i) * u16(12) / i32(14)       // deg/s
accel_raw[i] = i24(33 + 3*i) * u16(27) / i32(29)      // g
mag[i]       = (i16(48 + 2*i) - i16(42)) / i32(44)

gyro_android = (-X, Z, Y) * pi / 180                  // rad/s
accel_android = (-X, Z, Y) * 9.81                     // m/s²
temperature = i16(2) / 326.8 + 25                     // °C
```

设备时间戳应作为摄像头/IMU 时间对齐的原始时钟保存；不要用 Android 收包时间覆盖它。

官方 `libnr_service.so` 同时内嵌了 report version 1 和 version 2 schema，不能仅按
型号硬编码布局。version 1 同样是 64 字节，但 gyro/accel 是 i16：gyro 位于
18/20/22，accel 位于 30/32/34，mag 位于 42/44/46，sensor timestamp 位于 48；
version 2 使用上表的 i24 布局。Sensor Probe 现在读取 byte 1 后选择对应布局，缩放
分子和除数仍逐帧读取，因此兼容官方列出的 `0x20210601`、`0x20220101` 和
`0x20260323` 三套 schema。

## 证据范围

- XBX A01 的完整冷启动、校准读取、版本握手和实时 IMU：真机验证。
- FD/AA frame、CRC、命令顺序：官方 SO 静态分析与 Frida/libusb 动态抓包交叉验证。
- Flora/Hylla：官方 logical type、interface 和通用 AA 初始化链已对齐；真实固件仍需
  对应眼镜做最终运行验证，代码不会再把 logical type 相同误当成 Helen MCU 时序相同。
- `0x6cxx` 事件名称：官方实现和现有解析器映射，部分事件仍缺少对应硬件场景验证。
- 其他 XREAL 型号：仅确认官方设备表和接口映射；使用前应分别抓包验证。

实现位置：[`xreal_helen.cpp`](../app/src/main/cpp/xreal_helen.cpp)、
[`libusb_jni.cpp`](../app/src/main/cpp/libusb_jni.cpp) 和
[`ProtocolDecoders.kt`](../app/src/main/java/io/github/sensorprobe/ProtocolDecoders.kt)。

## Sensor Probe 数据流与 ownership

native 层刻意区分设备所有权和厂商协议状态：

```text
UsbDeviceConnection (Kotlin)
  └─ MultiSession owns native handle
       └─ ProbeUsb owns libusb_context + libusb_device_handle
            ├─ owns generic EndpointReader[]
            └─ owns optional XrealHelenSession
                 ├─ borrows ProbeUsb context/handle
                 ├─ owns endpoint 0x84 transfer + packet queue
                 └─ owns event-loop thread + heartbeat thread
```

数据方向如下：

```text
USB 0x84
  → libusb callback
  → XrealHelenSession bounded queue
  → readXrealHelen JNI
  → Kotlin Session reader thread
  → XbxA01Protocol.decode()
  → SensorReading/UI/log
```

`XrealHelenSession` 不得关闭 `libusb_device_handle` 或退出 context。资源只由父级
`ProbeUsb` 关闭。Kotlin `MultiSession.close()` 先停止所有消费线程，再调用 native
`close()`；native 的关闭顺序为：

1. 停止并 join heartbeat，保证不再写 `0x03/0x82`。
2. cancel 通用 endpoint readers。
3. 保持 Helen event loop 存活，直到 cancel callback 已分发。
4. cancel/free Helen `0x84` transfer 并销毁 Helen session。
5. free 通用 transfers。
6. 最后关闭 device handle、退出 libusb context、删除 `ProbeUsb`。

这个顺序避免 callback、Kotlin reader 或 heartbeat 在线程退出后继续访问已经释放的
handle/session。Helen 活跃时它的 event loop 是该 libusb context 唯一的事件循环，也会
分发同一 context 上的通用 endpoint callback，避免两个线程并发调用 libusb event API。
