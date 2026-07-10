# Android Sensor Probe

一个通过 Android USB Host 授权和内置 libusb/JNI 探测 USB-C AR 眼镜的实验性 Android 应用。它用于识别眼镜型号、枚举 USB 接口、读取可用传感器，并展示外接相机或 OV580 双目 SLAM 原始画面。

项目尽量使用公开协议直接访问硬件，不打包厂商 SDK 中的大量预编译 `.so`。协议实现主要参考开源社区项目，尤其是 [badicsalex/ar-drivers-rs](https://github.com/badicsalex/ar-drivers-rs)。

## 当前能力

- 枚举 USB-C/OTG 设备、接口和 endpoint
- 识别 XREAL、VITURE、RayNeo、Rokid、Grawoow/MetaVision、Mad Gaze 等设备
- 解析已支持设备的加速度计、陀螺仪、磁力计、按键、接近和环境光报告
- 仅列出 Camera2 标记为 `LENS_FACING_EXTERNAL` 的摄像头，排除手机内置摄像头
- 所有厂商的 interrupt、bulk、control 和 OV580 传输统一经过 libusb；Android API 仅负责设备发现、授权和取得文件描述符
- 所有实时 IN endpoint 使用 JNI 中的常驻异步 libusb transfer、原生 event loop 和有界队列；Kotlin 不再执行 USB 收包循环
- 通过 JNI/libusb 读取 XREAL Light OV580 双目 SLAM 原始帧
- 对 XREAL Helen/XBX 执行官方 MCU 初始化序列和 IMU 校准读取/启动序列
- 向公开协议支持的设备发送 2D、Full SBS、Half SBS 和高刷新率显示模式命令

详细型号和能力矩阵见 [ar-drivers-rs 支持表](docs/ar-drivers-rs-support.md)和 [VITURE 官方 SDK 支持表](docs/viture-sdk-support.md)。

> [!WARNING]
> 本项目处于实验阶段。显示模式命令会直接改变眼镜状态；只应在确认型号后使用。XBX A01 的 MCU 初始化和显示模式封包来自对官方 Beam Pro APK 的实机验证，但连续 IMU 数据仍依赖 APK 外动态注入的 `NRImuStartExt` 实现，尚未打通。

## 构建

要求：

- JDK 17 或更高版本
- Android SDK 36
- Android NDK `29.0.14206865`
- CMake（由 Android SDK 管理器安装）

```bash
./gradlew assembleDebug
```

APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。

## 安装

普通 Android 设备可以直接使用 ADB：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

项目也保留了针对 vivo X300 / OriginOS 安装确认界面的辅助脚本：

```bash
ADB_SERIAL=192.168.1.60:33491 scripts/install-debug.sh
```

该脚本通过固定坐标点击系统安装确认，仅适用于对应分辨率和系统版本。其他设备请使用普通 `adb install`。

## 代码结构

- `Models.kt`：USB VID/PID 和型号能力表
- `ProtocolDecoders.kt`：传感器与 MCU 报告解析
- `UsbGlassesReader.kt`：USB Host 会话和显示模式控制
- `LibusbNative.kt`、`cpp/libusb_jni.cpp`：Android 文件描述符到 libusb 的统一传输层、异步 endpoint reader、Helen 官方时序
- `ExternalCameraPreview.kt`：外接 Camera2 预览
- `Ov580Native.kt`、`cpp/ov580_jni.cpp`：OV580 双目 SLAM JNI 读取链路
- `cpp/libusb/`：内置 libusb 1.0.29 源码（静态链接进项目自己的 JNI 库）
- `scripts/`：构建与安装辅助脚本

## 验证状态

当前实机主要验证设备为 XBX A01 (`3318:0440`)。libusb 可打开并 claim 三个 HID 接口；MCU 官方启动序列实测 6/6 条收到 ACK，55,026 字节校准配置可完整读取，IMU stop、同步和 start 也均收到 ACK。眼镜仍不主动上报 IMU 帧，当前证据指向 Beam Pro 系统组件提供、但官方 APK 内不存在的 `NRImuStartExt`。其他型号的实现来自公开协议和开源驱动移植，仍需要对应硬件逐项验证。
