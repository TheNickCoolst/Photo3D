#!/bin/zsh
# Baut die Android-APK und legt sie als ~/Photo3D/Photo3D.apk ab.
cd "$(dirname "$0")"
export JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Library/Android/sdk}
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew --console=plain assembleRelease "$@" && cp app/build/outputs/apk/release/app-release.apk ../Photo3D.apk && echo "APK: $(cd ..; pwd)/Photo3D.apk"
