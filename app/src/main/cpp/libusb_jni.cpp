#include "usb_session.h"
#include "xreal_helen.h"

#include <algorithm>

namespace {

uint64_t traceNanos() {
    return static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count());
}

void LIBUSB_CALL endpointTransferCallback(libusb_transfer* transfer) {
    auto* reader = static_cast<EndpointReader*>(transfer->user_data);
    probeTrace(reader->owner, TRACE_ASYNC_CALLBACK, 1, reader->interfaceId,
               reader->endpoint, transfer->status, transfer->length, transfer->buffer,
               transfer->status == LIBUSB_TRANSFER_COMPLETED ? transfer->actual_length : 0);
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
        const int result=libusb_submit_transfer(transfer);
        probeTrace(reader->owner,TRACE_ASYNC_RESUBMIT,1,reader->interfaceId,reader->endpoint,
                   result,transfer->length,nullptr,0);
        if (result != LIBUSB_SUCCESS) reader->active = false;
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
    const bool input=(endpoint & LIBUSB_ENDPOINT_DIR_MASK)!=0;
    probeTrace(usb,interrupt?TRACE_INTERRUPT_SUBMIT:TRACE_BULK_SUBMIT,input?1:0,-1,
               endpoint,0,length,input?nullptr:bytes.data(),input?0:length);
    const int result = interrupt
        ? libusb_interrupt_transfer(usb->handle, static_cast<unsigned char>(endpoint),
                                    bytes.data(), length, &actual, timeout)
        : libusb_bulk_transfer(usb->handle, static_cast<unsigned char>(endpoint),
                               bytes.data(), length, &actual, timeout);
    probeTrace(usb, interrupt ? TRACE_INTERRUPT_COMPLETE : TRACE_BULK_COMPLETE,
               input ? 1 : 0, -1, endpoint, result, length,
               input ? bytes.data() : nullptr, result == LIBUSB_SUCCESS && input ? actual : 0);
    if (result == LIBUSB_SUCCESS &&
        (endpoint & LIBUSB_ENDPOINT_DIR_MASK) == LIBUSB_ENDPOINT_IN && actual > 0) {
        env->SetByteArrayRegion(array, 0, actual, reinterpret_cast<const jbyte*>(bytes.data()));
    }
    return result == LIBUSB_SUCCESS ? actual : result;
}

}  // namespace

void probeTrace(ProbeUsb* usb, unsigned char kind, unsigned char direction, int interfaceId,
                int endpoint, int status, int requested, const unsigned char* data, int actual) {
    if (!usb || !usb->traceFile) return;
    std::lock_guard<std::mutex> lock(usb->traceMutex);
    if (!usb->traceFile) return;
    const uint32_t magic = 0x52545053; // "SPTR" little endian
    const uint16_t version = 1;
    const uint64_t now = traceNanos();
    const uint64_t sequence = usb->traceSequence++;
    const int16_t intf = static_cast<int16_t>(interfaceId), ep = static_cast<int16_t>(endpoint);
    const int32_t result = status;
    const uint32_t req = std::max(0, requested), count = std::max(0, actual);
    fwrite(&magic, sizeof(magic), 1, usb->traceFile);fwrite(&version,sizeof(version),1,usb->traceFile);
    fwrite(&kind,1,1,usb->traceFile);fwrite(&direction,1,1,usb->traceFile);
    fwrite(&now,sizeof(now),1,usb->traceFile);fwrite(&sequence,sizeof(sequence),1,usb->traceFile);
    fwrite(&intf,sizeof(intf),1,usb->traceFile);fwrite(&ep,sizeof(ep),1,usb->traceFile);
    fwrite(&result,sizeof(result),1,usb->traceFile);fwrite(&req,sizeof(req),1,usb->traceFile);
    fwrite(&count,sizeof(count),1,usb->traceFile);if(count && data)fwrite(data,1,count,usb->traceFile);
}

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

