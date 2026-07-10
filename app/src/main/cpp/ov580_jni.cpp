#include <jni.h>
#include <libusb.h>
#include <vector>
#include <algorithm>
#include <cstdint>

static const unsigned char COMMIT[34]={1,0,1,1,0x15,0x16,5,0,0,0,0,0,0,0,0,0,0x65,0,0,0x65,9,0,0,0x80,0,0,0x80,0xd1,0xf0,8,8,0xf0,0xa9,0x18};
struct Ov580Usb { libusb_context* context=nullptr;libusb_device_handle* handle=nullptr; };
static Ov580Usb* ov(jlong value){return reinterpret_cast<Ov580Usb*>(static_cast<intptr_t>(value));}

extern "C" JNIEXPORT jlong JNICALL Java_io_github_sensorprobe_Ov580Native_start(JNIEnv*,jobject,jint javaFd){
    auto* usb=new Ov580Usb();libusb_set_option(nullptr,LIBUSB_OPTION_NO_DEVICE_DISCOVERY,nullptr);
    if(libusb_init(&usb->context)!=0||libusb_wrap_sys_device(usb->context,javaFd,&usb->handle)!=0||libusb_claim_interface(usb->handle,1)!=0){if(usb->handle)libusb_close(usb->handle);if(usb->context)libusb_exit(usb->context);delete usb;return 0;}
    int rc=libusb_control_transfer(usb->handle,0x21,1,0x0200,1,const_cast<unsigned char*>(COMMIT),sizeof(COMMIT),1000);
    if(rc<0){libusb_release_interface(usb->handle,1);libusb_close(usb->handle);libusb_exit(usb->context);delete usb;return 0;}
    return static_cast<jlong>(reinterpret_cast<intptr_t>(usb));
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_io_github_sensorprobe_Ov580Native_readFrame(JNIEnv* env,jobject,jlong value){
    auto* usb=ov(value);if(!usb)return nullptr;constexpr size_t RAW=615908,BLOCK=0x8000,PIXELS=640*480*2,OUT=PIXELS+8;
    std::vector<unsigned char> raw(RAW);size_t got=0;while(got<RAW){int actual=0;int requested=static_cast<int>(std::min(BLOCK,RAW-got));int rc=libusb_bulk_transfer(usb->handle,0x81,raw.data()+got,requested,&actual,1000);if(rc!=0||actual<=0)return nullptr;got+=actual;}
    if(raw[0]==0)return nullptr;std::vector<unsigned char> clean;clean.reserve(OUT);size_t r=0;
    while(r<raw.size()&&clean.size()<OUT){size_t h=raw[r];r+=h;if(r>=raw.size())break;size_t len=BLOCK-(r%BLOCK);size_t end=std::min(r+len,raw.size());size_t take=std::min(end-r,OUT-clean.size());clean.insert(clean.end(),raw.begin()+r,raw.begin()+r+take);r+=len;}
    if(clean.size()<OUT)return nullptr;jbyteArray out=env->NewByteArray(OUT);env->SetByteArrayRegion(out,0,OUT,reinterpret_cast<jbyte*>(clean.data()));return out;
}
extern "C" JNIEXPORT void JNICALL Java_io_github_sensorprobe_Ov580Native_stop(JNIEnv*,jobject,jlong value){auto* usb=ov(value);if(!usb)return;libusb_release_interface(usb->handle,1);libusb_close(usb->handle);libusb_exit(usb->context);delete usb;}
