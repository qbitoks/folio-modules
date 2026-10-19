#!/usr/bin/env bash
# Builds libfoliodjvu.so (DjVuLibre's libdjvu + folio_djvu.cpp) for arm64-v8a and x86_64 into ../src/main/jniLibs.
# Linux (or Docker), NDK r28c, make:
#   ANDROID_NDK=/path/to/android-ndk-r28c ./build.sh
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
work=${WORK:-$here/.work}
DJVU_VERSION=3.5.28
tools=$ANDROID_NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
mkdir -p "$work"
if [ ! -d "$work/djvulibre-$DJVU_VERSION" ]; then
    curl -sSL "https://downloads.sourceforge.net/project/djvu/DjVuLibre/$DJVU_VERSION/djvulibre-$DJVU_VERSION.tar.gz" | tar -xz -C "$work"
fi
src=$work/djvulibre-$DJVU_VERSION
for abi in arm64-v8a x86_64; do
    case $abi in
        arm64-v8a) triple=aarch64-linux-android ;;
        x86_64) triple=x86_64-linux-android ;;
    esac
    cc="$tools/${triple}34-clang"
    build=$work/build-$abi
    rm -rf "$build" && mkdir -p "$build"
    # libdjvu only: no JPEG/TIFF (DjVu's own codecs need neither), no tools, static, PIC for the JNI .so.
    (cd "$build" && CC="$cc" CXX="$cc++" AR="$tools/llvm-ar" RANLIB="$tools/llvm-ranlib" \
        CFLAGS="-O2 -fPIC" CXXFLAGS="-O2 -fPIC -std=gnu++14" \
        "$src/configure" --host="$triple" --disable-shared --enable-static --disable-desktopfiles \
        --disable-xmltools --without-jpeg --without-tiff -q)
    make -C "$build/libdjvu" -j"$(nproc)" -s
    mkdir -p "$here/../src/main/jniLibs/$abi"
    "$cc++" -shared -O2 -fPIC -fvisibility=hidden -o "$build/libfoliodjvu.so" "$here/folio_djvu.cpp" \
        -I"$src" -I"$build" "$build/libdjvu/.libs/libdjvulibre.a" -static-libstdc++ -ljnigraphics -llog \
        -Wl,--gc-sections -Wl,-z,max-page-size=16384 -Wl,--exclude-libs,ALL
    "$tools/llvm-strip" -o "$here/../src/main/jniLibs/$abi/libfoliodjvu.so" "$build/libfoliodjvu.so"
done
cp "$src/COPYING" "$here/../LICENSE"
