#pragma once

#include <jni.h>
#include <libusb.h>

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

struct ProbeUsb;
struct XrealHelenSession;

struct EndpointReader {
    ProbeUsb* owner = nullptr;
    unsigned char endpoint = 0;
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
};

inline ProbeUsb* probeUsbFrom(jlong value) {
    return reinterpret_cast<ProbeUsb*>(static_cast<intptr_t>(value));
}

uint32_t probeCrc32(const unsigned char* data, size_t size);
int probeInterrupt(libusb_device_handle* handle, unsigned char endpoint,
                   unsigned char* data, int length, unsigned int timeout);
