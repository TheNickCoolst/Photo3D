// Command-line wrapper around Apple's Object Capture (RealityKit PhotogrammetrySession).
// Usage: photogrammetry <imagesDir> <outputDir> [--detail preview|reduced|medium|full|raw]
//                       [--ordering unordered|sequential] [--sensitivity normal|high] [--no-masking]
// Writes <outputDir>/model.usdz and <outputDir>/obj/ (OBJ+MTL+textures).
// Emits one JSON object per line on stdout: {"event":"progress","fraction":0.42} etc.

import Foundation
import RealityKit

func emit(_ dict: [String: Any]) {
    if let data = try? JSONSerialization.data(withJSONObject: dict),
       let line = String(data: data, encoding: .utf8) {
        print(line)
        fflush(stdout)
    }
}

var args = Array(CommandLine.arguments.dropFirst())
if args.first == "--check" {
    emit(["event": "check", "supported": PhotogrammetrySession.isSupported])
    exit(PhotogrammetrySession.isSupported ? 0 : 1)
}
guard args.count >= 2 else {
    FileHandle.standardError.write("usage: photogrammetry <imagesDir> <outputDir> [options]\n".data(using: .utf8)!)
    exit(2)
}
let inputURL = URL(fileURLWithPath: args.removeFirst(), isDirectory: true)
let outputURL = URL(fileURLWithPath: args.removeFirst(), isDirectory: true)

var detail: PhotogrammetrySession.Request.Detail = .medium
var config = PhotogrammetrySession.Configuration()
config.isObjectMaskingEnabled = true

while !args.isEmpty {
    let a = args.removeFirst()
    switch a {
    case "--detail":
        switch args.isEmpty ? "" : args.removeFirst() {
        case "preview": detail = .preview
        case "reduced": detail = .reduced
        case "full": detail = .full
        case "raw": detail = .raw
        default: detail = .medium
        }
    case "--ordering":
        config.sampleOrdering = (args.isEmpty ? "" : args.removeFirst()) == "sequential" ? .sequential : .unordered
    case "--sensitivity":
        config.featureSensitivity = (args.isEmpty ? "" : args.removeFirst()) == "high" ? .high : .normal
    case "--no-masking":
        config.isObjectMaskingEnabled = false
    default:
        break
    }
}

guard PhotogrammetrySession.isSupported else {
    emit(["event": "error", "message": "Object Capture wird auf diesem Mac nicht unterstützt."])
    exit(1)
}

try? FileManager.default.createDirectory(at: outputURL, withIntermediateDirectories: true)
let usdzURL = outputURL.appendingPathComponent("model.usdz")
let objDir = outputURL.appendingPathComponent("obj", isDirectory: true)
try? FileManager.default.createDirectory(at: objDir, withIntermediateDirectories: true)

let session: PhotogrammetrySession
do {
    session = try PhotogrammetrySession(input: inputURL, configuration: config)
} catch {
    emit(["event": "error", "message": "Session-Start fehlgeschlagen: \(error.localizedDescription)"])
    exit(1)
}

signal(SIGTERM) { _ in exit(143) }

let task = Task {
    var failed = false
    do {
        for try await output in session.outputs {
            switch output {
            case .processingComplete:
                emit(["event": failed ? "error" : "done", "message": failed ? "Rekonstruktion fehlgeschlagen" : "ok"])
                exit(failed ? 1 : 0)
            case .requestError(_, let error):
                failed = true
                emit(["event": "error", "message": "\(error)"])
            case .requestComplete(let request, _):
                if case .modelFile(let url, _, _) = request {
                    emit(["event": "file", "path": url.path])
                }
            case .requestProgress(_, let fraction):
                emit(["event": "progress", "fraction": fraction])
            case .requestProgressInfo(_, let info):
                var d: [String: Any] = ["event": "info"]
                if let eta = info.estimatedRemainingTime { d["eta"] = eta }
                d["stage"] = String(describing: info.processingStage.map { "\($0)" } ?? "")
                emit(d)
            case .inputComplete:
                emit(["event": "stage", "stage": "input-complete"])
            case .invalidSample(let id, let reason):
                emit(["event": "warn", "message": "Bild \(id) ungültig: \(reason)"])
            case .skippedSample(let id):
                emit(["event": "warn", "message": "Bild \(id) übersprungen"])
            case .automaticDownsampling:
                emit(["event": "warn", "message": "Automatisches Downsampling aktiv (Speicher)"])
            case .processingCancelled:
                emit(["event": "error", "message": "Abgebrochen"])
                exit(1)
            case .stitchingIncomplete:
                emit(["event": "warn", "message": "Nicht alle Bilder konnten verbunden werden"])
            @unknown default:
                break
            }
        }
    } catch {
        emit(["event": "error", "message": "\(error)"])
        exit(1)
    }
}

do {
    try session.process(requests: [
        .modelFile(url: usdzURL, detail: detail),
        .modelFile(url: objDir, detail: detail),
    ])
} catch {
    emit(["event": "error", "message": "Verarbeitung konnte nicht starten: \(error)"])
    exit(1)
}

RunLoop.main.run()
