#!/bin/zsh
# Startet den Photo3D Render-Server (baut/aktualisiert vorher das Object-Capture-Tool).
cd "$(dirname "$0")"
if ! out=$(cd photogrammetry && swift build -c release 2>&1); then
  echo "$out" | tail -20
  echo "Swift-Build fehlgeschlagen (Xcode Command Line Tools installiert?)"
  exit 1
fi
exec python3 server.py "$@"
