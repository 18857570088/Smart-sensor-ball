import Combine
import Foundation

enum TrainingDuration {
    case seconds30
    case seconds60

    var seconds: Int {
        switch self {
        case .seconds30: return 30
        case .seconds60: return 60
        }
    }
}

@MainActor
final class TrainingManager: ObservableObject {
    @Published var isRunning = false
    @Published var countdownText = "3"
    @Published var realTimeHits = 0
    @Published private var statusKey = "ready"

    private weak var bluetoothManager: SensorBallBluetoothManager?
    private var timerTask: Task<Void, Never>?

    func attach(bluetoothManager: SensorBallBluetoothManager) {
        self.bluetoothManager = bluetoothManager
    }

    func statusText(language: AppLanguage) -> String {
        L10n.text(statusKey, language)
    }

    func start(duration: TrainingDuration) async {
        guard !isRunning else {
            return
        }
        guard let bluetoothManager, bluetoothManager.isConnected else {
            statusKey = "connect_first"
            return
        }
        realTimeHits = 0
        for value in stride(from: 3, through: 1, by: -1) {
            countdownText = "\(value)"
            statusKey = "countdown"
            try? await Task.sleep(nanoseconds: 850_000_000)
        }
        countdownText = "GO"
        statusKey = "running"
        try? await Task.sleep(nanoseconds: 450_000_000)

        isRunning = true
        bluetoothManager.setGyroscopeEnabled(true)
        let startCount = bluetoothManager.displayHitCount
        let start = Date()
        timerTask?.cancel()
        timerTask = Task { [weak self, weak bluetoothManager] in
            while !Task.isCancelled {
                guard let self, let bluetoothManager else { return }
                let elapsed = Int(Date().timeIntervalSince(start))
                if elapsed >= duration.seconds {
                    await self.stop()
                    return
                }
                self.realTimeHits = max(0, bluetoothManager.displayHitCount - startCount)
                self.countdownText = "\(duration.seconds - elapsed)"
                try? await Task.sleep(nanoseconds: 100_000_000)
            }
        }
    }

    func stop() async {
        timerTask?.cancel()
        timerTask = nil
        bluetoothManager?.setGyroscopeEnabled(false)
        isRunning = false
        statusKey = "complete"
        countdownText = "DONE"
    }
}
