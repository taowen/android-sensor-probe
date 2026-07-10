# ar-drivers-rs compatibility imported by Sensor Probe

Source: [badicsalex/ar-drivers-rs](https://github.com/badicsalex/ar-drivers-rs), MIT licensed.

| Product family | USB identity | Sensor/events exposed upstream | Camera capability | Android transport in Sensor Probe |
|---|---|---|---|---|
| XREAL Air | `3318:0424` | accel + gyro, magnetometer, keys | none | HID interrupt |
| XREAL Air 2 | `3318:0428` | accel + gyro, magnetometer, keys | none | HID interrupt |
| XREAL Air 2 Pro | `3318:0432` | accel + gyro, magnetometer, keys | none | HID interrupt |
| XREAL Air 2 Ultra | `3318:0426` | accel + gyro, magnetometer, keys | not implemented upstream | HID interrupt; USB interface inventory still reports UVC if exposed |
| XREAL Light MCU | `0486:573c` | keys, proximity, ambient light, VSync | camera descriptors come from OV580 calibration | MCU events and supported display modes |
| XREAL Light OV580 | `05a9:0680` | accel + gyro | RGB + left/right SLAM cameras | OV580 reports plus experimental JNI dual-SLAM capture |
| Rokid Air / Max | `04d2:162f` | accel + gyro, magnetometer, keys, proximity | none | interrupt endpoint `82` (or `83` on the upstream special PID) |
| Grawoow G530 / MetaVision M53 MCU | `1ff7:0ff4` | display/config control | none | MCU control transfers and 2D/stereo mode commands |
| Grawoow G530 / MetaVision M53 OV580 | `05a9:0f87` | accel + gyro | composite OV580 module | interrupt endpoint `89` |
| Mad Gaze Glow | `04b4:0002` | BMI160 accel + gyro, AK09911 magnetometer | none | experimental USB serial framing and 2D/stereo commands |

The upstream display controls include mirrored 2D, full SBS, half SBS and high-refresh modes where supported. Sensor Probe exposes these as explicit UI actions and never changes display mode during passive probing. Implementations ported from upstream still require testing on each physical model.
