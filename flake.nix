{
  description = "tagesschau: podcast feed, web player and time-shifted live stream of every edition";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs =
    { self, nixpkgs }:
    let
      systems = [
        "x86_64-linux"
        "aarch64-linux"
        "x86_64-darwin"
        "aarch64-darwin"
      ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
    in
    {
      # x86_64-linux package builds and checks run on the self-hosted nixbot.
      # aarch64-linux builds natively on GitHub's arm runners (the nix-aarch64
      # job of .github/workflows/test.yaml).
      hydraJobs = nixpkgs.lib.genAttrs [ "x86_64-linux" ] (system: {
        packages = self.packages.${system} or { };
        # Builders have no /dev/kvm; the VM test stays on GitHub Actions.
        checks = builtins.removeAttrs (self.checks.${system} or { }) [ "nixos" ];
      });

      packages = forAllSystems (pkgs: rec {
        tagesschau = pkgs.callPackage ./nix/package.nix { };
        default = tagesschau;
      });

      overlays.default = final: _prev: {
        tagesschau = final.callPackage ./nix/package.nix { };
      };

      nixosModules.default = {
        imports = [ ./nix/module.nix ];
        nixpkgs.overlays = [ self.overlays.default ];
      };

      checks = forAllSystems (
        pkgs:
        {
          inherit (self.packages.${pkgs.stdenv.hostPlatform.system}) tagesschau;
          ruff = pkgs.runCommand "tagesschau-ruff" { nativeBuildInputs = [ pkgs.ruff ]; } ''
            ruff check --no-cache --config ${./ruff.toml} ${./src}
            ruff format --check --no-cache --config ${./ruff.toml} ${./src}
            touch $out
          '';
        }
        // nixpkgs.lib.optionalAttrs pkgs.stdenv.hostPlatform.isLinux {
          nixos = pkgs.testers.runNixOSTest (import ./nix/test.nix { inherit self; });
        }
      );

      devShells = forAllSystems (pkgs: {
        default = pkgs.mkShell {
          packages = with pkgs; [
            python3
            ruff
            nodejs
            nixfmt
            statix
            deadnix
          ];
        };
      });

      formatter = forAllSystems (pkgs: pkgs.nixfmt-tree);
    };
}
