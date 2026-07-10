cmake_minimum_required(VERSION 3.22.1)
project(sensorprobe_native)
add_library(sensorprobe_native SHARED ov580_jni.cpp)
target_link_libraries(sensorprobe_native log)
