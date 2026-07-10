#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <vector>
#include <algorithm>
#include <cstring>

static const unsigned char COMMIT[34]={1,0,1,1,0x15,0x16,5,0,0,0,0,0,0,0,0,0,0x65,0,0,0x65,9,0,0,0x80,0,0,0x80,0xd1,0xf0,8,8,0xf0,0xa9,0x18};

extern "C" JNIEXPORT jint JNICALL Java_io_github_sensorprobe_Ov580Native_start(JNIEnv*,jobject,jint javaFd){
    int fd=dup(javaFd); if(fd<0)return -1;
    unsigned int iface=1;
    if(ioctl(fd,USBDEVFS_CLAIMINTERFACE,&iface)<0){close(fd);return -2;}
    usbdevfs_ctrltransfer c{}; c.bRequestType=0x21;c.bRequest=1;c.wValue=0x0200;c.wIndex=1;c.wLength=34;c.timeout=1000;c.data=(void*)COMMIT;
    if(ioctl(fd,USBDEVFS_CONTROL,&c)<0){ioctl(fd,USBDEVFS_RELEASEINTERFACE,&iface);close(fd);return -3;}
    return fd;
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_io_github_sensorprobe_Ov580Native_readFrame(JNIEnv* env,jobject,jint fd){
    constexpr size_t RAW=615908, BLOCK=0x8000, PIXELS=640*480*2, OUT=PIXELS+8;
    std::vector<unsigned char> raw(RAW); size_t got=0;
    while(got<RAW){
        usbdevfs_bulktransfer b{}; b.ep=0x81;b.len=std::min(BLOCK,RAW-got);b.timeout=1000;b.data=raw.data()+got;
        int n=ioctl(fd,USBDEVFS_BULK,&b); if(n<=0)return nullptr; got+=n;
    }
    if(raw[0]==0)return nullptr;
    std::vector<unsigned char> clean;clean.reserve(OUT);size_t r=0;
    while(r<raw.size()&&clean.size()<OUT){size_t h=raw[r];r+=h;if(r>=raw.size())break;size_t len=BLOCK-(r%BLOCK);size_t end=std::min(r+len,raw.size());size_t take=std::min(end-r,OUT-clean.size());clean.insert(clean.end(),raw.begin()+r,raw.begin()+r+take);r+=len;}
    if(clean.size()<OUT)return nullptr;
    jbyteArray out=env->NewByteArray(OUT);env->SetByteArrayRegion(out,0,OUT,(jbyte*)clean.data());return out;
}
extern "C" JNIEXPORT void JNICALL Java_io_github_sensorprobe_Ov580Native_stop(JNIEnv*,jobject,jint fd){unsigned int iface=1;ioctl(fd,USBDEVFS_RELEASEINTERFACE,&iface);close(fd);}
