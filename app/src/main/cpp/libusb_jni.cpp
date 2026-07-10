#include <jni.h>
#include <libusb.h>
#include <cstdint>
#include <vector>
#include <mutex>
#include <array>
#include <sstream>
#include <algorithm>
#include <atomic>
#include <condition_variable>
#include <deque>
#include <thread>
#include <chrono>
#include <memory>

struct ProbeUsb;
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

struct ProbeUsb {
    libusb_context* context = nullptr;
    libusb_device_handle* handle = nullptr;
    std::atomic<bool> helenRunning{false};
    std::atomic<bool> helenTransferActive{false};
    std::thread helenThread;
    libusb_transfer* helenTransfer = nullptr;
    std::array<unsigned char, 64> helenBuffer{};
    std::mutex helenMutex;
    std::condition_variable helenCondition;
    std::deque<std::vector<unsigned char>> helenQueue;
    std::atomic<bool> endpointLoopRunning{false};
    std::thread endpointThread;
    std::mutex endpointMutex;
    std::vector<std::unique_ptr<EndpointReader>> endpointReaders;
};

static ProbeUsb* from(jlong value) { return reinterpret_cast<ProbeUsb*>(static_cast<intptr_t>(value)); }

static void LIBUSB_CALL endpointTransferCallback(libusb_transfer* transfer) {
    auto* reader = static_cast<EndpointReader*>(transfer->user_data);
    if (transfer->status == LIBUSB_TRANSFER_COMPLETED && transfer->actual_length > 0) {
        std::vector<unsigned char> packet(transfer->buffer, transfer->buffer + transfer->actual_length);
        {
            std::lock_guard<std::mutex> lock(reader->mutex);
            if (reader->queue.size() >= 1024) reader->queue.pop_front();
            reader->queue.emplace_back(std::move(packet));
        }
        reader->condition.notify_all();
    }
    if (reader->owner->endpointLoopRunning && transfer->status != LIBUSB_TRANSFER_NO_DEVICE) {
        if (libusb_submit_transfer(transfer) != LIBUSB_SUCCESS) reader->active = false;
    } else { reader->active = false; reader->condition.notify_all(); }
}

static uint32_t crc32(const unsigned char* data, size_t size) {
    uint32_t crc = 0xffffffffu;
    for (size_t i = 0; i < size; ++i) {
        crc ^= data[i];
        for (int bit = 0; bit < 8; ++bit) crc = (crc >> 1) ^ (0xedb88320u & -(crc & 1u));
    }
    return ~crc;
}

static int interrupt(libusb_device_handle* handle, unsigned char endpoint,
                     unsigned char* data, int length, unsigned int timeout) {
    int actual = 0;
    const int rc = libusb_interrupt_transfer(handle, endpoint, data, length, &actual, timeout);
    return rc == LIBUSB_SUCCESS ? actual : rc;
}

static void LIBUSB_CALL helenTransferCallback(libusb_transfer* transfer) {
    auto* usb = static_cast<ProbeUsb*>(transfer->user_data);
    if (transfer->status == LIBUSB_TRANSFER_COMPLETED && transfer->actual_length > 0) {
        std::vector<unsigned char> packet(transfer->buffer, transfer->buffer + transfer->actual_length);
        {
            std::lock_guard<std::mutex> lock(usb->helenMutex);
            if (usb->helenQueue.size() >= 1024) usb->helenQueue.pop_front();
            usb->helenQueue.emplace_back(std::move(packet));
        }
        usb->helenCondition.notify_all();
    }
    if (usb->helenRunning && transfer->status != LIBUSB_TRANSFER_NO_DEVICE) {
        if (libusb_submit_transfer(transfer) != LIBUSB_SUCCESS) usb->helenTransferActive = false;
    } else usb->helenTransferActive = false;
}

