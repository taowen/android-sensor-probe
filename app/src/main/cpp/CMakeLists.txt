cmake_minimum_required(VERSION 3.22.1)
project(sensorprobe_native)
set(LIBUSB_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/libusb)
add_library(libusb_static STATIC
    ${LIBUSB_ROOT}/libusb/core.c
    ${LIBUSB_ROOT}/libusb/descriptor.c
    ${LIBUSB_ROOT}/libusb/hotplug.c
    ${LIBUSB_ROOT}/libusb/io.c
    ${LIBUSB_ROOT}/libusb/sync.c
    ${LIBUSB_ROOT}/libusb/strerror.c
    ${LIBUSB_ROOT}/libusb/os/linux_usbfs.c
    ${LIBUSB_ROOT}/libusb/os/events_posix.c
    ${LIBUSB_ROOT}/libusb/os/threads_posix.c
    ${LIBUSB_ROOT}/libusb/os/linux_netlink.c)
target_include_directories(libusb_static PRIVATE ${LIBUSB_ROOT}/android ${LIBUSB_ROOT}/libusb ${LIBUSB_ROOT}/libusb/os)
target_compile_definitions(libusb_static PRIVATE OS_LINUX=1)
target_compile_options(libusb_static PRIVATE -pthread -fvisibility=hidden)
add_library(sensorprobe_native SHARED ov580_jni.cpp uvc_jni.cpp libusb_jni.cpp xreal_helen.cpp)
target_include_directories(sensorprobe_native PRIVATE ${LIBUSB_ROOT}/libusb)
target_link_libraries(sensorprobe_native libusb_static log)
