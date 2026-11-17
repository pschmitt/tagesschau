{
  lib,
  stdenvNoCC,
  makeWrapper,
  python3,
  ffmpeg-headless,
}:
# Standard library only: nothing to build, the server runs from the store.
stdenvNoCC.mkDerivation {
  pname = "tagesschau";
  version = "0.1.0";

  src = lib.fileset.toSource {
    root = ../src;
    fileset = lib.fileset.unions [
      ../src/server.py
      ../src/index.html
      ../src/favicon.png
      ../src/icon-192.png
      ../src/icon-512.png
      ../src/apple-touch-icon.png
    ];
  };

  nativeBuildInputs = [ makeWrapper ];

  installPhase = ''
    runHook preInstall
    install -Dm644 server.py index.html *.png -t $out/share/tagesschau
    makeWrapper ${lib.getExe python3} $out/bin/tagesschau \
      --add-flags "-I $out/share/tagesschau/server.py" \
      --set-default TAGESSCHAU_FFMPEG ${lib.getExe ffmpeg-headless}
    runHook postInstall
  '';

  meta = {
    description = "Podcast feed, web player and time-shifted live stream of the tagesschau";
    homepage = "https://github.com/pschmitt/tagesschau";
    license = lib.licenses.gpl3Plus;
    mainProgram = "tagesschau";
    platforms = lib.platforms.all;
  };
}
