# NixOS module

The flake exports `nixosModules.default`, which adds the package through an
overlay and defines `services.tagesschau`.

| Option | Default | |
| --- | --- | --- |
| `enable` | `false` | Run the service. |
| `package` | `pkgs.tagesschau` | Package to run. |
| `bindHost` | `"127.0.0.1"` | Address to listen on. |
| `port` | `8089` | Port to listen on. |
| `baseUrl` | `"http://localhost:<port>"` | Public URL; feed artwork and live session URLs use it. |
| `quality` | `"webxxl"` | Preferred MP4 quality (`webxxl` is 1080p), with lower ones as fallback. |
| `maxAgeHours` | `48` | Editions older than this are left out. |
| `liveMinutes` | `90` | How long after an edition starts `/live/edition` returns it. |
| `thumbnails.enable` | `true` | Make scrubbing thumbnail sheets with ffmpeg (see [HTTP interface](http.md#scrubbing-thumbnails)). |
| `coverImage` | `null` | Square feed artwork, served at `/cover.jpg`. |
| `logoImage` | `null` | Small square logo for the player header, served at `/logo.jpg`. |
| `openFirewall` | `false` | Open the port in the firewall. |

## Behind a reverse proxy

The server speaks plain HTTP. Put a TLS proxy in front and set `baseUrl` to
its public URL:

```nix
{
  services.tagesschau = {
    enable = true;
    baseUrl = "https://tagesschau.example.com";
  };
  services.nginx.virtualHosts."tagesschau.example.com" = {
    forceSSL = true;
    enableACME = true;
    locations."/".proxyPass = "http://127.0.0.1:8089";
  };
}
```

## Artwork

The repository ships no images. Without `coverImage` the feed has no artwork;
without `logoImage` the player header shows the name only.

## Package

`nix run github:pschmitt/tagesschau` starts the server with the same settings
as environment variables: `BIND_HOST`, `PORT`, `TAGESSCHAU_BASE_URL`,
`TAGESSCHAU_QUALITY`, `TAGESSCHAU_MAX_AGE_HOURS`, `TAGESSCHAU_LIVE_MINUTES`,
`TAGESSCHAU_COVER` and `TAGESSCHAU_LOGO`.
