# The VM has no internet, so this checks what works offline: the service
# starts, serves the player to browsers and the artwork, and fails cleanly
# where it would need tagesschau.de.
{ self }:
{
  name = "tagesschau";

  nodes.machine =
    { pkgs, ... }:
    {
      # The module itself; nixosModules.default would also set an overlay,
      # which the test's read-only pkgs do not allow.
      imports = [ ./module.nix ];
      services.tagesschau = {
        enable = true;
        package = self.packages.${pkgs.stdenv.hostPlatform.system}.tagesschau;
        port = 8089;
        coverImage = pkgs.runCommand "cover.png" { nativeBuildInputs = [ pkgs.imagemagick ]; } ''
          magick -size 64x64 xc:navy png:$out
        '';
      };
    };

  testScript = ''
    machine.wait_for_unit("tagesschau.service")
    machine.wait_for_open_port(8089)

    page = machine.succeed("curl -sf -H 'Accept: text/html' http://127.0.0.1:8089/")
    assert "<title>tagesschau, alle Ausgaben</title>" in page, page[:200]
    # Not `| grep -q`: it closes the pipe early and curl fails under pipefail.
    player = machine.succeed("curl -sf http://127.0.0.1:8089/player")
    assert "episodes.json" in player, player[:200]

    ctype = machine.succeed("curl -sf -o /dev/null -w '%{content_type}' http://127.0.0.1:8089/cover.jpg")
    assert ctype.startswith("image/"), ctype
    machine.succeed("test \"$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8089/logo.jpg)\" = 404")

    # Without tagesschau.de these answer with an error, not a hang or a crash.
    for path in ["/feed.xml", "/episodes.json", "/live"]:
        code = machine.succeed(f"curl -s -m 60 -o /dev/null -w '%{{http_code}}' http://127.0.0.1:8089{path}")
        assert code in ("404", "502"), f"{path}: {code}"
    machine.succeed("systemctl is-active tagesschau.service")
  '';
}
