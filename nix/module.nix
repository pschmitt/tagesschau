{
  config,
  lib,
  pkgs,
  ...
}:
let
  cfg = config.services.tagesschau;
in
{
  options.services.tagesschau = {
    enable = lib.mkEnableOption "the tagesschau feed, web player and live stream";

    package = lib.mkPackageOption pkgs "tagesschau" { };

    bindHost = lib.mkOption {
      type = lib.types.str;
      default = "127.0.0.1";
      description = "Address to listen on.";
    };

    port = lib.mkOption {
      type = lib.types.port;
      default = 8089;
      description = "Port to listen on.";
    };

    baseUrl = lib.mkOption {
      type = lib.types.str;
      default = "http://localhost:${toString cfg.port}";
      defaultText = lib.literalExpression ''"http://localhost:''${toString config.services.tagesschau.port}"'';
      example = "https://tagesschau.example.org";
      description = ''
        Public URL of the service. Feed artwork and live stream session URLs
        are built from it, so set it when the service runs behind a reverse proxy.
      '';
    };

    quality = lib.mkOption {
      type = lib.types.enum [
        "webxxl"
        "webxl"
        "webm"
        "webs"
      ];
      default = "webxxl";
      description = "Preferred MP4 quality (webxxl is 1080p); lower ones are the fallback.";
    };

    maxAgeHours = lib.mkOption {
      type = lib.types.ints.positive;
      default = 48;
      description = "Editions older than this are left out of the feed and the player.";
    };

    liveMinutes = lib.mkOption {
      type = lib.types.ints.positive;
      default = 90;
      description = ''
        How long after an edition starts /live hands out a time-shifted stream
        of it. Das Erste keeps two hours of its live stream.
      '';
    };

    thumbnails.enable = lib.mkOption {
      type = lib.types.bool;
      default = true;
      description = ''
        Make scrubbing thumbnails: for every edition, ffmpeg turns the lowest-quality MP4 into a
        few JPEG sheets of small frames (kept under the service's cache directory and deleted
        with the edition). Without them the players seek a second, hidden video instead.
      '';
    };

    coverImage = lib.mkOption {
      type = lib.types.nullOr lib.types.path;
      default = null;
      description = "Square feed artwork, served at /cover.jpg. Without it the feed has no image.";
    };

    logoImage = lib.mkOption {
      type = lib.types.nullOr lib.types.path;
      default = null;
      description = "Small square logo for the player's header and favicon, served at /logo.jpg.";
    };

    openFirewall = lib.mkOption {
      type = lib.types.bool;
      default = false;
      description = "Open the port in the firewall.";
    };
  };

  config = lib.mkIf cfg.enable {
    systemd.services.tagesschau = {
      description = "tagesschau feed, web player and live stream";
      wantedBy = [ "multi-user.target" ];
      after = [ "network-online.target" ];
      wants = [ "network-online.target" ];

      environment = {
        BIND_HOST = cfg.bindHost;
        PORT = toString cfg.port;
        TAGESSCHAU_BASE_URL = cfg.baseUrl;
        TAGESSCHAU_QUALITY = cfg.quality;
        TAGESSCHAU_MAX_AGE_HOURS = toString cfg.maxAgeHours;
        TAGESSCHAU_LIVE_MINUTES = toString cfg.liveMinutes;
        TAGESSCHAU_THUMBNAILS = if cfg.thumbnails.enable then "1" else "0";
      }
      // lib.optionalAttrs (cfg.coverImage != null) { TAGESSCHAU_COVER = "${cfg.coverImage}"; }
      // lib.optionalAttrs (cfg.logoImage != null) { TAGESSCHAU_LOGO = "${cfg.logoImage}"; };

      serviceConfig = {
        ExecStart = lib.getExe cfg.package;
        Restart = "always";
        RestartSec = 5;
        DynamicUser = true;
        CacheDirectory = "tagesschau";

        CapabilityBoundingSet = "";
        LockPersonality = true;
        NoNewPrivileges = true;
        PrivateDevices = true;
        PrivateTmp = true;
        ProtectClock = true;
        ProtectControlGroups = true;
        ProtectHome = true;
        ProtectHostname = true;
        ProtectKernelLogs = true;
        ProtectKernelModules = true;
        ProtectKernelTunables = true;
        ProtectSystem = "strict";
        RestrictAddressFamilies = [
          "AF_INET"
          "AF_INET6"
        ];
        RestrictNamespaces = true;
        RestrictRealtime = true;
        SystemCallArchitectures = "native";
        SystemCallFilter = [ "@system-service" ];
      };
    };

    networking.firewall.allowedTCPPorts = lib.mkIf cfg.openFirewall [ cfg.port ];
  };
}
