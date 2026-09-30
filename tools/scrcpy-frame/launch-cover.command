#!/bin/zsh
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"
resources_dir="${0:A:h}"

fail() {
    print -r -- "$1"
    read -r '?按回车键结束。'
    exit 1
}

command -v adb >/dev/null 2>&1 || fail '未找到 ADB，请安装 Android Platform Tools。'
export ADB="$(command -v adb)"
device_serial="${1:-$(adb -d get-serialno 2>/dev/null)}"
[[ -n "$device_serial" && "$(adb -s "$device_serial" get-state 2>/dev/null)" == device ]] \
    || fail '请连接 Z Flip5 的 USB 数据线，打开 USB 调试，并允许电脑调试。'

display_dump=$(adb -s "$device_serial" shell dumpsys display) || fail '无法读取手机显示器信息。'
display_uid=$(print -r -- "$display_dump" | sed -nE '/mBaseDisplayInfo=.*displayId 1,/s/.*uniqueId "([^"]+)".*/\1/p' | head -1)
[[ -n "$display_uid" ]] || fail '未找到外屏 Display 1，已停止启动。'
physical_line=$(print -r -- "$display_dump" | awk -v id="$display_uid" 'index($0,"DisplayDeviceInfo{") && index($0,"uniqueId=\"" id "\"") {print; exit}')
[[ "$physical_line" == *FLAG_EXTRA_BUILT_IN_DISPLAY* ]] || fail 'Display 1 不是已识别的内置外屏，已停止启动。'
pixel_size=$(print -r -- "$physical_line" | sed -nE 's/.*uniqueId="[^"]+", ([0-9]+) x ([0-9]+),.*/\1,\2/p')
[[ "$pixel_size" == '748,720' ]] || fail '当前外屏尺寸与校准框不符。为避免错误遮挡，已停止启动。'
safe_insets=$(print -r -- "$physical_line" | sed -nE 's/.*insets=Rect\(([0-9]+), ([0-9]+) - ([0-9]+), ([0-9]+)\).*/\1,\2,\3,\4/p')

export FLIPCOVER_FRAME_PNG="$resources_dir/zflip5-cover-calibrated.png"
export FLIPCOVER_SAFE_AREA="${pixel_size},${safe_insets}"
export SCRCPY_SERVER_PATH="$resources_dir/scrcpy-server"
export SCRCPY_ICON_DIR="$resources_dir"
[[ -f "$FLIPCOVER_FRAME_PNG" ]] || fail '机型框资源缺失，请重新安装带框投屏。'

print '正在打开带机型框的 Z Flip5 外屏。请合上手机并正常解锁。'
print 'F8 切换外框；F9 切换系统缺口安全线。Mac 媒体键模式请加 Fn。'
print 'Option+左/右箭头旋转整个展示窗口。关闭窗口结束投屏。'
print '边框与镜头为外观示意；绿色线仅表示系统缺口安全边界。'
adb -s "$device_serial" shell cmd device_state state reset || fail '无法恢复实际开合状态。'
adb -s "$device_serial" shell input -d 1 keyevent KEYCODE_WAKEUP

"$resources_dir/../MacOS/scrcpy" --serial="$device_serial" --display-id=1 \
    --no-audio --no-clipboard-autosync --no-power-on --keep-active \
    --capture-orientation=@0 --no-terminal-title --window-width=543 --window-height=645 \
    --window-title='Z Flip5 外屏 · 校准机型框 · F8 外框 / F9 辅助线' \
    || fail '外屏投屏已中断或启动失败。请检查 USB 连接后重试。'
