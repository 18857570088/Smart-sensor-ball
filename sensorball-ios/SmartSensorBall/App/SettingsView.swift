import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var bluetooth: SensorBallBluetoothManager
    @Environment(\.dismiss) private var dismiss
    @Binding var language: AppLanguage

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(spacing: 18) {
                    bluetoothCard
                    languageCard
                }
                .padding(20)
            }
            .background(Color(hex: "03120C").ignoresSafeArea())
            .navigationTitle(L10n.text("settings_title", language))
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(L10n.text("done", language)) { dismiss() }
                }
            }
        }
        .navigationViewStyle(.stack)
    }

    private var bluetoothCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(L10n.text("bluetooth_connection", language))
                .font(.title3.bold())
                .foregroundStyle(Color(hex: "80FFB0"))
            Text(L10n.text("bluetooth_hint", language))
                .foregroundStyle(Color(hex: "D7FDE8"))

            Text(bluetooth.statusText)
                .font(.callout)
                .foregroundStyle(Color(hex: "B9F8D0"))
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(hex: "082018"), in: RoundedRectangle(cornerRadius: 12))

            HStack(spacing: 10) {
                Button(L10n.text("scan", language)) { bluetooth.startScan() }
                    .buttonStyle(SettingsButtonStyle(color: Color(hex: "008840")))
                    .disabled(bluetooth.isConnected)
                Button(L10n.text("connect", language)) { bluetooth.connectSelected() }
                    .buttonStyle(SettingsButtonStyle(color: Color(hex: "16384A")))
                    .disabled(bluetooth.isConnected || bluetooth.selectedDevice == nil)
                Button(L10n.text("disconnect", language)) { bluetooth.disconnect() }
                    .buttonStyle(SettingsButtonStyle(color: Color(hex: "5B2D2D")))
                    .disabled(!bluetooth.isConnected)
            }

            VStack(spacing: 8) {
                if bluetooth.devices.isEmpty {
                    Text(L10n.text("no_devices", language))
                        .foregroundStyle(Color(hex: "8FEFBC"))
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(12)
                } else {
                    ForEach(bluetooth.devices) { device in
                        Button {
                            bluetooth.selectedDevice = device
                        } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(device.name)
                                        .font(.headline)
                                    Text(device.identifier.uuidString)
                                        .font(.caption)
                                }
                                Spacer()
                                if bluetooth.selectedDevice?.id == device.id {
                                    Image(systemName: "checkmark.circle.fill")
                                }
                            }
                            .foregroundStyle(.white)
                            .padding(12)
                            .background(bluetooth.selectedDevice?.id == device.id ? Color(hex: "1B6F48") : Color(hex: "0A241A"), in: RoundedRectangle(cornerRadius: 12))
                        }
                    }
                }
            }
        }
        .settingsCard(stroke: Color(hex: "00FF88"))
    }

    private var languageCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(L10n.text("language", language))
                .font(.title3.bold())
                .foregroundStyle(Color(hex: "8FD8FF"))
            Picker(L10n.text("language", language), selection: $language) {
                ForEach(AppLanguage.allCases) { item in
                    Text(item.displayName).tag(item)
                }
            }
            .pickerStyle(.inline)
            .onChange(of: language) { newValue in
                newValue.save()
            }
        }
        .settingsCard(stroke: Color(hex: "2A6A8F"))
    }
}

private struct SettingsButtonStyle: ButtonStyle {
    let color: Color

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.bold())
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 11)
            .background(color.opacity(configuration.isPressed ? 0.65 : 1), in: RoundedRectangle(cornerRadius: 10))
    }
}

private extension View {
    func settingsCard(stroke: Color) -> some View {
        padding(16)
            .background(Color(hex: "07140F"), in: RoundedRectangle(cornerRadius: 18))
            .overlay(RoundedRectangle(cornerRadius: 18).stroke(stroke, lineWidth: 1))
    }
}
