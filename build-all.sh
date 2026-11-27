#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

# Auto-detect JDK 17 if JAVA_HOME is not set
if [[ -z "${JAVA_HOME:-}" ]]; then
  for candidate in \
    "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home" \
    "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
    "/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"; do
    if [[ -d "$candidate" ]]; then
      export JAVA_HOME="$candidate"
      break
    fi
  done
fi

IOS_MODE="${1:-simulator}" # "simulator" (default) or "device"
if [[ "$IOS_MODE" == "--device" || "$IOS_MODE" == "device" ]]; then
  KMP_IOS_TASK=":composeApp:linkDebugFrameworkIosArm64"
  XCODE_SDK="iphoneos"
  XCODE_ARCH="arm64"
  FRAMEWORK_DIR="iosArm64"
else
  KMP_IOS_TASK=":composeApp:linkDebugFrameworkIosSimulatorArm64"
  XCODE_SDK="iphonesimulator"
  XCODE_ARCH="arm64"
  FRAMEWORK_DIR="iosSimulatorArm64"
fi

echo "==> [1/5] Syncing shared Web Audio DSP & App Icons..."
mkdir -p iosApp/GeneratedResources iosApp/PitchScaleSafariExtension/Resources composeApp/build/macos-icon dist

python3 - <<'PYEOF'
import json, pathlib
worklet_src = pathlib.Path("shared-web/pitch-worklet.js").read_text(encoding="utf-8")
loader = f"window.__PITCH_SCALER_WORKLET_SRC = {json.dumps(worklet_src)};\n"
for target in [
    pathlib.Path("iosApp/GeneratedResources/worklet-loader.js"),
    pathlib.Path("iosApp/PitchScaleSafariExtension/Resources/worklet-loader.js"),
]:
    target.write_text(loader, encoding="utf-8")
PYEOF

cp shared-web/pitch-engine.js iosApp/GeneratedResources/pitch-engine.js
cp shared-web/pitch-engine.js iosApp/PitchScaleSafariExtension/Resources/pitch-engine.js

ICON_SRC="composeApp/src/androidMain/res/mipmap-xxxhdpi/ic_launcher.png"
ICON_DST="iosApp/PitchScale/Assets.xcassets/AppIcon.appiconset/AppIcon.png"
MACOS_ICNS="composeApp/build/macos-icon/AppIcon.icns"

if [[ -f "$ICON_SRC" ]]; then
  sips -z 1024 1024 "$ICON_SRC" --out "$ICON_DST" >/dev/null

  ROUNDED_ICON="composeApp/build/macos-icon/AppIcon.png"
  python3 - "$ICON_SRC" "$ROUNDED_ICON" <<'PYEOF'
import sys
from PIL import Image, ImageDraw

src_path, dst_path = sys.argv[1], sys.argv[2]
size = 1024
pad = 100
inner = size - 2 * pad
radius = 185

src = Image.open(src_path).convert("RGBA")
w, h = src.size
crop_margin = int(min(w, h) * 0.08)
src = src.crop((crop_margin, crop_margin, w - crop_margin, h - crop_margin))
src = src.resize((inner, inner), Image.Resampling.LANCZOS)

mask = Image.new("L", (inner, inner), 0)
draw = ImageDraw.Draw(mask)
draw.rounded_rectangle((0, 0, inner - 1, inner - 1), radius=radius, fill=255)

rounded = Image.new("RGBA", (inner, inner), (0, 0, 0, 0))
rounded.paste(src, (0, 0), mask)

canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
canvas.paste(rounded, (pad, pad), rounded)
canvas.save(dst_path, "PNG")
PYEOF

  ICONSET_DIR="$(mktemp -d)/AppIcon.iconset"
  mkdir -p "$ICONSET_DIR"
  for sz in 16 32 128 256 512; do
    sips -z "$sz" "$sz" "$ROUNDED_ICON" --out "$ICONSET_DIR/icon_${sz}x${sz}.png" >/dev/null
    sz2=$((sz * 2))
    sips -z "$sz2" "$sz2" "$ROUNDED_ICON" --out "$ICONSET_DIR/icon_${sz}x${sz}@2x.png" >/dev/null
  done
  iconutil -c icns "$ICONSET_DIR" -o "$MACOS_ICNS"
  rm -rf "$(dirname "$ICONSET_DIR")"
