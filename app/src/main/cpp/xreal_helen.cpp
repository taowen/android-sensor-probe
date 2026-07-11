#include "usb_session.h"
#include "xreal_helen.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <sstream>

struct XrealHelenSession {
    explicit XrealHelenSession(ProbeUsb* parent) : usb(parent) {}

    ProbeUsb* usb;  // Borrowed. ProbeUsb outlives this session.
    std::atomic<bool> running{false};
    std::atomic<bool> transferActive{false};
    libusb_transfer* transfer = nullptr;
    std::array<unsigned char, 64> buffer{};
    std::thread eventThread;
    std::mutex queueMutex;
    std::condition_variable queueCondition;
    std::deque<std::vector<unsigned char>> queue;
    std::atomic<bool> heartbeatRunning{false};
    std::thread heartbeatThread;
};

namespace {

XrealHelenSession* helen(ProbeUsb* usb) {
    return usb ? usb->xrealHelen : nullptr;
}

const XrealHelenSession* helen(const ProbeUsb* usb) {
    return usb ? usb->xrealHelen : nullptr;
}

void LIBUSB_CALL transferCallback(libusb_transfer* transfer) {
    auto* session = static_cast<XrealHelenSession*>(transfer->user_data);
    if (transfer->status == LIBUSB_TRANSFER_COMPLETED && transfer->actual_length > 0) {
        std::vector<unsigned char> packet(transfer->buffer, transfer->buffer + transfer->actual_length);
        {
            std::lock_guard<std::mutex> lock(session->queueMutex);
            if (session->queue.size() >= 1024) session->queue.pop_front();
            session->queue.emplace_back(std::move(packet));
        }
        session->queueCondition.notify_all();
    }
    if (session->running && transfer->status != LIBUSB_TRANSFER_NO_DEVICE) {
        if (libusb_submit_transfer(transfer) != LIBUSB_SUCCESS) session->transferActive = false;
    } else {
        session->transferActive = false;
        session->queueCondition.notify_all();
    }
}

bool startReceiver(XrealHelenSession* session) {
    session->running = true;
    session->transfer = libusb_alloc_transfer(0);
    if (!session->transfer) {
        session->running = false;
        return false;
    }
    libusb_fill_interrupt_transfer(session->transfer, session->usb->handle, 0x84,
                                   session->buffer.data(), session->buffer.size(), transferCallback,
                                   session, 5000);
    session->transferActive = true;
    session->eventThread = std::thread([session] {
        while (true) {
            bool genericTransferActive = false;
            {
                std::lock_guard<std::mutex> lock(session->usb->endpointMutex);
                genericTransferActive = std::any_of(
                    session->usb->endpointReaders.begin(), session->usb->endpointReaders.end(),
                    [](const auto& reader) { return reader->active.load(); });
            }
            if (!session->running && !session->transferActive && !genericTransferActive) break;
            timeval timeout{0, 250000};
            libusb_handle_events_timeout_completed(session->usb->context, &timeout, nullptr);
        }
    });
    if (libusb_submit_transfer(session->transfer) == LIBUSB_SUCCESS) return true;
    session->transferActive = false;
    session->running = false;
    if (session->eventThread.joinable()) session->eventThread.join();
    libusb_free_transfer(session->transfer);
    session->transfer = nullptr;
    return false;
}

std::vector<unsigned char> pop(XrealHelenSession* session, int timeoutMs) {
    std::unique_lock<std::mutex> lock(session->queueMutex);
    session->queueCondition.wait_for(lock, std::chrono::milliseconds(timeoutMs), [session] {
        return !session->queue.empty() || !session->running;
    });
    if (session->queue.empty()) return {};
    auto packet = std::move(session->queue.front());
    session->queue.pop_front();
    return packet;
}

std::vector<unsigned char> aaPacket(unsigned char command,
                                    const std::vector<unsigned char>& data = {}) {
    const uint16_t bodyLength = static_cast<uint16_t>(3 + data.size());
    std::vector<unsigned char> packet(8 + data.size());
    packet[0] = 0xaa;
    packet[5] = bodyLength & 0xff;
    packet[6] = bodyLength >> 8;
    packet[7] = command;
    std::copy(data.begin(), data.end(), packet.begin() + 8);
    const uint32_t crc = probeCrc32(packet.data() + 5, bodyLength);
    packet[1] = crc;
    packet[2] = crc >> 8;
    packet[3] = crc >> 16;
    packet[4] = crc >> 24;
    return packet;
}

std::vector<unsigned char> fdPacket(uint16_t command, const std::vector<unsigned char>& data,
                                    uint32_t requestId, uint32_t timestampLow) {
    const uint16_t bodyLength = static_cast<uint16_t>(17 + data.size());
    std::vector<unsigned char> packet(22 + data.size());
    packet[0] = 0xfd;
    packet[5] = bodyLength & 0xff;
    packet[6] = bodyLength >> 8;
    packet[7] = requestId;
    packet[8] = requestId >> 8;
    packet[9] = requestId >> 16;
    packet[10] = requestId >> 24;
    packet[11] = timestampLow;
    packet[12] = timestampLow >> 8;
    packet[13] = timestampLow >> 16;
    packet[14] = timestampLow >> 24;
    packet[15] = command;
    packet[16] = command >> 8;
    std::copy(data.begin(), data.end(), packet.begin() + 22);
    const uint32_t crc = probeCrc32(packet.data() + 5, bodyLength);
    packet[1] = crc;
    packet[2] = crc >> 8;
    packet[3] = crc >> 16;
    packet[4] = crc >> 24;
    return packet;
}

uint64_t monotonicNanos() {
    return static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count());
}

