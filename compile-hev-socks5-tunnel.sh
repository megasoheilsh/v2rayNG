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

TMPDIR=$(mktemp -d)

clear_tmp () {
  rm -rf $TMPDIR
}

trap 'echo -e "Aborted, error $? in command: $BASH_COMMAND"; trap ERR; clear_tmp; exit 1' ERR INT

# Check if hev-socks5-tunnel directory exists and is properly initialized
if [ ! -d "$__dir/hev-socks5-tunnel" ] || [ ! -f "$__dir/hev-socks5-tunnel/Android.mk" ]; then
    echo "hev-socks5-tunnel directory is missing or incomplete. Cloning repository..."
    
    # Remove the directory if it exists but is incomplete
    if [ -d "$__dir/hev-socks5-tunnel" ]; then
        rm -rf "$__dir/hev-socks5-tunnel"
    fi
    
    # Clone hev-socks5-tunnel repository
    git clone https://github.com/heiher/hev-socks5-tunnel.git "$__dir/hev-socks5-tunnel"
    
    # Enter directory and initialize submodules
    cd "$__dir/hev-socks5-tunnel"
    git submodule update --init --recursive
    cd "$__dir"
fi

# Check if build.mk exists, download if needed
if [ ! -f "$__dir/hev-socks5-tunnel/build.mk" ]; then
    echo "build.mk not found, downloading from repository..."
    curl -o "$__dir/hev-socks5-tunnel/build.mk" https://raw.githubusercontent.com/heiher/hev-socks5-tunnel/master/build.mk
    echo "Downloaded build.mk content:"
    cat "$__dir/hev-socks5-tunnel/build.mk"
fi

# Create temporary directory structure for build
echo "Copying build files to temporary directory..."
cp $__dir/hev-socks5-tunnel/Android.mk $TMPDIR/
cp $__dir/hev-socks5-tunnel/Application.mk $TMPDIR/
cp $__dir/hev-socks5-tunnel/build.mk $TMPDIR/

# Print directory contents for debugging
echo "hev-socks5-tunnel directory contents:"
ls -la $__dir/hev-socks5-tunnel/
echo "TMPDIR contents:"
ls -la $TMPDIR/

pushd $TMPDIR

# Create symlinks to the source code
ln -s $__dir/hev-socks5-tunnel/src src
ln -s $__dir/hev-socks5-tunnel/include include
ln -s $__dir/hev-socks5-tunnel/third-part third-part

# Debug: show the content of Android.mk
echo "Content of Android.mk:"
cat Android.mk

# Build using NDK
echo "Starting NDK build..."
$NDK_HOME/ndk-build \
	NDK_PROJECT_PATH=. \
	APP_BUILD_SCRIPT=./Android.mk \
	APP_ABI=all \
	APP_PLATFORM=android-21 \
	NDK_LIBS_OUT=$TMPDIR/libs \
	NDK_OUT=$TMPDIR/tmp \
	APP_SHORT_COMMANDS=false LOCAL_SHORT_COMMANDS=false -B -j4

# Copy the compiled libraries back to the project
echo "Copying compiled libraries to project..."
cp -r $TMPDIR/libs $__dir/

popd
rm -rf $TMPDIR 