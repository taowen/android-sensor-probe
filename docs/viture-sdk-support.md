# VITURE 官方 SDK 支持矩阵

本表来自 `VITURE XR Glasses SDK for Android` 2.3.2 的公开头文件、官方 demo USB filter，以及 `libglasses.so` 的 `xr_device_provider_get_market_name()` 和产品校验表。

| 产品 | 眼镜 PID（VID `35CA`） | SDK 类型 | 传感器/跟踪 | 配套相机 |
|---|---|---|---|---|
| One | `1011`, `1013`, `1017` | Gen1 | 3DoF pose，陀螺仪、加速度计，VSync | 无 |
| Lite | `1015`, `101B` | Gen1 | 3DoF pose，陀螺仪、加速度计，VSync | 无 |
| Pro | `1019`, `101D` | Gen1 | 3DoF pose，陀螺仪、加速度计，VSync | 无 |
| Luma | `1131` | Gen2 | 3DoF pose，9 轴原始 IMU，VSync | 无 |
| Luma Pro | `1121`, `1141` | Gen2 | 3DoF pose，9 轴原始 IMU，VSync | `0C45:636B` |
| Luma Cyber | `1151` | Gen2 | 3DoF pose，9 轴原始 IMU，VSync | `0C45:636B` |
| Luma Ultra | `1101`, `1104` | Carina | 原生 3DoF/6DoF、IMU、VSync、四路灰度 SLAM | `0C45:636B` |
| Beast | `1201`, `1211` | Gen2 Native DOF | 眼镜端原生 3DoF、9 轴 IMU、VSync | 单目相机 `0C45:6368` |
| Pro 2 | `1301` | Gen2 | 3DoF pose、原始 IMU、VSync | SDK 未注明 |

SDK 的普通设备 raw callback 返回 10 个 float。One/Lite/Pro 的磁力计三项固定为零；Luma、Luma Pro 和 Beast 返回陀螺仪、加速度计、磁力计和温度。Carina 回调另行提供六轴 IMU、VSync、pose 和四路灰度相机帧。

Beast 的 Gen2 V2 协议由 Probe 直接实现，不依赖官方 `libglasses.so`。V2 包头为 `10 00`、little-endian message ID、payload 长度、16 位 payload 字节累加和；RAW IMU 使用 `0301 [02 02]`（120 Hz）。显示状态查询使用 `3140` 和 `3142`，2D/3D 设置使用 `0142 [31]` / `0142 [37]`。

Debug APK 提供 ADB receiver。先启动应用，再发送：

```bash
adb shell am broadcast -a io.github.sensorprobe.DEBUG_COMMAND -p io.github.sensorprobe --es command connect_beast
adb shell am broadcast -a io.github.sensorprobe.DEBUG_COMMAND -p io.github.sensorprobe --es command query_beast
adb shell am broadcast -a io.github.sensorprobe.DEBUG_COMMAND -p io.github.sensorprobe --es command set_2d
adb shell am broadcast -a io.github.sensorprobe.DEBUG_COMMAND -p io.github.sensorprobe --es command set_3d
```

该 receiver 仅打包进 Debug 变体；Release APK 不包含此入口。

`1301` 被 SDK 的产品校验和商品名函数正式接受并命名为 Pro 2，但未出现在官方 demo 较旧的 USB filter 中；探针按 SDK 库本身的能力将其收录。
