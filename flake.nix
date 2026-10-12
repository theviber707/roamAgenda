# SPDX-FileCopyrightText: 2026 The Viber <theviber707@proton.me>
#
# SPDX-License-Identifier: GPL-3.0-or-later

{
  description = "'Emacs From Scratch'-like Org Agenda Setup with Jetpack Compose";
  inputs.nixpkgs.url = "nixpkgs/master";
  outputs =
    {
      nixpkgs,
      self,
      ...
    }:
    let
      inherit (nixpkgs) lib;
      forAllSystems = f: lib.genAttrs lib.systems.flakeExposed (system:
      f (import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      })
      );
    in
    {
      devShells = forAllSystems (
        pkgs:
        let
          jdk = pkgs.jdk21;
          abiVersion = if pkgs.stdenv.hostPlatform.isAarch64 then "arm64-v8a" else "x86_64";
          androidComposition = pkgs.androidenv.composeAndroidPackages {
            abiVersions = [abiVersion];
            buildToolsVersions = [ "34.0.0" "35.0.0" ];
            cmdLineToolsVersion = "13.0";
            includeEmulator = true;
            includeNDK = false;
            includeSources = false;
            includeSystemImages = true;
            platformToolsVersion = "37.0.1";
            platformVersions = ["34" "35"];
            systemImageTypes = ["google_apis"];
            toolsVersion = "26.1.1";
          };
          androidSdk = androidComposition.androidsdk;
          emulatorLibs = with pkgs; [
            freetype
            libGL
            libX11
            libxcb
            libxdamage
            libxext
            libxfixes
            pulseaudio
            vulkan-loader
            fontconfig
          ];
          runAvd = pkgs.writeShellScriptBin "run-avd" ''
            export ANDROID_AVD_HOME="''${ANDROID_AVD_HOME:-$HOME/.config/.android/avd}"
            exec emulator -avd test-device -no-boot-anim -gpu host -qemu -enable-kvm "$@"
          '';
          createAvd = pkgs.writeShellScriptBin "create-avd" ''
            echo "no" | avdmanager create avd \
              -n test-device \
              -k "system-images;android-34;google_apis;${abiVersion}" \
              --force
          '';
        in
        {
          default = pkgs.mkShell {
            env = {
              ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
              ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";
              GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMaven=true";
              LD_LIBRARY_PATH = "${pkgs.lib.makeLibraryPath emulatorLibs}";
              JAVA_HOME = "${jdk.home}";
              QT_QPA_PLATFORM = "xcb";
            };
            packages = with pkgs; [
              androidSdk
              createAvd
              detekt
              gradle
              grype
              jdk
              kotlin
              kotlin-language-server
              ktlint
              nixd
              osv-scanner
              qemu_kvm
              reuse
              runAvd
              trivy
            ];
            shellHook = ''
              export ANDROID_AVD_HOME="''${ANDROID_AVD_HOME:-$HOME/.config/.android/avd}"
            '';
          };
        }
      );
      formatter = forAllSystems (system: nixpkgs.legacyPackages.${system}.nixfmt);
    };
}
