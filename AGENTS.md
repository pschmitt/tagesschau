# Agent Guidelines for tagesschau

A small Python server (standard library only) with one HTML page: a podcast
feed, a web player and a time-shifted live stream of the tagesschau. Sources
are `src/server.py` and `src/index.html`; Nix files live in `nix/`.

Before changing workflows, check for reusable workflows or actions in
[ci-workflows](https://github.com/pschmitt/ci-workflows) and preserve existing
triggers, inputs, permissions, and check coverage.

## Checks

Run these before finishing a change (in `nix develop`):

```sh
ruff check src && ruff format --check src
sed -n '/^<script>$/,/^<\/script>$/p' src/index.html | sed '1d;$d' > /tmp/player.js && node --check /tmp/player.js
nixfmt --check flake.nix nix/*.nix && statix check . && deadnix --fail nix flake.nix
```

Build and run `nix flake check` on an ad-hoc `rworker` or a remote build host,
not on the machine the agent runs on. The NixOS VM test needs KVM.

## Conventions

- No runtime dependencies beyond the Python standard library; the page loads
  hls.js and its font from a CDN and needs no build step.
- The repository ships no ARD artwork or logos. Images come from the module's
  `coverImage` and `logoImage` options.
- No hostnames or private infrastructure in the code or docs; use
  `example.com` placeholders.
- User-facing text on the page is German.
- Update `README.md` and `docs/` in the same change when behaviour changes.
- Strip trailing whitespace; use LF line endings and spaces for indentation.
