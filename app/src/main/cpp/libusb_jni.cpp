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
    std::atomic<bool> helenHeartbeatRunning{false};
    std::thread helenHeartbeatThread;
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
    if (!usb->helenTransfer) {
        if (usb->helenTransfer) libusb_free_transfer(usb->helenTransfer);
        usb->helenTransfer=nullptr; usb->helenRunning = false; return false;
    }
    libusb_fill_interrupt_transfer(usb->helenTransfer, usb->handle, 0x84, usb->helenBuffer.data(),
                                   usb->helenBuffer.size(), helenTransferCallback, usb, 5000);
    usb->helenTransferActive = true;
    usb->helenThread = std::thread([usb] {
        while (usb->helenRunning || usb->helenTransferActive) {
            timeval timeout{0, 250000};
            libusb_handle_events_timeout_completed(usb->context, &timeout, nullptr);
        }
    });
    const bool submitFailed=libusb_submit_transfer(usb->helenTransfer)!=LIBUSB_SUCCESS;
    if (submitFailed) {
        usb->helenTransferActive = false;
        libusb_cancel_transfer(usb->helenTransfer);
        usb->helenRunning = false;
        if(usb->helenThread.joinable())usb->helenThread.join();
        return false;
    }
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
                                           uint32_t requestId, uint32_t timestampLow = 0) {
    const uint16_t bodyLength = static_cast<uint16_t>(17 + data.size());
    std::vector<unsigned char> packet(22 + data.size());
    packet[0] = 0xfd; packet[5] = bodyLength & 0xff; packet[6] = bodyLength >> 8;
    packet[7] = requestId; packet[8] = requestId >> 8; packet[9] = requestId >> 16; packet[10] = requestId >> 24;
    // Official Helen MCU packets carry the low 32 bits of the monotonic
    // timestamp in the envelope as well as the full timestamp in the body.
    packet[11] = timestampLow; packet[12] = timestampLow >> 8;
    packet[13] = timestampLow >> 16; packet[14] = timestampLow >> 24;
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
    // Helen's permanent IMU event thread services every transfer on this
    // libusb context, including MCU/event endpoints added after initialization.
    if (usb->helenRunning) {
        usb->endpointLoopRunning = true;
    } else if (!usb->endpointLoopRunning.exchange(true)) {
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
Java_io_github_sensorprobe_LibusbNative_startXrealHelenReceiver(JNIEnv* env, jobject, jlong value) {
    auto* usb = from(value);
    if (!usb) return env->NewStringUTF("XREAL Helen 被动接收：无 libusb handle");
    int claim = LIBUSB_ERROR_BUSY;
    bool receiver = false;
    int heartbeat = LIBUSB_ERROR_BUSY;
    bool heartbeatAck = false;
    bool sdkVersionAck = false;
    int mcuInitAcks = 0;
    bool imuStop=false,imuLength=false,imuSync=false,imuStart=false;
    int imuExpected=0,imuReceived=0;
    // Official NRService initializes MCU/interface 0 completely before it
    // claims the Helen IMU interface or submits the first endpoint 0x84 URB.
    if (libusb_kernel_driver_active(usb->handle, 0) == 1) libusb_detach_kernel_driver(usb->handle, 0);
    if (libusb_claim_interface(usb->handle, 0) == LIBUSB_SUCCESS) {
            const std::array<uint16_t,6> commands{0x26,0x57,0x12,0x02,0x34,0x35};
            for(size_t index=0;index<commands.size();++index){
                std::vector<unsigned char> initBody;
                if(commands[index]==0x12||commands[index]==0x02)initBody={1,0,0,0};
                const uint32_t rid=static_cast<uint32_t>(index+1);
                const uint64_t stamp=static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(
                    std::chrono::steady_clock::now().time_since_epoch()).count());
                auto initPacket=fdPacket(commands[index],initBody,rid,static_cast<uint32_t>(stamp));
                if(interrupt(usb->handle,0x03,initPacket.data(),initPacket.size(),500)!=static_cast<int>(initPacket.size()))continue;
                for(int attempt=0;attempt<8;++attempt){
                    std::array<unsigned char,64> response{};
                    const int n=interrupt(usb->handle,0x82,response.data(),response.size(),250);
                    if(n<23||response[0]!=0xfd)continue;
                    const uint32_t rr=response[7]|(response[8]<<8)|(response[9]<<16)|(response[10]<<24);
                    const uint16_t rc=response[15]|(response[16]<<8);
                    if(rr==rid&&rc==commands[index]){if(response[22]==0)++mcuInitAcks;break;}
                }
            }
            {
                const uint64_t stamp=static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(
                    std::chrono::steady_clock::now().time_since_epoch()).count());
                const std::vector<unsigned char> version{'3','.','1','.','1'};
                auto packet=fdPacket(0x31,version,7,static_cast<uint32_t>(stamp));
                if(interrupt(usb->handle,0x03,packet.data(),packet.size(),750)==static_cast<int>(packet.size())){
                    for(int attempt=0;attempt<8;++attempt){
                        std::array<unsigned char,64> response{};
                        const int n=interrupt(usb->handle,0x82,response.data(),response.size(),300);
                        if(n<23||response[0]!=0xfd)continue;
                        const uint32_t rr=response[7]|(response[8]<<8)|(response[9]<<16)|(response[10]<<24);
                        const uint16_t cmd=response[15]|(response[16]<<8);
                        if(rr==7&&cmd==0x31){sdkVersionAck=response[22]==0;break;}
                    }
                }
            }
            for(uint32_t rid=8;rid<=9;++rid){
                const uint64_t now=static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(
                    std::chrono::steady_clock::now().time_since_epoch()).count());
                std::vector<unsigned char> body(8);
                for(int i=0;i<8;++i)body[i]=static_cast<unsigned char>(now>>(i*8));
                auto packet=fdPacket(0x001a,body,rid,static_cast<uint32_t>(now));
                heartbeat=interrupt(usb->handle,0x03,packet.data(),packet.size(),750);
                for(int attempt=0;attempt<8;++attempt){
                    std::array<unsigned char,64> response{};
                    const int n=interrupt(usb->handle,0x82,response.data(),response.size(),300);
                    if(n<23||response[0]!=0xfd)continue;
                    const uint32_t rr=response[7]|(response[8]<<8)|(response[9]<<16)|(response[10]<<24);
                    const uint16_t cmd=response[15]|(response[16]<<8);
                    if(rr==rid&&cmd==0x001a&&response[22]==0){heartbeatAck=true;break;}
                }
            }
            if (libusb_kernel_driver_active(usb->handle,1)==1)libusb_detach_kernel_driver(usb->handle,1);
            claim=libusb_claim_interface(usb->handle,1);
            receiver=claim==LIBUSB_SUCCESS&&startHelenReceiver(usb);
            if(receiver){
            auto imuCommand=[&](unsigned char id,const std::vector<unsigned char>& payload=std::vector<unsigned char>{}){
                std::vector<unsigned char> empty;
                auto request=aaPacket(id,payload);
                if(interrupt(usb->handle,0x05,request.data(),request.size(),750)!=static_cast<int>(request.size()))return empty;
                for(int attempt=0;attempt<12;++attempt){
                    auto response=popHelen(usb,500);
                    if(response.size()>=8&&response[0]==0xaa&&response[7]==id)return response;
                }
                return empty;
            };
            imuStop=!imuCommand(0x19,{0}).empty();
            auto length=imuCommand(0x14);
            imuLength=length.size()>=12;
            if(imuLength)imuExpected=length[8]|(length[9]<<8)|(length[10]<<16)|(length[11]<<24);
            while(imuReceived<imuExpected&&imuReceived<128*1024){
                auto part=imuCommand(0x15);if(part.size()<8)break;
                const int bytes=(part[5]|(part[6]<<8))-3;if(bytes<=0)break;imuReceived+=bytes;
            }
            imuSync=!imuCommand(0x1a).empty();
            imuStart=!imuCommand(0x19,{1}).empty();
            usb->helenHeartbeatRunning=true;
            usb->helenHeartbeatThread=std::thread([usb] {
                uint32_t requestId=10;
                while (usb->helenHeartbeatRunning) {
                    const uint64_t stamp=static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::nanoseconds>(
                        std::chrono::steady_clock::now().time_since_epoch()).count());
                    std::vector<unsigned char> payload(8);
                    for(int i=0;i<8;++i)payload[i]=static_cast<unsigned char>(stamp>>(i*8));
                    auto heartbeatPacket=fdPacket(0x001a,payload,requestId++,static_cast<uint32_t>(stamp));
                    interrupt(usb->handle,0x03,heartbeatPacket.data(),heartbeatPacket.size(),250);
                    std::array<unsigned char,64> response{};
                    interrupt(usb->handle,0x82,response.data(),response.size(),100);
                    std::this_thread::sleep_for(std::chrono::milliseconds(100));
                }
            });
            }
    }
    std::ostringstream result;
    result << "XREAL Helen 单 URB 官方队列 · claim IMU=" << claim
           << " · endpoint 0x84 async=" << (receiver ? "已提交" : "失败")
           << " · MCU heartbeat=" << heartbeat << '/' << 30
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
    usb->helenHeartbeatRunning=false;
    if(usb->helenHeartbeatThread.joinable())usb->helenHeartbeatThread.join();
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
