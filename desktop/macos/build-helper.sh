#!/bin/sh
set -eu
cd "$(dirname "$0")/../.."
root="$PWD"
arch="$(uname -m)"
case "$arch" in arm64) platform=macosarm64 ;; x86_64) platform=macosx64 ;; *) exit 1 ;; esac
work="$root/desktop/build/native-helper-$arch"
mkdir -p "$work/source"
if [ ! -f "$work/source.tar.gz" ]; then
    curl -fL https://api.github.com/repos/chromiumembedded/java-cef/tarball/17e805ad387d89839fdd2b864466b6510b2a783c -o "$work/source.tar.gz"
    printf '%s  %s\n' 6caaa315a19613bbb9c61486311d27c1216a3de421a25766c65541299d3d090f "$work/source.tar.gz" | shasum -a 256 -c -
fi
tar -xzf "$work/source.tar.gz" --strip-components=1 -C "$work/source"
patch -d "$work/source" -p1 < desktop/macos/jcef-helper.patch
cef="cef_binary_152.0.6+g708dc14+chromium-152.0.7977.83_$platform"
if [ ! -d "$work/$cef" ]; then
    url="https://cef-builds.spotifycdn.com/$cef.tar.bz2"
    curl -fL "$url.sha1" -o "$work/cef.sha1"
    curl -fL "$url" -o "$work/cef.tar.bz2"
    printf '%s  %s\n' "$(cat "$work/cef.sha1")" "$work/cef.tar.bz2" | shasum -a 1 -c -
    tar -xjf "$work/cef.tar.bz2" -C "$work"
fi
cmake -S desktop/macos -B "$work/build" -DCMAKE_BUILD_TYPE=Release \
    -DPROJECT_ARCH="$arch" -DCEF_ROOT="$work/$cef" -DJCEF_SOURCE="$work/source"
cmake --build "$work/build" --target yunx_helper --parallel 8
helper="$work/build/jcef Helper.app"
mkdir -p "$helper/Contents/Resources"
cp "$work/source/LICENSE.txt" "$helper/Contents/Resources/JCEF-LICENSE.txt"
cp "$work/$cef/LICENSE.txt" "$helper/Contents/Resources/CEF-LICENSE.txt"
plutil -convert xml1 "$helper/Contents/Info.plist"
codesign --force --sign - "$helper"
codesign --verify --deep --strict "$helper"
# Store the signed bundle without modifying its executable or sealed plist at runtime.
(cd "$work/build" && zip -qr "$root/desktop/libs/jcef-helper-macos-$arch.zip" 'jcef Helper.app')