bool readFdAck(ProbeUsb* usb, uint32_t requestId, uint16_t command, int timeoutMs) {
    for (int attempt = 0; attempt < 8; ++attempt) {
        std::array<unsigned char, 64> response{};
        const int count = probeInterrupt(usb->handle, 0x82, response.data(), response.size(), timeoutMs);
        if (count < 23 || response[0] != 0xfd) continue;
        const uint32_t responseId = response[7] | (response[8] << 8) |
            (response[9] << 16) | (response[10] << 24);
        const uint16_t responseCommand = response[15] | (response[16] << 8);
        if (responseId == requestId && responseCommand == command) return response[22] == 0;
    }
    return false;
}

}  // namespace

bool xrealHelenRunning(const ProbeUsb* usb) {
    const auto* session = helen(usb);
    return session && session->running;
}

void xrealHelenStopHeartbeat(ProbeUsb* usb) {
    auto* session = helen(usb);
    if (!session) return;
    session->heartbeatRunning = false;
    if (session->heartbeatThread.joinable()) session->heartbeatThread.join();
}

void xrealHelenDestroy(ProbeUsb* usb) {
    auto* session = helen(usb);
    if (!session) return;
    xrealHelenStopHeartbeat(usb);
    session->running = false;
    session->queueCondition.notify_all();
    if (session->transfer && session->transferActive) libusb_cancel_transfer(session->transfer);
    if (session->eventThread.joinable()) session->eventThread.join();
    if (session->transfer) libusb_free_transfer(session->transfer);
    delete session;
    usb->xrealHelen = nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_sensorprobe_LibusbNative_startXrealHelenReceiver(JNIEnv* env, jobject, jlong value,
                                                                jboolean helenBootstrap) {
    auto* usb = probeUsbFrom(value);
    if (!usb) return env->NewStringUTF("XREAL Helen：无 libusb handle");
    xrealHelenDestroy(usb);
    auto* session = new XrealHelenSession(usb);
    usb->xrealHelen = session;

    int imuClaim = LIBUSB_ERROR_BUSY;
    int heartbeatBytes = LIBUSB_ERROR_BUSY;
    bool heartbeatAck = false;
    bool sdkVersionAck = false;
    int mcuInitAcks = 0;
    bool imuStop = false, imuLength = false, imuSync = false, imuStart = false;
    int imuExpected = 0, imuReceived = 0;

    bool mcuReady = !helenBootstrap;
    if (helenBootstrap) {
        if (libusb_kernel_driver_active(usb->handle, 0) == 1) libusb_detach_kernel_driver(usb->handle, 0);
        mcuReady = libusb_claim_interface(usb->handle, 0) == LIBUSB_SUCCESS;
    }
    if (mcuReady) {
      if (helenBootstrap) {
        const std::array<uint16_t, 6> commands{0x26, 0x57, 0x12, 0x02, 0x34, 0x35};
        for (size_t index = 0; index < commands.size(); ++index) {
            std::vector<unsigned char> body;
            if (commands[index] == 0x12 || commands[index] == 0x02) body = {1, 0, 0, 0};
            const uint32_t requestId = static_cast<uint32_t>(index + 1);
            const uint64_t stamp = monotonicNanos();
            auto packet = fdPacket(commands[index], body, requestId, static_cast<uint32_t>(stamp));
            if (probeInterrupt(usb->handle, 0x03, packet.data(), packet.size(), 500) ==
                    static_cast<int>(packet.size()) &&
                readFdAck(usb, requestId, commands[index], 250)) {
                ++mcuInitAcks;
            }
        }

        const uint64_t versionStamp = monotonicNanos();
        const std::vector<unsigned char> version{'3', '.', '1', '.', '1'};
        auto versionPacket = fdPacket(0x31, version, 7, static_cast<uint32_t>(versionStamp));
        sdkVersionAck = probeInterrupt(usb->handle, 0x03, versionPacket.data(), versionPacket.size(), 750) ==
                static_cast<int>(versionPacket.size()) && readFdAck(usb, 7, 0x31, 300);

        for (uint32_t requestId = 8; requestId <= 9; ++requestId) {
            const uint64_t stamp = monotonicNanos();
            std::vector<unsigned char> body(8);
            for (int i = 0; i < 8; ++i) body[i] = static_cast<unsigned char>(stamp >> (i * 8));
            auto packet = fdPacket(0x1a, body, requestId, static_cast<uint32_t>(stamp));
            heartbeatBytes = probeInterrupt(usb->handle, 0x03, packet.data(), packet.size(), 750);
            heartbeatAck = readFdAck(usb, requestId, 0x1a, 300) || heartbeatAck;
        }
      }

        if (libusb_kernel_driver_active(usb->handle, 1) == 1) libusb_detach_kernel_driver(usb->handle, 1);
        imuClaim = libusb_claim_interface(usb->handle, 1);
        if (imuClaim == LIBUSB_SUCCESS && startReceiver(session)) {
            auto imuCommand = [session](unsigned char command,
                                        const std::vector<unsigned char>& payload = std::vector<unsigned char>{}) {
                auto request = aaPacket(command, payload);
                if (probeInterrupt(session->usb->handle, 0x05, request.data(), request.size(), 750) !=
                    static_cast<int>(request.size())) return std::vector<unsigned char>{};
                for (int attempt = 0; attempt < 12; ++attempt) {
                    auto response = pop(session, 500);
                    if (response.size() >= 8 && response[0] == 0xaa && response[7] == command) return response;
                }
                return std::vector<unsigned char>{};
            };

            imuStop = !imuCommand(0x19, {0}).empty();
            auto length = imuCommand(0x14);
            imuLength = length.size() >= 12;
            if (imuLength) {
                imuExpected = length[8] | (length[9] << 8) | (length[10] << 16) | (length[11] << 24);
            }
            while (imuReceived < imuExpected && imuReceived < 128 * 1024) {
                auto part = imuCommand(0x15);
                if (part.size() < 8) break;
                const int bytes = (part[5] | (part[6] << 8)) - 3;
                if (bytes <= 0) break;
                imuReceived += bytes;
            }
            // Present in the official common transport. Older Air-family
            // implementations tolerate it; Helen requires it before start.
            imuSync = !imuCommand(0x1a).empty();
            imuStart = !imuCommand(0x19, {1}).empty();

            session->heartbeatRunning = helenBootstrap;
            if (helenBootstrap)
            session->heartbeatThread = std::thread([session] {
                uint32_t requestId = 10;
                while (session->heartbeatRunning) {
                    const uint64_t stamp = monotonicNanos();
                    std::vector<unsigned char> payload(8);
                    for (int i = 0; i < 8; ++i) payload[i] = static_cast<unsigned char>(stamp >> (i * 8));
                    auto packet = fdPacket(0x1a, payload, requestId++, static_cast<uint32_t>(stamp));
                    probeInterrupt(session->usb->handle, 0x03, packet.data(), packet.size(), 250);
                    std::array<unsigned char, 64> response{};
                    probeInterrupt(session->usb->handle, 0x82, response.data(), response.size(), 100);
                    std::this_thread::sleep_for(std::chrono::milliseconds(100));
                }
            });
        }
    }

    std::ostringstream result;
    result << "XREAL " << (helenBootstrap ? "Helen" : "kernel HID")
           << " 单 URB 官方队列 · claim IMU=" << imuClaim
           << " · endpoint 0x84 async=" << (session->running ? "已提交" : "失败")
           << " · MCU heartbeat=" << heartbeatBytes << "/30"
           << " ACK=" << (heartbeatAck ? "成功" : "失败")
           << " initACK=" << mcuInitAcks << "/6"
           << " sdk3.1.1=" << (sdkVersionAck ? "成功" : "失败")
           << " · IMU stop=" << imuStop << " length=" << imuLength
           << " config=" << imuReceived << '/' << imuExpected
           << " sync=" << imuSync << " start=" << imuStart;
    return env->NewStringUTF(result.str().c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_sensorprobe_LibusbNative_readXrealHelen(JNIEnv* env, jobject, jlong value,
                                                       jbyteArray data, jint timeout) {
    auto* session = helen(probeUsbFrom(value));
    if (!session || !data || !session->running) return LIBUSB_ERROR_NO_DEVICE;
    auto packet = pop(session, timeout);
    if (packet.empty()) return 0;
    const jsize size = std::min<jsize>(env->GetArrayLength(data), static_cast<jsize>(packet.size()));
    env->SetByteArrayRegion(data, 0, size, reinterpret_cast<const jbyte*>(packet.data()));
    return size;
}
