# Community diagnostic reports

Sensor Probe 0.2 starts recording before opening the selected USB device. Reconnect the glasses,
open Sensor Probe, then tap the glasses entry once. Do not start an official glasses application.

Recommended 30–60 second sequence:

1. Leave the glasses still for five seconds.
2. Rotate slowly around each of the three axes, one axis at a time.
3. Translate the glasses without rotating them.
4. Press every glasses button and cover/uncover the proximity sensor.
5. If a camera is present, move a high-contrast object through its view.
6. Only if desired, use the explicit 2D/3D buttons; these commands modify device state.
7. Tap **停止录制并生成报告**, then **分享最近的诊断 ZIP**.

The ZIP contains USB descriptors, phone/OS version, a monotonic event timeline, every native USB
IN/OUT transfer, raw XREAL Ethernet bytes, decoded IMU CSV, camera frame metadata, app logs and a
machine-readable summary. Camera images are not recorded. USB serial numbers are replaced by a
short SHA-256 prefix; Android ID, accounts, Wi-Fi details and phone numbers are not collected.

Inspect a returned report with:

```bash
python3 scripts/analyze-report.py sensor-probe-report-*.zip
```

`raw/usb-transfers.bin` is a sequence of little-endian SPTR v1 records. Its exact header and kind
dictionary are recorded in `report.json`. Synchronous interrupt, bulk and control operations have
separate submit and complete records. Asynchronous URBs record initial submit, callback, every
resubmit and cancellation request. This permits host-side latency analysis without pretending to
be a USB bus/SOF capture. Reports retain failed, unknown and malformed packets because these are
useful evidence for new protocols.
