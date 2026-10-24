# Ismail IPTV

A lightweight, native Android TV IPTV player prototype for Philips Android TV / Google TV.

## Features (v0.1)
- Load an authorized M3U playlist URL
- Browse channels with a TV remote
- Play HTTP(S) streams with AndroidX Media3 / ExoPlayer
- No backend, accounts, subscriptions, or bundled streams

## Build
Use the **Build Android TV APK** GitHub Actions workflow (Actions tab). Download the `StreamLite-TV-debug-apk` artifact and install its APK on an Android TV device. Alternatively build with Android Studio using JDK 17 and Android SDK 35.

## Compatibility
Requires Android TV / Google TV (Android 6+). Philips Titan OS does **not** support Android APK installation.

## Security and limitations
Use only streams you are authorized to access. Playlist URLs can contain credentials: avoid sharing them. This prototype stores no playlist credentials and supports up to 2,000 visible channels. HTTPS is preferred. The app is a starter, not yet tested on a physical Philips TV.
