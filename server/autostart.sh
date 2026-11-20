#!/bin/zsh
# Richtet den Photo3D-Server als LaunchAgent ein (startet automatisch bei der Anmeldung).
#   ./autostart.sh          -> installieren + starten
#   ./autostart.sh remove   -> entfernen
cd "$(dirname "$0")"
DIR="$(pwd)"
PLIST="$HOME/Library/LaunchAgents/app.photo3d.server.plist"

if [[ "$1" == "remove" ]]; then
  launchctl bootout gui/$(id -u) "$PLIST" 2>/dev/null
  rm -f "$PLIST"
  echo "Autostart entfernt."
  exit 0
fi

(cd photogrammetry && swift build -c release) || exit 1
mkdir -p "$HOME/Library/LaunchAgents"
cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>app.photo3d.server</string>
  <key>ProgramArguments</key><array>
    <string>$(command -v python3)</string><string>$DIR/server.py</string>
  </array>
  <key>WorkingDirectory</key><string>$DIR</string>
  <key>EnvironmentVariables</key><dict>
    <key>PATH</key><string>/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin</string>
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>$DIR/server.log</string>
  <key>StandardErrorPath</key><string>$DIR/server.log</string>
</dict></plist>
EOF
launchctl bootout gui/$(id -u) "$PLIST" 2>/dev/null
launchctl bootstrap gui/$(id -u) "$PLIST" && echo "Autostart aktiv. Log: $DIR/server.log"
