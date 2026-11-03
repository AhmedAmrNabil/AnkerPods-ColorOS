{
  description = "Dev shell and build targets for the Soundcore ColorOS LSPosed module";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/0bb2eedc20e4739f29e8045034f2828ad7054f8f";
    flake-parts.url = "github:hercules-ci/flake-parts";
    flake-parts.inputs.nixpkgs-lib.follows = "nixpkgs";
  };

  outputs =
    inputs@{ flake-parts, ... }:
    flake-parts.lib.mkFlake { inherit inputs; } {
      systems = [ "x86_64-linux" ];

      perSystem =
        {
          pkgs,
          system,
          lib,
          ...
        }:
        let
          # Match these to compileSdk / buildToolsVersion in app/build.gradle.kts
          platformVersion = "37";
          buildToolsVersion = "37.0.0";

          androidSdk =
            (pkgs.androidenv.composeAndroidPackages {
              platformVersions = [ platformVersion ];
              buildToolsVersions = [ buildToolsVersion ];
              includeEmulator = false;
              includeSources = false;
              includeSystemImages = false;
              includeNDK = false;
            }).androidsdk;

          jdk = pkgs.jdk21;
          sdk = "${androidSdk}/libexec/android-sdk";
          aapt2 = "${sdk}/build-tools/${buildToolsVersion}/aapt2";

          # app name -> gradle task
          gradleTasks = {
            assembleDebug = ":app:assembleDebug";
            assembleRelease = ":app:assembleRelease";
            test = ":app:testDebugUnitTest";
            lint = ":app:lintDebug";
            build = "build";
            installDebug = ":app:installDebug";
          };

          mkGradleApp =
            name: task:
            let
              drv = pkgs.writeShellApplication {
                name = "gradle-${name}";
                runtimeInputs = [
                  jdk
                  androidSdk
                  pkgs.git
                ];
                text = ''
                  export ANDROID_HOME="${sdk}"
                  export ANDROID_SDK_ROOT="${sdk}"
                  export JAVA_HOME="${jdk}"
                  if [ -f keystore/signing.env ]; then
                    # shellcheck disable=SC1091
                    source keystore/signing.env
                  fi
                  exec ./gradlew ${task} -Pandroid.aapt2FromMavenOverride=${aapt2} "$@"
                '';
              };
            in
            {
              type = "app";
              program = lib.getExe drv;
            };
        in
        {
          # Needed so the Android SDK license and unfree packages are accepted
          _module.args.pkgs = import inputs.nixpkgs {
            inherit system;
            config = {
              allowUnfree = true;
              android_sdk.accept_license = true;
            };
          };

          devShells.default = pkgs.mkShell {
            packages = [
              jdk
              androidSdk
              pkgs.jadx
              pkgs.git
            ];
            ANDROID_HOME = sdk;
            ANDROID_SDK_ROOT = sdk;
            JAVA_HOME = "${jdk}";
            GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${aapt2}";
            shellHook = ''
              export PATH="$ANDROID_HOME/platform-tools:$PATH"
              if [ -f keystore/signing.env ]; then
                # shellcheck disable=SC1091
                source keystore/signing.env
              fi
            '';
          };

          apps = (lib.mapAttrs mkGradleApp gradleTasks) // {
            default = mkGradleApp "assembleDebug" gradleTasks.assembleDebug;
          };
        };
    };
}
