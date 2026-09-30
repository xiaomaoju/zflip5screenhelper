#!/bin/bash
set -euo pipefail
[[ "$(uname -s)" == Darwin ]] || { echo '此构建入口用于 macOS。'; exit 1; }
tools_dir=$(cd -- "$(dirname -- "$0")" && pwd)
project_root=$(cd -- "$tools_dir/../.." && pwd)
build_root="$project_root/Cache/build-tools/scrcpy-frame.nosync"
source_dir="$build_root/scrcpy-4.1"
bundle="${1:-$project_root/dist/Z Flip5 外屏.app}"
brew_prefix=$(brew --prefix)
export PATH="$brew_prefix/bin:$PATH"
export PKG_CONFIG_PATH="$brew_prefix/opt/sdl3/lib/pkgconfig:$brew_prefix/opt/ffmpeg/lib/pkgconfig:${PKG_CONFIG_PATH:-}"
mkdir -p "$build_root"
archive="$build_root/scrcpy-v4.1.tar.gz"
if [[ ! -f "$archive" ]]; then
    curl -fL --retry 2 'https://github.com/Genymobile/scrcpy/archive/refs/tags/v4.1.tar.gz' -o "$archive"
fi
[[ "$(shasum -a 256 "$archive" | cut -d ' ' -f 1)" == 537b2ade623cb94b6edddfa5c61bf0b0af21484aa8365ea2531b686ea573249a ]] \
    || { echo 'scrcpy 源码校验失败。'; exit 1; }
tar -xzf "$archive" -C "$build_root"
patch -d "$source_dir" -p1 < "$tools_dir/scrcpy.patch"
cp "$tools_dir/cover_frame.c" "$tools_dir/cover_frame.h" "$source_dir/app/src/"
if [[ -d "$build_root/build/meson-private" ]]; then
    meson setup --reconfigure "$build_root/build" "$source_dir" --buildtype=release -Dcompile_server=false -Dportable=true -Dusb=false
else
    meson setup "$build_root/build" "$source_dir" --buildtype=release -Dcompile_server=false -Dportable=true -Dusb=false
fi
meson compile -C "$build_root/build"
: "${ANDROID_HOME:?Set ANDROID_HOME to an Android SDK containing Platform 36}"
server_build="$build_root/server-manual"
mkdir -p "$server_build"
ANDROID_PLATFORM=36 ANDROID_BUILD_TOOLS="${ANDROID_BUILD_TOOLS:-35.0.0}" \
    BUILD_DIR="$server_build" "$source_dir/server/build_without_gradle.sh"
server="$server_build/scrcpy-server"
mkdir -p "$bundle/Contents/MacOS" "$bundle/Contents/Resources"
cp "$build_root/build/app/scrcpy" "$bundle/Contents/MacOS/scrcpy"
cp "$server" "$bundle/Contents/Resources/scrcpy-server"
rm -f "$bundle/Contents/MacOS/scrcpy-server"
cp "$tools_dir/Info.plist" "$bundle/Contents/Info.plist"
cp "$tools_dir/launch-cover.command" "$bundle/Contents/Resources/"
cp "$project_root/dist/device-frames/zflip5-cover-overlay.png" "$bundle/Contents/Resources/zflip5-cover-calibrated.png"
cp "$source_dir/app/data/scrcpy.png" "$source_dir/app/data/disconnected.png" "$bundle/Contents/Resources/"
cp "$source_dir/LICENSE" "$bundle/Contents/Resources/SCRCPY-LICENSE"
chmod +x "$bundle/Contents/Resources/launch-cover.command"
xattr -dr com.apple.FinderInfo "$bundle" 2>/dev/null || true
xattr -dr com.apple.ResourceFork "$bundle" 2>/dev/null || true
codesign --force --deep --sign - "$bundle"
codesign --verify --deep --strict "$bundle"
printf '已构建：%s\n' "$bundle"
