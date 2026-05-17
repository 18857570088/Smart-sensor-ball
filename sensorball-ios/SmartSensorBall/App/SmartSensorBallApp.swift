import SwiftUI

@main
struct SmartSensorBallApp: App {
    @StateObject private var bluetoothManager = SensorBallBluetoothManager()
    @StateObject private var trainingManager = TrainingManager()
    private let apiClient = SensorBallAPIClient()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(bluetoothManager)
                .environmentObject(trainingManager)
                .environment(\.sensorBallAPIClient, apiClient)
                .onAppear {
                    trainingManager.attach(bluetoothManager: bluetoothManager)
                }
        }
    }
}

