# Agent instructions

- Use Nix flakes for all project tooling. If a needed tool is not installed, run it with `nix run`; uncached commands may take time to download.
- Follow the commit naming conventions in the existing commit history (for example, `feat(gallery): ...` and `fix(video): ...`).
- For app verification, check for a connected phone with `adb`. If one is connected, install the app there and ask the user to verify it. If none is connected, prompt the user to connect a phone.
