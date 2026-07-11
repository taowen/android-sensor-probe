#pragma once

#include <jni.h>

struct ProbeUsb;

bool xrealHelenRunning(const ProbeUsb* usb);
void xrealHelenStopHeartbeat(ProbeUsb* usb);
void xrealHelenDestroy(ProbeUsb* usb);

