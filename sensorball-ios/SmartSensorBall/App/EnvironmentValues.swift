import SwiftUI

private struct SensorBallAPIClientKey: EnvironmentKey {
    static let defaultValue = SensorBallAPIClient()
}

extension EnvironmentValues {
    var sensorBallAPIClient: SensorBallAPIClient {
        get { self[SensorBallAPIClientKey.self] }
        set { self[SensorBallAPIClientKey.self] = newValue }
    }
}

extension Color {
    init(hex: String) {
        var value = hex.trimmingCharacters(in: CharacterSet.alphanumerics.inverted)
        if value.count == 3 {
            value = value.map { "\($0)\($0)" }.joined()
        }
        let int = UInt64(value, radix: 16) ?? 0
        let red = Double((int >> 16) & 0xFF) / 255.0
        let green = Double((int >> 8) & 0xFF) / 255.0
        let blue = Double(int & 0xFF) / 255.0
        self.init(red: red, green: green, blue: blue)
    }
}

