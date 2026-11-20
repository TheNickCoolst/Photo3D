#!/bin/zsh
# Startet den Photo3D Render-Server (baut/aktualisiert vorher das Object-Capture-Tool).
cd "$(dirname "$0")"
(cd photogrammetry && swift build -c release 2>&1 | grep -E "error|Build complete" ) || exit 1
[[ -x photogrammetry/.build/release/photogrammetry ]] || { echo "Build fehlgeschlagen"; exit 1; }
exec python3 server.py "$@"
