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

# Copy the Android.mk and Application.mk files to the temporary directory
install -m644 $__dir/hev-socks5-tunnel/Android.mk $TMPDIR/
install -m644 $__dir/hev-socks5-tunnel/Application.mk $TMPDIR/

pushd $TMPDIR

# Create symlinks to the source code
ln -s $__dir/hev-socks5-tunnel/src src
ln -s $__dir/hev-socks5-tunnel/include include
ln -s $__dir/hev-socks5-tunnel/third-part third-part

# Build using NDK
$NDK_HOME/ndk-build \
	NDK_PROJECT_PATH=. \
	APP_BUILD_SCRIPT=./Android.mk \
	APP_ABI=all \
	APP_PLATFORM=android-21 \
	NDK_LIBS_OUT=$TMPDIR/libs \
	NDK_OUT=$TMPDIR/tmp \
	APP_SHORT_COMMANDS=false LOCAL_SHORT_COMMANDS=false -B -j4

# Copy the compiled libraries back to the project
cp -r $TMPDIR/libs $__dir/

popd
rm -rf $TMPDIR 