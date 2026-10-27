# LOCALRECS: dev shell for building this fork (JDK, Android SDK, adb).
{ pkgs, ... }:

{
  languages.java = {
    enable = true;
    jdk.package = pkgs.jdk17;
  };

  android = {
    enable = true;
    platforms.version = [ "34" ];
    # 30.0.3 is what the project asks for; 34.0.0 provides an aapt2 new enough for AGP 7.4.
    buildTools.version = [ "30.0.3" "34.0.0" ];
    emulator.enable = false;
    systemImages.enable = false;
    ndk.enable = false;
    android-studio.enable = false;
    googleAPIs.enable = false;
    sources.enable = false;
  };

  # AGP downloads its own aapt2, which cannot run on NixOS, so point it at the SDK copy.
  enterShell = ''
    export GRADLE_OPTS="-Dorg.gradle.project.android.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/34.0.0/aapt2"
  '';
}
