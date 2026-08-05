cmake_minimum_required(VERSION 3.22.1)
project(quest_uvc LANGUAGES C CXX)

set(LIBUSB ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/libusb)
add_library(usb1 STATIC
    ${LIBUSB}/libusb/core.c ${LIBUSB}/libusb/descriptor.c ${LIBUSB}/libusb/hotplug.c
    ${LIBUSB}/libusb/io.c ${LIBUSB}/libusb/sync.c ${LIBUSB}/libusb/strerror.c
    ${LIBUSB}/libusb/os/linux_usbfs.c ${LIBUSB}/libusb/os/events_posix.c
    ${LIBUSB}/libusb/os/threads_posix.c ${LIBUSB}/libusb/os/linux_netlink.c)
target_include_directories(usb1 PRIVATE ${LIBUSB}/android ${LIBUSB}/libusb ${LIBUSB}/libusb/os)
target_compile_options(usb1 PRIVATE -fvisibility=hidden -pthread)

add_library(quest_uvc SHARED quest_uvc.cpp)
target_include_directories(quest_uvc PRIVATE ${LIBUSB}/libusb)
target_link_libraries(quest_uvc PRIVATE usb1 log android)
