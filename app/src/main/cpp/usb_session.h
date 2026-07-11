#pragma once

#include <jni.h>
#include <libusb.h>

#include <atomic>
#include <condition_variable>
#include <cstdio>
#include <cstdint>
#include <deque>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

struct ProbeUsb;
struct XrealHelenSession;

enum ProbeTraceKind : unsigned char {
    TRACE_INTERRUPT_SUBMIT = 11, TRACE_INTERRUPT_COMPLETE = 12,
    TRACE_BULK_SUBMIT = 21, TRACE_BULK_COMPLETE = 22,
    TRACE_CONTROL_SUBMIT = 31, TRACE_CONTROL_COMPLETE = 32,
    TRACE_ASYNC_SUBMIT = 41, TRACE_ASYNC_CALLBACK = 42,
    TRACE_ASYNC_RESUBMIT = 43, TRACE_ASYNC_CANCEL = 44,
    TRACE_CLAIM = 51, TRACE_RELEASE = 52
};

struct EndpointReader {
    ProbeUsb* owner = nullptr;
    unsigned char endpoint = 0;
    int interfaceId = -1;
    bool interrupt = true;
    std::atomic<bool> active{false};
    libusb_transfer* transfer = nullptr;
    std::vector<unsigned char> buffer;
    std::mutex mutex;
    std::condition_variable condition;
    std::deque<std::vector<unsigned char>> queue;
};

// Owns the wrapped libusb device and every child transfer session. Vendor
// sessions borrow context/handle; they never close either resource.
struct ProbeUsb {
    libusb_context* context = nullptr;
    libusb_device_handle* handle = nullptr;
    // Exclusive child session. Created/destroyed by xreal_helen.cpp; it only
    // borrows context and handle from this parent.
    XrealHelenSession* xrealHelen = nullptr;
    std::atomic<bool> endpointLoopRunning{false};
    std::thread endpointThread;
    std::mutex endpointMutex;
    std::vector<std::unique_ptr<EndpointReader>> endpointReaders;
    std::mutex traceMutex;
    FILE* traceFile = nullptr;
    std::atomic<uint64_t> traceSequence{0};
};

inline ProbeUsb* probeUsbFrom(jlong value) {
    return reinterpret_cast<ProbeUsb*>(static_cast<intptr_t>(value));
}

uint32_t probeCrc32(const unsigned char* data, size_t size);
void probeTrace(ProbeUsb* usb, unsigned char kind, unsigned char direction, int interfaceId,
                int endpoint, int status, int requested, const unsigned char* data, int actual);
int probeInterrupt(ProbeUsb* usb, int interfaceId, unsigned char endpoint,
                   unsigned char* data, int length, unsigned int timeout);
