#!/bin/sh
set -eu

./gradlew testDebugUnitTest --tests com.airplay.streamer.engine.SinkFactoryTest assembleDebug --console=plain
printf 'APK: %s\n' "$PWD/app/build/outputs/apk/debug/app-debug.apk"
