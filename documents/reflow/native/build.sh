#!/usr/bin/env bash
# Builds libfolioreflow.so for arm64-v8a and x86_64 into ../src/main/jniLibs. Linux (or Docker), NDK r28c, CMake, Ninja:
#   ANDROID_NDK=/path/to/android-ndk-r28c ./build.sh
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
work=${WORK:-$here/.work}
K2_COMMIT=64aa9cc   # koreader/libk2pdfopt (k2pdfopt 2.55 + KOReader's reflow API)
LEPT_VERSION=1.85.0
mkdir -p "$work"
if [ ! -d "$work/libk2pdfopt" ]; then
    git clone -q https://github.com/koreader/libk2pdfopt.git "$work/libk2pdfopt"
    git -C "$work/libk2pdfopt" checkout -q "$K2_COMMIT"
fi
if [ ! -d "$work/leptonica-$LEPT_VERSION" ]; then
    curl -sSL "https://github.com/DanBloomberg/leptonica/releases/download/$LEPT_VERSION/leptonica-$LEPT_VERSION.tar.gz" | tar -xz -C "$work"
fi
for abi in arm64-v8a x86_64; do
    cmake -S "$here" -B "$work/build-$abi" -G Ninja -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-34 \
        -DK2PDFOPT_DIR="$work/libk2pdfopt" -DLEPTONICA_DIR="$work/leptonica-$LEPT_VERSION"
    cmake --build "$work/build-$abi" --target folioreflow
    mkdir -p "$here/../src/main/jniLibs/$abi"
    "$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" -o "$here/../src/main/jniLibs/$abi/libfolioreflow.so" \
        "$work/build-$abi/libfolioreflow.so"
done