static bool startHelenReceiver(ProbeUsb* usb) {
    usb->helenRunning = true;
    usb->helenTransfer = libusb_alloc_transfer(0);
    if (!usb->helenTransfer) { usb->helenRunning = false; return false; }
    libusb_fill_interrupt_transfer(usb->helenTransfer, usb->handle, 0x84, usb->helenBuffer.data(),
                                   usb->helenBuffer.size(), helenTransferCallback, usb, 0);
    usb->helenTransferActive = true;
    if (libusb_submit_transfer(usb->helenTransfer) != LIBUSB_SUCCESS) {
        usb->helenTransferActive = false;
        libusb_free_transfer(usb->helenTransfer); usb->helenTransfer = nullptr; usb->helenRunning = false; return false;
    }
    usb->helenThread = std::thread([usb] {
        while (usb->helenRunning || usb->helenTransferActive) {
            timeval timeout{0, 250000};
            libusb_handle_events_timeout_completed(usb->context, &timeout, nullptr);
        }
    });
    return true;
}

static std::vector<unsigned char> popHelen(ProbeUsb* usb, int timeoutMs) {
    std::unique_lock<std::mutex> lock(usb->helenMutex);
    usb->helenCondition.wait_for(lock, std::chrono::milliseconds(timeoutMs),
                                 [usb] { return !usb->helenQueue.empty() || !usb->helenRunning; });
    if (usb->helenQueue.empty()) return {};
    auto packet = std::move(usb->helenQueue.front());
    usb->helenQueue.pop_front();
    return packet;
}

static std::vector<unsigned char> aaPacket(unsigned char command,
                                           const std::vector<unsigned char>& data = {}) {
    const uint16_t bodyLength = static_cast<uint16_t>(3 + data.size());
    std::vector<unsigned char> packet(8 + data.size());
    packet[0] = 0xaa; packet[5] = bodyLength & 0xff; packet[6] = bodyLength >> 8; packet[7] = command;
    std::copy(data.begin(), data.end(), packet.begin() + 8);
    const uint32_t crc = crc32(packet.data() + 5, bodyLength);
    packet[1] = crc; packet[2] = crc >> 8; packet[3] = crc >> 16; packet[4] = crc >> 24;
    return packet;
}

