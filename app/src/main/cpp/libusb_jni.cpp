#include "usb_session.h"
#include "xreal_helen.h"

#include <algorithm>

namespace {

void LIBUSB_CALL endpointTransferCallback(libusb_transfer* transfer) {
    auto* reader = static_cast<EndpointReader*>(transfer->user_data);
    if (transfer->status == LIBUSB_TRANSFER_COMPLETED && transfer->actual_length > 0) {
        std::vector<unsigned char> packet(transfer->buffer,
                                          transfer->buffer + transfer->actual_length);
        {
            std::lock_guard<std::mutex> lock(reader->mutex);
            if (reader->queue.size() >= 1024) reader->queue.pop_front();
            reader->queue.emplace_back(std::move(packet));
        }
        reader->condition.notify_all();
    }
    if (reader->owner->endpointLoopRunning && transfer->status != LIBUSB_TRANSFER_NO_DEVICE) {
        if (libusb_submit_transfer(transfer) != LIBUSB_SUCCESS) reader->active = false;
    } else {
        reader->active = false;
        reader->condition.notify_all();
    }
}

jint transfer(JNIEnv* env, ProbeUsb* usb, jint endpoint, jbyteArray array,
              jint length, jint timeout, bool interrupt) {
    if (!usb || !array) return LIBUSB_ERROR_INVALID_PARAM;
    length = std::min(length, env->GetArrayLength(array));
    std::vector<unsigned char> bytes(static_cast<size_t>(length));
    if ((endpoint & LIBUSB_ENDPOINT_DIR_MASK) == LIBUSB_ENDPOINT_OUT) {
        env->GetByteArrayRegion(array, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
    }
    int actual = 0;
    const int result = interrupt
        ? libusb_interrupt_transfer(usb->handle, static_cast<unsigned char>(endpoint),
                                    bytes.data(), length, &actual, timeout)
        : libusb_bulk_transfer(usb->handle, static_cast<unsigned char>(endpoint),
                               bytes.data(), length, &actual, timeout);
    if (result == LIBUSB_SUCCESS &&
        (endpoint & LIBUSB_ENDPOINT_DIR_MASK) == LIBUSB_ENDPOINT_IN && actual > 0) {
        env->SetByteArrayRegion(array, 0, actual, reinterpret_cast<const jbyte*>(bytes.data()));
    }
    return result == LIBUSB_SUCCESS ? actual : result;
}

}  // namespace

uint32_t probeCrc32(const unsigned char* data, size_t size) {
    uint32_t crc = 0xffffffffu;
    for (size_t i = 0; i < size; ++i) {
        crc ^= data[i];
        for (int bit = 0; bit < 8; ++bit) {
            crc = (crc >> 1) ^ (0xedb88320u & -(crc & 1u));
        }
    }
    return ~crc;
}

int probeInterrupt(libusb_device_handle* handle, unsigned char endpoint,
                   unsigned char* data, int length, unsigned int timeout) {
    int actual = 0;
    const int result = libusb_interrupt_transfer(handle, endpoint, data, length, &actual, timeout);
    return result == LIBUSB_SUCCESS ? actual : result;
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_sensorprobe_LibusbNative_open(JNIEnv*, jobject, jint fd) {
    auto usb = std::make_unique<ProbeUsb>();
    libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY, nullptr);
    if (libusb_init(&usb->context) != LIBUSB_SUCCESS ||
        libusb_wrap_sys_device(usb->context, static_cast<intptr_t>(fd), &usb->handle) !=
            LIBUSB_SUCCESS) {
        if (usb->context) libusb_exit(usb->context);
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(usb.release()));
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_sensorprobe_LibusbNative_claim(JNIEnv*, jobject, jlong value, jint interfaceId) {
    auto* usb = probeUsbFrom(value);
    if (!usb) return LIBUSB_ERROR_NO_DEVICE;
    if (libusb_kernel_driver_active(usb->handle, interfaceId) == 1) {
        libusb_detach_kernel_driver(usb->handle, interfaceId);
    }
    return libusb_claim_interface(usb->handle, interfaceId);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_sensorprobe_LibusbNative_release(JNIEnv*, jobject, jlong value,
                                                jint interfaceId) {
    auto* usb = probeUsbFrom(value);
    return usb ? libusb_release_interface(usb->handle, interfaceId) : LIBUSB_ERROR_NO_DEVICE;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_sensorprobe_LibusbNative_interruptTransfer(
    JNIEnv* env, jobject, jlong value, jint endpoint, jbyteArray data, jint length, jint timeout) {
    return transfer(env, probeUsbFrom(value), endpoint, data, length, timeout, true);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_sensorprobe_LibusbNative_bulkTransfer(
    JNIEnv* env, jobject, jlong value, jint endpoint, jbyteArray data, jint length, jint timeout) {
    return transfer(env, probeUsbFrom(value), endpoint, data, length, timeout, false);
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_sensorprobe_LibusbNative_controlTransfer(
    JNIEnv* env, jobject, jlong value, jint type, jint request, jint controlValue, jint index,
    jbyteArray data, jint length, jint timeout) {
    auto* usb = probeUsbFrom(value);
    if (!usb) return LIBUSB_ERROR_NO_DEVICE;
    std::vector<unsigned char> bytes(static_cast<size_t>(length));
    if (data && (type & LIBUSB_ENDPOINT_DIR_MASK) == LIBUSB_ENDPOINT_OUT) {
        env->GetByteArrayRegion(data, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
    }
    const int result = libusb_control_transfer(usb->handle, type, request, controlValue, index,
                                               bytes.data(), length, timeout);
    if (result > 0 && data && (type & LIBUSB_ENDPOINT_DIR_MASK) == LIBUSB_ENDPOINT_IN) {
        env->SetByteArrayRegion(data, 0, result, reinterpret_cast<const jbyte*>(bytes.data()));
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_sensorprobe_LibusbNative_startEndpointReader(
    JNIEnv*, jobject, jlong value, jint endpoint, jint transferType, jint packetSize) {
    auto* usb = probeUsbFrom(value);
    if (!usb || !(endpoint & LIBUSB_ENDPOINT_IN) || packetSize <= 0) {
        return LIBUSB_ERROR_INVALID_PARAM;
    }
    std::lock_guard<std::mutex> readersLock(usb->endpointMutex);
    for (const auto& existing : usb->endpointReaders) {
        if (existing->endpoint == static_cast<unsigned char>(endpoint)) return LIBUSB_SUCCESS;
    }

    auto reader = std::make_unique<EndpointReader>();
    reader->owner = usb;
    reader->endpoint = static_cast<unsigned char>(endpoint);
    reader->interrupt = transferType == LIBUSB_TRANSFER_TYPE_INTERRUPT;
    reader->buffer.resize(static_cast<size_t>(std::max(packetSize, 64)));
    reader->transfer = libusb_alloc_transfer(0);
    if (!reader->transfer) return LIBUSB_ERROR_NO_MEM;
    if (reader->interrupt) {
        libusb_fill_interrupt_transfer(reader->transfer, usb->handle, reader->endpoint,
                                       reader->buffer.data(), reader->buffer.size(),
                                       endpointTransferCallback, reader.get(), 0);
    } else {
        libusb_fill_bulk_transfer(reader->transfer, usb->handle, reader->endpoint,
                                  reader->buffer.data(), reader->buffer.size(),
                                  endpointTransferCallback, reader.get(), 0);
    }
    reader->active = true;
    const int result = libusb_submit_transfer(reader->transfer);
    if (result != LIBUSB_SUCCESS) {
        libusb_free_transfer(reader->transfer);
        return result;
    }
    usb->endpointReaders.emplace_back(std::move(reader));

    // Helen owns the only libusb event loop while active. It also dispatches
    // callbacks for generic readers sharing this context.
    if (xrealHelenRunning(usb)) {
        usb->endpointLoopRunning = true;
    } else if (!usb->endpointLoopRunning.exchange(true)) {
        usb->endpointThread = std::thread([usb] {
            while (true) {
                bool active = false;
                {
                    std::lock_guard<std::mutex> lock(usb->endpointMutex);
                    active = std::any_of(usb->endpointReaders.begin(), usb->endpointReaders.end(),
                                         [](const auto& item) { return item->active.load(); });
                }
                if (!usb->endpointLoopRunning && !active) break;
                timeval timeout{0, 250000};
                libusb_handle_events_timeout_completed(usb->context, &timeout, nullptr);
            }
        });
    }
    return LIBUSB_SUCCESS;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_sensorprobe_LibusbNative_readEndpoint(
    JNIEnv* env, jobject, jlong value, jint endpoint, jbyteArray data, jint timeout) {
    auto* usb = probeUsbFrom(value);
    if (!usb || !data) return LIBUSB_ERROR_INVALID_PARAM;
    EndpointReader* reader = nullptr;
    {
        std::lock_guard<std::mutex> lock(usb->endpointMutex);
        for (const auto& candidate : usb->endpointReaders) {
            if (candidate->endpoint == static_cast<unsigned char>(endpoint)) {
                reader = candidate.get();
                break;
            }
        }
    }
    if (!reader) return LIBUSB_ERROR_NOT_FOUND;
    std::unique_lock<std::mutex> lock(reader->mutex);
    reader->condition.wait_for(lock, std::chrono::milliseconds(timeout), [reader] {
        return !reader->queue.empty() || !reader->active;
    });
    if (reader->queue.empty()) return 0;
    auto packet = std::move(reader->queue.front());
    reader->queue.pop_front();
    lock.unlock();
    const jsize size = std::min<jsize>(env->GetArrayLength(data), packet.size());
    env->SetByteArrayRegion(data, 0, size, reinterpret_cast<const jbyte*>(packet.data()));
    return size;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_sensorprobe_LibusbNative_close(JNIEnv*, jobject, jlong value) {
    auto* usb = probeUsbFrom(value);
    if (!usb) return;

    // Stop writers before cancelling transfers. Helen's event thread remains
    // alive long enough to dispatch cancellation callbacks for every reader.
    xrealHelenStopHeartbeat(usb);
    usb->endpointLoopRunning = false;
    for (auto& reader : usb->endpointReaders) {
        reader->condition.notify_all();
        if (reader->transfer && reader->active) libusb_cancel_transfer(reader->transfer);
    }
    if (usb->endpointThread.joinable()) usb->endpointThread.join();
    xrealHelenDestroy(usb);
    for (auto& reader : usb->endpointReaders) {
        if (reader->transfer) libusb_free_transfer(reader->transfer);
    }
    usb->endpointReaders.clear();
    libusb_close(usb->handle);
    libusb_exit(usb->context);
    delete usb;
}