int probeInterrupt(ProbeUsb* usb, int interfaceId, unsigned char endpoint,
                   unsigned char* data, int length, unsigned int timeout) {
    int actual = 0;
    const bool input=(endpoint & LIBUSB_ENDPOINT_DIR_MASK)!=0;
    probeTrace(usb,TRACE_INTERRUPT_SUBMIT,input?1:0,interfaceId,endpoint,0,length,
               input?nullptr:data,input?0:length);
    const int result = libusb_interrupt_transfer(usb->handle, endpoint, data, length, &actual, timeout);
    probeTrace(usb,TRACE_INTERRUPT_COMPLETE,input?1:0,interfaceId,endpoint,result,length,
               input?data:nullptr,result==LIBUSB_SUCCESS&&input?actual:0);
    return result == LIBUSB_SUCCESS ? actual : result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_sensorprobe_LibusbNative_startTrace(JNIEnv* env, jobject, jlong value, jstring path) {
    auto* usb=probeUsbFrom(value);if(!usb||!path)return JNI_FALSE;
    const char* chars=env->GetStringUTFChars(path,nullptr);
    std::lock_guard<std::mutex> lock(usb->traceMutex);
    if(usb->traceFile)fclose(usb->traceFile);
    usb->traceFile=fopen(chars,"wb");usb->traceSequence=0;
    env->ReleaseStringUTFChars(path,chars);return usb->traceFile?JNI_TRUE:JNI_FALSE;
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
    const int result=libusb_claim_interface(usb->handle, interfaceId);
    probeTrace(usb,TRACE_CLAIM,0,interfaceId,0,result,0,nullptr,0);return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_sensorprobe_LibusbNative_release(JNIEnv*, jobject, jlong value,
                                                jint interfaceId) {
    auto* usb = probeUsbFrom(value);
    if(!usb)return LIBUSB_ERROR_NO_DEVICE;const int result=libusb_release_interface(usb->handle,interfaceId);
    probeTrace(usb,TRACE_RELEASE,0,interfaceId,0,result,0,nullptr,0);return result;
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
    const bool input=(type & LIBUSB_ENDPOINT_DIR_MASK)!=0;
    probeTrace(usb,TRACE_CONTROL_SUBMIT,input?1:0,index,0,0,length,input?nullptr:bytes.data(),input?0:length);
    const int result = libusb_control_transfer(usb->handle, type, request, controlValue, index,
                                               bytes.data(), length, timeout);
    probeTrace(usb,TRACE_CONTROL_COMPLETE,input?1:0,index,0,result,length,input?bytes.data():nullptr,
               result>0&&input?result:0);
    if (result > 0 && data && (type & LIBUSB_ENDPOINT_DIR_MASK) == LIBUSB_ENDPOINT_IN) {
        env->SetByteArrayRegion(data, 0, result, reinterpret_cast<const jbyte*>(bytes.data()));
    }
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_io_github_sensorprobe_LibusbNative_startEndpointReader(
    JNIEnv*, jobject, jlong value, jint interfaceId, jint endpoint, jint transferType, jint packetSize) {
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
    reader->interfaceId = interfaceId;
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
    probeTrace(usb,TRACE_ASYNC_SUBMIT,1,interfaceId,endpoint,0,reader->buffer.size(),nullptr,0);
    const int result = libusb_submit_transfer(reader->transfer);
    if(result!=LIBUSB_SUCCESS)probeTrace(usb,TRACE_ASYNC_CALLBACK,1,interfaceId,endpoint,result,reader->buffer.size(),nullptr,0);
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
        if (reader->transfer && reader->active) {const int result=libusb_cancel_transfer(reader->transfer);probeTrace(usb,TRACE_ASYNC_CANCEL,1,reader->interfaceId,reader->endpoint,result,reader->transfer->length,nullptr,0);}
    }
    if (usb->endpointThread.joinable()) usb->endpointThread.join();
    xrealHelenDestroy(usb);
    for (auto& reader : usb->endpointReaders) {
        if (reader->transfer) libusb_free_transfer(reader->transfer);
    }
    usb->endpointReaders.clear();
    { std::lock_guard<std::mutex> lock(usb->traceMutex);if(usb->traceFile){fflush(usb->traceFile);fclose(usb->traceFile);usb->traceFile=nullptr;} }
    libusb_close(usb->handle);
    libusb_exit(usb->context);
    delete usb;
}
