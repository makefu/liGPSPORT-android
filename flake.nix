{
  description = "ligpsport-android — native Android app for planning routes and uploading them to iGPSPORT cycling computers";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  inputs.flake-utils.url = "github:numtide/flake-utils";

  outputs = { self, nixpkgs, flake-utils, ... }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };
        jdk = pkgs.jdk21_headless;
        # Pin platform / build-tools / system-image versions explicitly
        # so a flake-update doesn't silently bump them.
        androidEnv = pkgs.androidenv.composeAndroidPackages {
          platformVersions = [ "34" ];
          buildToolsVersions = [ "34.0.0" ];
          includeEmulator = true;
          emulatorVersion = "35.1.19";
          includeSystemImages = true;
          systemImageTypes = [ "google_apis" ];
          abiVersions = [ "x86_64" ];
          includeNDK = false;
          useGoogleAPIs = true;
          extraLicenses = [
            "android-sdk-license"
            "android-sdk-preview-license"
            "android-googletv-license"
            "android-sdk-arm-dbt-license"
            "google-gdk-license"
            "intel-android-extra-license"
            "intel-android-sysimage-license"
            "mips-android-sysimage-license"
          ];
        };
        androidSdk = androidEnv.androidsdk;
        androidShell = ''
          export ANDROID_HOME="${androidSdk}/libexec/android-sdk"
          export ANDROID_SDK_ROOT="$ANDROID_HOME"
          export ANDROID_AVD_HOME="$PWD/.android/avd"
          export JAVA_HOME="${jdk}"
          export GRADLE_USER_HOME="$PWD/.gradle"
          export GRADLE_OPTS="-Dorg.gradle.project.android.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/34.0.0/aapt2"
          export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
        '';

        # Shared body for the two emulator variants. The headless one
        # is what CI / `nix run .#emulator` use; the GUI one drops the
        # `-no-window` flag and pulls in the host's X11 / Wayland /
        # libGL libraries so a real window opens on the developer's
        # desktop.
        emulatorScript = { headless ? true }: ''
          set -euo pipefail
          ${androidShell}
          cd "''${LIGPSPORT_ANDROID_DIR:-$PWD}"
          APK="$PWD/app/build/outputs/apk/debug/app-debug.apk"
          if [ ! -f "$APK" ]; then
            echo "==> building debug APK"
            gradle --no-daemon assembleDebug
          fi
          AVD_NAME="${if headless then "ligpsport-test" else "ligpsport-gui"}"
          mkdir -p "$ANDROID_AVD_HOME"
          if [ ! -d "$ANDROID_AVD_HOME/$AVD_NAME.avd" ]; then
            echo "==> creating AVD $AVD_NAME"
            echo "no" | avdmanager create avd \
              -n "$AVD_NAME" \
              -k "system-images;android-34;google_apis;x86_64" \
              --force
          fi
          echo "==> launching emulator (${if headless then "headless" else "GUI"})"
          ${if headless
             then ''emulator -avd "$AVD_NAME" -no-window -no-audio -no-snapshot -gpu swiftshader_indirect &''
             else ''
               # The emulator binary links against host GL/X11 libs at
               # runtime; the nixpkgs `androidsdk` ships them, but on
               # Wayland-only sessions XWayland must be active. host_gpu
               # uses the host's GPU drivers via the Android emulator's
               # OpenGL passthrough; if that fails (mesa version
               # mismatches, …), set EMULATOR_GPU=swiftshader_indirect
               # to fall back to software rendering.
               EMU_GPU="''${EMULATOR_GPU:-host}"
               emulator -avd "$AVD_NAME" -no-snapshot -gpu "$EMU_GPU" &
             ''}
          EMU_PID=$!
          trap 'kill $EMU_PID || true' EXIT
          adb wait-for-device
          until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do
            sleep 2
          done
          echo "==> installing $APK"
          adb install -r "$APK"
          echo "==> launching app"
          adb shell am start -n de.syntaxfehler.ligpsport.debug/de.syntaxfehler.ligpsport.MainActivity || true
          wait $EMU_PID
        '';
        emulator = pkgs.writeShellApplication {
          name = "ligpsport-emulator";
          runtimeInputs = [ androidSdk jdk pkgs.gradle_8 pkgs.coreutils ];
          text = emulatorScript { headless = true; };
        };
        guiEmulator = pkgs.writeShellApplication {
          name = "ligpsport-gui-emulator";
          runtimeInputs = [ androidSdk jdk pkgs.gradle_8 pkgs.coreutils ];
          text = emulatorScript { headless = false; };
        };

        runInstrumentedTests = pkgs.writeShellApplication {
          name = "ligpsport-run-instrumented-tests";
          runtimeInputs = [ androidSdk jdk pkgs.gradle_8 pkgs.coreutils ];
          text = ''
            set -euo pipefail
            ${androidShell}
            AVD_NAME="ligpsport-ci"
            mkdir -p "$ANDROID_AVD_HOME"
            if [ ! -d "$ANDROID_AVD_HOME/$AVD_NAME.avd" ]; then
              echo "no" | avdmanager create avd \
                -n "$AVD_NAME" \
                -k "system-images;android-34;google_apis;x86_64" \
                --force
            fi
            ACCEL=""
            if [ ! -e /dev/kvm ]; then
              echo "==> /dev/kvm not available, falling back to software emulation"
              ACCEL="-accel off"
            fi
            # shellcheck disable=SC2086
            emulator -avd "$AVD_NAME" \
              -no-window -no-audio -no-snapshot -gpu swiftshader_indirect $ACCEL &
            EMU_PID=$!
            trap 'kill $EMU_PID || true; adb kill-server || true' EXIT
            adb wait-for-device
            until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do
              sleep 2
            done
            echo "==> running connectedDebugAndroidTest"
            gradle --no-daemon connectedDebugAndroidTest
          '';
        };

        # APK builders are exposed as `nix run` apps rather than
        # derivations: Gradle's plugin resolution requires network
        # access at build time, which Nix's sandbox forbids unless the
        # user is in `trusted-users` (most NixOS hosts are not). The
        # `nix run` wrappers reuse the dev-shell SDK closure but execute
        # outside the build sandbox so Gradle can fetch its plugin tree.
        mkBuildApp = variant: pkgs.writeShellApplication {
          name = "ligpsport-build-${variant}";
          runtimeInputs = [ androidSdk jdk pkgs.gradle_8 ];
          text = ''
            set -euo pipefail
            ${androidShell}
            cd "''${LIGPSPORT_ANDROID_DIR:-$PWD}"
            gradle --no-daemon "assemble${if variant == "debug" then "Debug" else "Release"}"
            echo "==> APK: $PWD/app/build/outputs/apk/${variant}/"
            ls -lh "$PWD/app/build/outputs/apk/${variant}/"
          '';
        };
        unitTestApp = pkgs.writeShellApplication {
          name = "ligpsport-test-unit";
          runtimeInputs = [ androidSdk jdk pkgs.gradle_8 ];
          text = ''
            set -euo pipefail
            ${androidShell}
            cd "''${LIGPSPORT_ANDROID_DIR:-$PWD}"
            gradle --no-daemon testDebugUnitTest
            echo "==> reports: $PWD/app/build/reports/tests/testDebugUnitTest/"
          '';
        };
        # End-to-end test against real iGPSPORT hardware: builds &
        # installs the APK, grants permissions, then drives the
        # PAIR → UPLOAD → DELETE_ROUTE → UNPAIR cycle via adb
        # broadcasts. Exits 0 only on a complete pass.
        e2eTest = pkgs.writeShellApplication {
          name = "ligpsport-e2e-test";
          runtimeInputs = [
            androidSdk
            jdk
            pkgs.gradle_8
            pkgs.coreutils
            pkgs.gnugrep
            pkgs.gawk
            pkgs.bash
          ];
          # The harness uses `nix run .#build-debug` internally; mark
          # bash's strict-mode flags as expected by ShellCheck.
          checkPhase = "true";
          text = ''
            set -euo pipefail
            ${androidShell}
            cd "''${LIGPSPORT_ANDROID_DIR:-$PWD}"
            exec ./scripts/e2e-test.sh "$@"
          '';
        };
        # Install + launch on the first running adb device. The
        # default behaviour rebuilds the debug APK first; pass
        # LIGPSPORT_SKIP_BUILD=1 to install whatever is already in
        # app/build/outputs/apk/debug/.
        deployApp = pkgs.writeShellApplication {
          name = "ligpsport-install";
          runtimeInputs = [ androidSdk jdk pkgs.gradle_8 pkgs.coreutils ];
          text = ''
            set -euo pipefail
            ${androidShell}
            cd "''${LIGPSPORT_ANDROID_DIR:-$PWD}"
            APK="$PWD/app/build/outputs/apk/debug/app-debug.apk"
            if [ "''${LIGPSPORT_SKIP_BUILD:-0}" != "1" ]; then
              echo "==> building debug APK"
              gradle --no-daemon assembleDebug
            fi
            if [ ! -f "$APK" ]; then
              echo "==> ERROR: $APK not found"
              exit 1
            fi
            DEVICES=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
            if [ -z "$DEVICES" ]; then
              echo "==> no adb device attached. Start the emulator first:"
              echo "      nix run .#gui-emulator    # windowed"
              echo "      nix run .#emulator        # headless"
              exit 1
            fi
            COUNT=$(echo "$DEVICES" | wc -l)
            if [ "$COUNT" -gt 1 ] && [ -z "''${ANDROID_SERIAL:-}" ]; then
              echo "==> multiple devices attached, picking the first one:"
              echo "$DEVICES"
              echo "    (set ANDROID_SERIAL=<id> to override)"
            fi
            SERIAL=''${ANDROID_SERIAL:-$(echo "$DEVICES" | head -n1)}
            echo "==> installing $APK on $SERIAL"
            adb -s "$SERIAL" install -r "$APK"
            echo "==> launching app"
            adb -s "$SERIAL" shell am start \
              -n de.syntaxfehler.ligpsport.debug/de.syntaxfehler.ligpsport.MainActivity
          '';
        };
      in {
        apps.build-debug = flake-utils.lib.mkApp { drv = mkBuildApp "debug"; };
        apps.build-release = flake-utils.lib.mkApp { drv = mkBuildApp "release"; };
        apps.test-unit = flake-utils.lib.mkApp { drv = unitTestApp; };
        apps.emulator = flake-utils.lib.mkApp { drv = emulator; };
        apps.gui-emulator = flake-utils.lib.mkApp { drv = guiEmulator; };
        apps.install = flake-utils.lib.mkApp { drv = deployApp; };
        apps.e2e-test = flake-utils.lib.mkApp { drv = e2eTest; };
        apps.run-instrumented-tests = flake-utils.lib.mkApp { drv = runInstrumentedTests; };

        packages.build-debug = mkBuildApp "debug";
        packages.build-release = mkBuildApp "release";
        packages.test-unit = unitTestApp;
        packages.emulator = emulator;
        packages.gui-emulator = guiEmulator;
        packages.install = deployApp;
        packages.e2e-test = e2eTest;
        packages.run-instrumented-tests = runInstrumentedTests;
        packages.default = mkBuildApp "debug";

        devShells.default = pkgs.mkShell {
          packages = [
            jdk
            androidSdk
            pkgs.gradle_8
            pkgs.kotlin
            pkgs.protobuf
            pkgs.ktlint
            pkgs.fd
            pkgs.ripgrep
            pkgs.coreutils
          ];
          shellHook = androidShell;
        };
      });
}
