# Photo3D

**📲 APK herunterladen: [Photo3D.apk](https://github.com/TheNickCoolst/Photo3D/releases/latest/download/Photo3D.apk)** · [Alle Releases](https://github.com/TheNickCoolst/Photo3D/releases)

Android-App, die Fotos/Videos an deinen Mac im WLAN schickt. Der Mac rechnet daraus mit Apple Object Capture
(Photogrammetrie, GPU/Neural Engine des M-Chips) ein texturiertes 3D-Modell; die App zeigt es in 3D/AR.

```
Handy (App)  ──WLAN/HTTP──▶  Mac: server.py
  Fotos/Videos hochladen        ├─ ffmpeg: Videos → Einzelbilder
  Fortschritt anzeigen          ├─ photogrammetry (Swift, RealityKit Object Capture) → USDZ + OBJ
  3D-Viewer, AR, Teilen  ◀──    └─ Blender (headless) → GLB
```

## Mac (Server)

```sh
git clone https://github.com/TheNickCoolst/Photo3D.git
cd Photo3D/server
./start.sh            # startet den Server (Port 8765), baut beim 1. Mal das Swift-Tool
./autostart.sh        # optional: bei jeder Anmeldung automatisch starten
./autostart.sh remove # Autostart wieder entfernen
```

Der Server meldet sich per Bonjour (`_photo3d._tcp`) – die App findet ihn automatisch.
Status im Browser: `http://<Mac-IP>:8765`. Daten liegen in `server/data/<job>/`.

Benötigt: macOS mit Apple Silicon, Xcode Command Line Tools, ffmpeg (Homebrew), Blender (für GLB).

## Android (App)

- APK direkt am Handy öffnen: https://github.com/TheNickCoolst/Photo3D/releases/latest/download/Photo3D.apk
  → installieren (Installation aus unbekannten Quellen erlauben)
- oder per USB: `adb install -r Photo3D.apk`
- Selbst bauen: `android/build.sh` (braucht JDK 17+ und Android SDK)

Funktionen: Galerie/Dateien/Kamera (Serienfotos) / Video als Quelle, Teilen aus der Galerie direkt an Photo3D,
Qualitätsstufen, Hintergrund-Maskierung, Fortschritt mit Restzeit, 3D-Viewer (offline), AR (Google Scene Viewer),
Export als GLB / USDZ / OBJ-ZIP, Neu-Rendern in anderer Qualität.

## Tipps für gute Scans

- 30–150 Fotos rundum, ~70 % Überlappung, 2–3 Höhen
- weiches gleichmäßiges Licht, Objekt bewegt sich nicht, scharfe Bilder
- Video: langsam ums Objekt gehen; der Server zieht automatisch Einzelbilder heraus
- glänzende, durchsichtige oder strukturlose Oberflächen funktionieren schlecht

## Sicherheit

Der Server hat keine Anmeldung – jeder im selben Netzwerk kann Scans hochladen/abrufen. Nur im Heimnetz betreiben.