fi

echo "==> [2/5] Building Android APK, iOS Compose Framework, and macOS Native Bundle..."
./gradlew :composeApp:assembleDebug "$KMP_IOS_TASK" :composeApp:createDistributable --no-daemon

cp composeApp/build/outputs/apk/debug/composeApp-debug.apk dist/PitchScale-android.apk

echo "==> [3/5] Compiling native macOS Core Audio Process Tap + shared SoundTouch DSP..."
MACOS_APP="dist/Pitch Scaler.app"
rm -rf "$MACOS_APP" dist/PitchScale-macos.app dist/PitchScale-ios.app iosApp/build
cp -R "composeApp/build/compose/binaries/main/app/Pitch Scaler.app" "$MACOS_APP"
mkdir -p "$MACOS_APP/Contents/Frameworks"

ST_SOURCES=(
  native/soundtouch/soundtouch_c_api.cpp
  native/soundtouch/soundtouch/AAFilter.cpp
  native/soundtouch/soundtouch/BPMDetect.cpp
  native/soundtouch/soundtouch/FIFOSampleBuffer.cpp
  native/soundtouch/soundtouch/FIRFilter.cpp
  native/soundtouch/soundtouch/InterpolateCubic.cpp
  native/soundtouch/soundtouch/InterpolateLinear.cpp
  native/soundtouch/soundtouch/InterpolateShannon.cpp
  native/soundtouch/soundtouch/PeakFinder.cpp
  native/soundtouch/soundtouch/RateTransposer.cpp
  native/soundtouch/soundtouch/SoundTouch.cpp
  native/soundtouch/soundtouch/TDStretch.cpp
  native/soundtouch/soundtouch/cpu_detect_x86.cpp
)

clang++ -std=c++17 -O3 -fobjc-arc -shared \
  -DSOUNDTOUCH_INTEGER_SAMPLES=1 \
  -Inative/soundtouch/include \
  -Inative/soundtouch/soundtouch \
  -I"$JAVA_HOME/include" \
  -I"$JAVA_HOME/include/darwin" \
  -framework CoreAudio \
  -framework AudioToolbox \
  -framework Foundation \
  -framework AVFoundation \
  native/macos/macos_audio_engine.mm \
  "${ST_SOURCES[@]}" \
  -o "$MACOS_APP/Contents/Frameworks/libpitchscale_macos.dylib"

codesign --force --deep --sign - "$MACOS_APP" >/dev/null 2>&1 || true

echo "==> [4/5] Generating Xcode project via XcodeGen..."
(cd iosApp && xcodegen generate)

echo "==> [5/5] Building iOS App + Safari Web Extension ($XCODE_SDK)..."
XCODE_DERIVED="/tmp/pitchscale-xcode-build"
xcodebuild \
  -project iosApp/PitchScale.xcodeproj \
  -scheme PitchScale \
  -configuration Debug \
  -sdk "$XCODE_SDK" \
  -derivedDataPath "$XCODE_DERIVED" \
  ARCHS="$XCODE_ARCH" \
  ONLY_ACTIVE_ARCH=YES \
  FRAMEWORK_SEARCH_PATHS="$(pwd)/composeApp/build/bin/${FRAMEWORK_DIR}/debugFramework" \
  CODE_SIGNING_ALLOWED=NO \
  build

# Package iOS .app as .zip so macOS Spotlight / App Menu doesn't show disabled iOS .app icons
rm -rf dist/PitchScale-ios.app dist/PitchScale-ios.app.zip
(cd "$XCODE_DERIVED/Build/Products/Debug-${XCODE_SDK}" && zip -qry "$ROOT_DIR/dist/PitchScale-ios.app.zip" PitchScale.app)

echo ""
echo "============================================"
echo "  ALL 3 BUILDS SUCCEEDED!"
echo "  Android APK : dist/PitchScale-android.apk"
echo "  iOS App     : dist/PitchScale-ios.app.zip"
echo "    (includes PitchScaleSafariExtension.appex)"
echo "  macOS App   : dist/Pitch Scaler.app"
echo "============================================"
