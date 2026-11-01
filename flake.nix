{
  description = "Development environment for Fossify Gallery";

  inputs.nixpkgs.url = "https://channels.nixos.org/nixpkgs-unstable/nixexprs.tar.xz";

  outputs = { self, nixpkgs }:
    let
      supportedSystems = [ "x86_64-linux" ];
      forAllSystems = nixpkgs.lib.genAttrs supportedSystems;
    in
    {
      devShells = forAllSystems (system:
        let
          pkgs = import nixpkgs {
            inherit system;
            config.allowUnfree = true;
            config.android_sdk.accept_license = true;
          };
          buildToolsVersion = "36.0.0";
          android = pkgs.androidenv.composeAndroidPackages {
            platformVersions = [ "36" ];
            buildToolsVersions = [ buildToolsVersion ];
            includeEmulator = false;
            includeSystemImages = false;
          };
          androidSdk = "${android.androidsdk}/libexec/android-sdk";
        in
        {
          default = pkgs.mkShell {
            packages = [
              pkgs.jdk17
              android.androidsdk
            ];

            ANDROID_HOME = androidSdk;
            ANDROID_SDK_ROOT = androidSdk;

            # Use Nix's aapt2 binary so it runs on NixOS and other systems
            # without relying on the copy downloaded by the Android Gradle plugin.
            GRADLE_OPTS =
              "-Dorg.gradle.project.android.aapt2FromMavenOverride=${androidSdk}/build-tools/${buildToolsVersion}/aapt2";
          };
        });
    };
}