static std::vector<unsigned char> fdPacket(uint16_t command, const std::vector<unsigned char>& data,
                                           uint32_t requestId) {
    const uint16_t bodyLength = static_cast<uint16_t>(17 + data.size());
    std::vector<unsigned char> packet(22 + data.size());
    packet[0] = 0xfd; packet[5] = bodyLength & 0xff; packet[6] = bodyLength >> 8;
    packet[7] = requestId; packet[8] = requestId >> 8; packet[9] = requestId >> 16; packet[10] = requestId >> 24;
    packet[15] = command; packet[16] = command >> 8;
    std::copy(data.begin(), data.end(), packet.begin() + 22);
    const uint32_t crc = crc32(packet.data() + 5, bodyLength);
    packet[1] = crc; packet[2] = crc >> 8; packet[3] = crc >> 16; packet[4] = crc >> 24;
    return packet;
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_sensorprobe_LibusbNative_open(JNIEnv*, jobject, jint fd) {
    auto* usb = new ProbeUsb();
    libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY, nullptr);
    if (libusb_init(&usb->context) != LIBUSB_SUCCESS ||
        libusb_wrap_sys_device(usb->context, static_cast<intptr_t>(fd), &usb->handle) != LIBUSB_SUCCESS) {
        if (usb->context) libusb_exit(usb->context);
        delete usb;
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(usb));
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_sensorprobe_LibusbNative_claim(JNIEnv*, jobject, jlong value, jint interfaceId) {
    auto* usb = from(value);
    if (!usb) return LIBUSB_ERROR_NO_DEVICE;
    if (libusb_kernel_driver_active(usb->handle, interfaceId) == 1) libusb_detach_kernel_driver(usb->handle, interfaceId);
    return libusb_claim_interface(usb->handle, interfaceId);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_sensorprobe_LibusbNative_release(JNIEnv*, jobject, jlong value, jint interfaceId) {
    auto* usb = from(value); return usb ? libusb_release_interface(usb->handle, interfaceId) : LIBUSB_ERROR_NO_DEVICE;
}

static jint transfer(JNIEnv* env, ProbeUsb* usb, jint endpoint, jbyteArray array, jint length, jint timeout, bool interrupt) {
    if (!usb || !array) return LIBUSB_ERROR_INVALID_PARAM;
    const jsize capacity = env->GetArrayLength(array); length = length < capacity ? length : capacity;
    std::vector<unsigned char> bytes(static_cast<size_t>(length));
    if ((endpoint & LIBUSB_ENDPOINT_DIR_MASK) == LIBUSB_ENDPOINT_OUT)
        env->GetByteArrayRegion(array, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
    int actual = 0;
    int rc = interrupt
        ? libusb_interrupt_transfer(usb->handle, static_cast<unsigned char>(endpoint), bytes.data(), length, &actual, timeout)
        : libusb_bulk_transfer(usb->handle, static_cast<unsigned char>(endpoint), bytes.data(), length, &actual, timeout);
    if (rc == LIBUSB_SUCCESS && (endpoint & LIBUSB_ENDPOINT_DIR_MASK) == LIBUSB_ENDPOINT_IN && actual > 0)
        env->SetByteArrayRegion(array, 0, actual, reinterpret_cast<const jbyte*>(bytes.data()));
    return rc == LIBUSB_SUCCESS ? actual : rc;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_sensorprobe_LibusbNative_interruptTransfer(JNIEnv* env, jobject, jlong value, jint endpoint, jbyteArray data, jint length, jint timeout) {
    return transfer(env, from(value), endpoint, data, length, timeout, true);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_sensorprobe_LibusbNative_bulkTransfer(JNIEnv* env, jobject, jlong value, jint endpoint, jbyteArray data, jint length, jint timeout) {
    return transfer(env, from(value), endpoint, data, length, timeout, false);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_sensorprobe_LibusbNative_controlTransfer(JNIEnv* env, jobject, jlong value, jint type, jint request, jint val, jint index, jbyteArray data, jint length, jint timeout) {
    auto* usb=from(value);if(!usb)return LIBUSB_ERROR_NO_DEVICE;
    std::vector<unsigned char> bytes(static_cast<size_t>(length));
    if(data && (type & LIBUSB_ENDPOINT_DIR_MASK)==LIBUSB_ENDPOINT_OUT)env->GetByteArrayRegion(data,0,length,reinterpret_cast<jbyte*>(bytes.data()));
    int rc=libusb_control_transfer(usb->handle,type,request,val,index,bytes.data(),length,timeout);
    if(rc>0 && data && (type & LIBUSB_ENDPOINT_DIR_MASK)==LIBUSB_ENDPOINT_IN)env->SetByteArrayRegion(data,0,rc,reinterpret_cast<const jbyte*>(bytes.data()));
    return rc;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_sensorprobe_LibusbNative_startEndpointReader(JNIEnv*, jobject, jlong value, jint endpoint,
                                                            jint transferType, jint packetSize) {
    auto* usb = from(value);
    if (!usb || !(endpoint & LIBUSB_ENDPOINT_IN) || packetSize <= 0) return LIBUSB_ERROR_INVALID_PARAM;
    std::lock_guard<std::mutex> readersLock(usb->endpointMutex);
    for (const auto& existing : usb->endpointReaders)
        if (existing->endpoint == static_cast<unsigned char>(endpoint)) return LIBUSB_SUCCESS;
    auto reader = std::make_unique<EndpointReader>();
    reader->owner = usb; reader->endpoint = static_cast<unsigned char>(endpoint);
    reader->interrupt = transferType == LIBUSB_TRANSFER_TYPE_INTERRUPT;
    reader->buffer.resize(static_cast<size_t>(std::max(packetSize, 64)));
    reader->transfer = libusb_alloc_transfer(0);
    if (!reader->transfer) return LIBUSB_ERROR_NO_MEM;
    if (reader->interrupt)
        libusb_fill_interrupt_transfer(reader->transfer, usb->handle, reader->endpoint, reader->buffer.data(),
                                       reader->buffer.size(), endpointTransferCallback, reader.get(), 0);
    else
        libusb_fill_bulk_transfer(reader->transfer, usb->handle, reader->endpoint, reader->buffer.data(),
                                  reader->buffer.size(), endpointTransferCallback, reader.get(), 0);
    reader->active = true;
    const int rc = libusb_submit_transfer(reader->transfer);
    if (rc != LIBUSB_SUCCESS) { libusb_free_transfer(reader->transfer); return rc; }
    usb->endpointReaders.emplace_back(std::move(reader));
    if (!usb->endpointLoopRunning.exchange(true)) {
        usb->endpointThread = std::thread([usb] {
            while (true) {
                bool active=false;
                { std::lock_guard<std::mutex> lock(usb->endpointMutex); active=std::any_of(usb->endpointReaders.begin(), usb->endpointReaders.end(),
                    [](const auto& r) { return r->active.load(); }); }
                if(!usb->endpointLoopRunning&&!active)break;
                timeval timeout{0, 250000};
                libusb_handle_events_timeout_completed(usb->context, &timeout, nullptr);
            }
        });
    }
    return LIBUSB_SUCCESS;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_sensorprobe_LibusbNative_readEndpoint(JNIEnv* env, jobject, jlong value, jint endpoint,
                                                      jbyteArray data, jint timeout) {
    auto* usb = from(value);
    if (!usb || !data) return LIBUSB_ERROR_INVALID_PARAM;
    EndpointReader* reader = nullptr;
    { std::lock_guard<std::mutex> lock(usb->endpointMutex);
      for (const auto& candidate : usb->endpointReaders)
          if (candidate->endpoint == static_cast<unsigned char>(endpoint)) { reader = candidate.get(); break; } }
    if (!reader) return LIBUSB_ERROR_NOT_FOUND;
    std::unique_lock<std::mutex> lock(reader->mutex);
    reader->condition.wait_for(lock, std::chrono::milliseconds(timeout),
                               [reader] { return !reader->queue.empty() || !reader->active; });
    if (reader->queue.empty()) return 0;
    auto packet = std::move(reader->queue.front()); reader->queue.pop_front(); lock.unlock();
    const jsize size = std::min<jsize>(env->GetArrayLength(data), static_cast<jsize>(packet.size()));
    env->SetByteArrayRegion(data, 0, size, reinterpret_cast<const jbyte*>(packet.data()));
    return size;
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_sensorprobe_LibusbNative_initializeXrealHelen(JNIEnv* env, jobject, jlong value) {
    auto* usb = from(value);
    if (!usb) return env->NewStringUTF("XREAL Helen JNI 初始化：无 libusb handle");

    std::array<bool, 6> mcuAck{};
    const std::array<uint16_t, 6> mcuCommands{0x26, 0x57, 0x12, 0x02, 0x34, 0x35};
    if (libusb_kernel_driver_active(usb->handle, 0) == 1) libusb_detach_kernel_driver(usb->handle, 0);
    const int mcuClaim = libusb_claim_interface(usb->handle, 0);
    if (mcuClaim == LIBUSB_SUCCESS) {
        for (size_t index = 0; index < mcuCommands.size(); ++index) {
            std::vector<unsigned char> body;
            if (mcuCommands[index] == 0x12 || mcuCommands[index] == 0x02) body = {1, 0, 0, 0};
            const uint32_t requestId = 0x48454c00u + static_cast<uint32_t>(index);
            auto packet = fdPacket(mcuCommands[index], body, requestId);
            if (interrupt(usb->handle, 0x03, packet.data(), packet.size(), 750) != static_cast<int>(packet.size())) continue;
            for (int attempt = 0; attempt < 8; ++attempt) {
                std::array<unsigned char, 64> response{};
                const int received = interrupt(usb->handle, 0x82, response.data(), response.size(), 400);
                if (received < 23 || response[0] != 0xfd) continue;
                const uint32_t rid = response[7] | (response[8] << 8) | (response[9] << 16) | (response[10] << 24);
                const uint16_t command = response[15] | (response[16] << 8);
                if (rid == requestId && command == mcuCommands[index]) {
                    mcuAck[index] = response[22] == 0;
                    break;
                }
            }
        }
        libusb_release_interface(usb->handle, 0);
    }

    bool stopAck = false, lengthAck = false, syncAck = false, startAck = false;
    int expectedConfig = 0, receivedConfig = 0;
    if (libusb_kernel_driver_active(usb->handle, 1) == 1) libusb_detach_kernel_driver(usb->handle, 1);
    const int imuClaim = libusb_claim_interface(usb->handle, 1);
    if (imuClaim == LIBUSB_SUCCESS) {
        startHelenReceiver(usb);
        auto command = [&](unsigned char id, const std::vector<unsigned char>& body = {}) {
            std::vector<unsigned char> empty;
            auto packet = aaPacket(id, body);
            if (interrupt(usb->handle, 0x05, packet.data(), packet.size(), 750) != static_cast<int>(packet.size())) return empty;
            for (int attempt = 0; attempt < 8; ++attempt) {
                auto response = popHelen(usb, 750);
                if (response.size() >= 8 && response[0] == 0xaa && response[7] == id) return response;
            }
            return empty;
        };

        stopAck = !command(0x19, {0}).empty();
        auto length = command(0x14);
        lengthAck = length.size() >= 12;
        if (lengthAck) expectedConfig = length[8] | (length[9] << 8) | (length[10] << 16) | (length[11] << 24);
        while (receivedConfig < expectedConfig && receivedConfig < 128 * 1024) {
            auto part = command(0x15);
            if (part.size() < 8) break;
            const int bytes = (part[5] | (part[6] << 8)) - 3;
            if (bytes <= 0) break;
            receivedConfig += bytes;
        }
        syncAck = !command(0x1a).empty();
        startAck = !command(0x19, {1}).empty();
    }

    std::ostringstream result;
    result << "XREAL Helen JNI/libusb 官方时序 · claim MCU=" << mcuClaim << " IMU=" << imuClaim << " · MCU ";
    for (size_t i = 0; i < mcuCommands.size(); ++i) {
        if (i) result << ", ";
        result << "0x" << std::hex << mcuCommands[i] << '=' << (mcuAck[i] ? "ACK" : "失败");
    }
    result << std::dec << " · IMU stop=" << (stopAck ? "ACK" : "失败")
           << ", length=" << (lengthAck ? "ACK" : "失败")
           << ", config=" << receivedConfig << '/' << expectedConfig
           << ", sync=" << (syncAck ? "ACK" : "失败")
           << ", start=" << (startAck ? "ACK" : "失败");
    return env->NewStringUTF(result.str().c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_sensorprobe_LibusbNative_readXrealHelen(JNIEnv* env, jobject, jlong value,
                                                        jbyteArray data, jint timeout) {
    auto* usb = from(value);
    if (!usb || !data || !usb->helenRunning) return LIBUSB_ERROR_NO_DEVICE;
    auto packet = popHelen(usb, timeout);
    if (packet.empty()) return 0;
    const jsize capacity = env->GetArrayLength(data);
    const jsize size = std::min<jsize>(capacity, static_cast<jsize>(packet.size()));
    env->SetByteArrayRegion(data, 0, size, reinterpret_cast<const jbyte*>(packet.data()));
    return size;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_sensorprobe_LibusbNative_close(JNIEnv*, jobject, jlong value) {
    auto* usb=from(value);if(!usb)return;
    usb->endpointLoopRunning=false;
    for(auto& reader:usb->endpointReaders){
        reader->condition.notify_all();
        if(reader->transfer&&reader->active)libusb_cancel_transfer(reader->transfer);
    }
    if(usb->endpointThread.joinable())usb->endpointThread.join();
    for(auto& reader:usb->endpointReaders)if(reader->transfer)libusb_free_transfer(reader->transfer);
    usb->endpointReaders.clear();
    usb->helenRunning=false;
    usb->helenCondition.notify_all();
    if(usb->helenTransfer)libusb_cancel_transfer(usb->helenTransfer);
    if(usb->helenThread.joinable())usb->helenThread.join();
    if(usb->helenTransfer){libusb_free_transfer(usb->helenTransfer);usb->helenTransfer=nullptr;}
    libusb_close(usb->handle);libusb_exit(usb->context);delete usb;
}
