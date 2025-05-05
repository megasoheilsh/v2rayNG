#!/bin/bash
set -o errexit
set -o pipefail
set -o nounset

# Set magic variables for current file & dir
__dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
__file="${__dir}/$(basename "${BASH_SOURCE[0]}")"
__base="$(basename ${__file} .sh)"

if [[ ! -d $NDK_HOME ]]; then
	echo "Android NDK: NDK_HOME not found. please set env \$NDK_HOME"
	exit 1
fi

# Check if hev-socks5-tunnel directory exists and is properly initialized
if [ ! -d "$__dir/hev-socks5-tunnel" ]; then
    echo "hev-socks5-tunnel directory is missing, cloning repository..."
    git clone --recursive https://github.com/heiher/hev-socks5-tunnel.git
fi

# Ensure Android.mk exists
if [ ! -f "$__dir/hev-socks5-tunnel/Android.mk" ]; then
    echo "Creating Android.mk file..."
    cat > $__dir/hev-socks5-tunnel/Android.mk << 'EOF'
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := hev-socks5-tunnel

LOCAL_CFLAGS += -Wall -Wextra -Wno-unused-parameter -Wno-missing-field-initializers
LOCAL_CFLAGS += -std=gnu99 -D_GNU_SOURCE -D_POSIX_C_SOURCE=200809L
LOCAL_CFLAGS += -I$(LOCAL_PATH)/src -I$(LOCAL_PATH)/third-part/lwip/include
LOCAL_CFLAGS += -I$(LOCAL_PATH)/third-part/yaml/yaml/src

LOCAL_SRC_FILES := \
	$(wildcard $(LOCAL_PATH)/src/*.c) \
	$(wildcard $(LOCAL_PATH)/src/core/*.c) \
	$(wildcard $(LOCAL_PATH)/src/tunnel/*.c) \
	$(wildcard $(LOCAL_PATH)/third-part/lwip/src/core/*.c) \
	$(wildcard $(LOCAL_PATH)/third-part/lwip/src/core/ipv4/*.c) \
	$(wildcard $(LOCAL_PATH)/third-part/lwip/src/core/ipv6/*.c) \
	$(wildcard $(LOCAL_PATH)/third-part/lwip/src/api/*.c) \
	$(wildcard $(LOCAL_PATH)/third-part/yaml/yaml/src/*.c)

LOCAL_STATIC_LIBRARIES := hev-task-system

include $(BUILD_SHARED_LIBRARY)

$(call import-module, hev-task-system)
EOF
fi

# Ensure Application.mk exists
if [ ! -f "$__dir/hev-socks5-tunnel/Application.mk" ]; then
    echo "Creating Application.mk file..."
    cat > $__dir/hev-socks5-tunnel/Application.mk << 'EOF'
APP_PLATFORM := android-21
APP_ABI := armeabi-v7a arm64-v8a x86 x86_64
APP_CFLAGS := -O3
APP_MODULES := hev-socks5-tunnel
EOF
fi

# Ensure hev-task-system exists
if [ ! -d "$__dir/hev-socks5-tunnel/third-part/hev-task-system" ]; then
    echo "Cloning hev-task-system..."
    mkdir -p $__dir/hev-socks5-tunnel/third-part
    git clone --recursive https://github.com/heiher/hev-task-system.git $__dir/hev-socks5-tunnel/third-part/hev-task-system
fi

# Ensure hev-task-system Android.mk exists
if [ ! -f "$__dir/hev-socks5-tunnel/third-part/hev-task-system/Android.mk" ]; then
    echo "Creating hev-task-system Android.mk..."
    cat > $__dir/hev-socks5-tunnel/third-part/hev-task-system/Android.mk << 'EOF'
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := hev-task-system

LOCAL_CFLAGS += -Wall -Wextra -Wno-unused-parameter
LOCAL_CFLAGS += -std=gnu99 -D_GNU_SOURCE -D_POSIX_C_SOURCE=200809L
LOCAL_CFLAGS += -I$(LOCAL_PATH)/src

LOCAL_SRC_FILES := \
	$(wildcard $(LOCAL_PATH)/src/*.c)

include $(BUILD_STATIC_LIBRARY)
EOF
fi

# Set up jni directory
mkdir -p $__dir/V2rayNG/app/src/main/jni
ln -sf "$__dir/hev-socks5-tunnel" "$__dir/V2rayNG/app/src/main/jni/hev-socks5-tunnel"

# Build the library
echo "Building hev-socks5-tunnel with NDK..."
cd $__dir/V2rayNG/app/src/main
export NDK_PROJECT_PATH=.
$NDK_HOME/ndk-build -C jni APP_BUILD_SCRIPT=jni/hev-socks5-tunnel/Android.mk APP_ABI=all APP_PLATFORM=android-21 NDK_LIBS_OUT=../libs NDK_OUT=../obj APP_SHORT_COMMANDS=false LOCAL_SHORT_COMMANDS=false -B
cd $__dir

echo "Build completed successfully!" 