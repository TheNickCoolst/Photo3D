#!/bin/zsh
# Startet den Photo3D Render-Server (baut das Object-Capture-Tool beim ersten Mal).
cd "$(dirname "$0")"
if [[ ! -x photogrammetry/.build/release/photogrammetry ]]; then
  echo "Baue photogrammetry-Tool ..."
  (cd photogrammetry && swift build -c release) || exit 1
fi
exec python3 server.py "$@"
