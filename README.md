# Android Sensor Probe

一个直接通过 Android USB Host API（以及少量 JNI）探测 USB-C AR 眼镜的实验性 Android 应用。它用于识别眼镜型号、枚举 USB 接口、读取可用传感器，并展示外接相机或 OV580 双目 SLAM 原始画面。

项目尽量使用公开协议直接访问硬件，不打包厂商 SDK 中的大量预编译 `.so`。协议实现主要参考开源社区项目，尤其是 [badicsalex/ar-drivers-rs](https://github.com/badicsalex/ar-drivers-rs)。

## 当前能力

- 枚举 USB-C/OTG 设备、接口和 endpoint
- 识别 XREAL、VITURE、RayNeo、Rokid、Grawoow/MetaVision、Mad Gaze 等设备
- 解析已支持设备的加速度计、陀螺仪、磁力计、按键、接近和环境光报告
- 仅列出 Camera2 标记为 `LENS_FACING_EXTERNAL` 的摄像头，排除手机内置摄像头
- 通过 JNI 和 Linux USBDEVFS 读取 XREAL Light OV580 双目 SLAM 原始帧
- 向公开协议支持的设备发送 2D、Full SBS、Half SBS 和高刷新率显示模式命令

详细型号和能力矩阵见 [docs/ar-drivers-rs-support.md](docs/ar-drivers-rs-support.md)。

> [!WARNING]
> 本项目处于实验阶段。显示模式命令会直接改变眼镜状态；只应在确认型号后使用。XBX a01 虽可识别，但其 MCU/显示模式私有协议尚无公开依据，因此应用不会向它发送 XREAL Air 的模式命令。

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
- `ExternalCameraPreview.kt`：外接 Camera2 预览
- `Ov580Native.kt`、`cpp/ov580_jni.cpp`：OV580 双目 SLAM JNI 读取链路
- `scripts/`：构建与安装辅助脚本

## 验证状态

当前实机主要验证设备为 XBX a01 (`3318:0440`)。其他型号的实现来自公开协议和开源驱动移植，仍需要对应硬件逐项验证。欢迎提交 USB 描述符、原始报告样本和兼容性修复。

