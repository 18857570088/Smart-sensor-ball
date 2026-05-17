import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var bluetooth: SensorBallBluetoothManager
    @EnvironmentObject private var training: TrainingManager
    @State private var selectedLanguage: AppLanguage = AppLanguage.current
    @State private var showingSettings = false
    @State private var showingLegal: LegalDocument?
    @State private var showingFirstUsePrompt = false

    var body: some View {
        NavigationView {
            ZStack {
                LinearGradient(colors: [Color(hex: "03120C"), Color(hex: "112D21")], startPoint: .top, endPoint: .bottom)
                    .ignoresSafeArea()

                ScrollView {
                    VStack(spacing: 18) {
                        header
                        trainingPanel
                        statsGrid
                        legalPanel
                    }
                    .padding(20)
                }
            }
            .navigationBarHidden(true)
            .sheet(isPresented: $showingSettings) {
                SettingsView(language: $selectedLanguage)
                    .environmentObject(bluetooth)
            }
            .sheet(item: $showingLegal) { document in
                LegalDocumentView(document: document, language: selectedLanguage)
            }
            .alert(Text(L10n.text("first_use_title", selectedLanguage)), isPresented: $showingFirstUsePrompt) {
                Button(L10n.text("later", selectedLanguage), role: .cancel) {}
                Button(L10n.text("open_settings", selectedLanguage)) {
                    showingSettings = true
                }
            } message: {
                Text(L10n.text("first_use_message", selectedLanguage))
            }
            .onAppear {
                selectedLanguage = AppLanguage.current
                if !UserDefaults.standard.bool(forKey: "bluetooth_first_use_prompt_shown") && !bluetooth.isConnected {
                    UserDefaults.standard.set(true, forKey: "bluetooth_first_use_prompt_shown")
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) {
                        showingFirstUsePrompt = true
                    }
                }
            }
        }
        .navigationViewStyle(.stack)
    }

    private var header: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 8) {
                Text("Smart sensor ball")
                    .font(.system(size: 30, weight: .bold))
                    .foregroundStyle(.white)
                HStack(spacing: 12) {
                    Image(systemName: "bonjour")
                        .font(.system(size: 20, weight: .bold))
                        .foregroundStyle(bluetooth.isConnected ? Color.blue : Color.red)
                    BatteryBadge(text: bluetooth.telemetry?.batteryText ?? "--")
                }
            }
            Spacer()
            Button {
                showingSettings = true
            } label: {
                Image(systemName: "gearshape.fill")
                    .font(.system(size: 24, weight: .bold))
                    .foregroundStyle(Color(hex: "80FFB0"))
                    .padding(10)
            }
            .accessibilityLabel(L10n.text("settings", selectedLanguage))
        }
    }

    private var trainingPanel: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(L10n.text("training_center", selectedLanguage))
                .font(.title2.bold())
                .foregroundStyle(Color(hex: "8FFFD0"))

            HStack {
                VStack(alignment: .leading, spacing: 8) {
                    Text(training.countdownText)
                        .font(.system(size: 44, weight: .black))
                        .foregroundStyle(.white)
                    Text(training.statusText(language: selectedLanguage))
                        .foregroundStyle(Color(hex: "D7FDE8"))
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 4) {
                    Text("\(training.realTimeHits)")
                        .font(.system(size: 56, weight: .black))
                        .foregroundStyle(Color(hex: "FFD060"))
                    Text(L10n.text("punch_count", selectedLanguage))
                        .foregroundStyle(Color(hex: "D7FDE8"))
                }
            }

            HStack(spacing: 12) {
                Button {
                    Task { await training.start(duration: .seconds30) }
                } label: {
                    Label(L10n.text("start", selectedLanguage), systemImage: "play.fill")
                }
                .buttonStyle(PrimaryButtonStyle(color: Color(hex: "00A86B")))
                .disabled(training.isRunning || !bluetooth.isConnected)

                Button {
                    Task { await training.stop() }
                } label: {
                    Label(L10n.text("end", selectedLanguage), systemImage: "stop.fill")
                }
                .buttonStyle(PrimaryButtonStyle(color: Color(hex: "783333")))
                .disabled(!training.isRunning)
            }
        }
        .padding(18)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 18))
        .overlay(RoundedRectangle(cornerRadius: 18).stroke(Color(hex: "1E6C46"), lineWidth: 1))
    }

    private var statsGrid: some View {
        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
            MetricTile(title: L10n.text("bluetooth", selectedLanguage), value: bluetooth.connectionTitle(language: selectedLanguage))
            MetricTile(title: L10n.text("battery", selectedLanguage), value: bluetooth.telemetry?.batteryText ?? "--")
            MetricTile(title: L10n.text("punch_count", selectedLanguage), value: "\(bluetooth.displayHitCount)")
            MetricTile(title: L10n.text("api_status", selectedLanguage), value: "API v1")
        }
    }

    private var legalPanel: some View {
        VStack(spacing: 10) {
            Button(L10n.text("privacy_policy", selectedLanguage)) {
                showingLegal = .privacy
            }
            Button(L10n.text("user_agreement", selectedLanguage)) {
                showingLegal = .agreement
            }
        }
        .buttonStyle(.bordered)
        .frame(maxWidth: .infinity)
    }
}

private struct BatteryBadge: View {
    let text: String

    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: "battery.75percent")
            Text(text)
                .font(.caption.bold())
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(Color.white.opacity(0.14), in: Capsule())
    }
}

private struct MetricTile: View {
    let title: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.caption)
                .foregroundStyle(Color(hex: "B9F8D0"))
            Text(value)
                .font(.headline)
                .foregroundStyle(.white)
                .lineLimit(2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(Color(hex: "092016"), in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).stroke(Color(hex: "1D5C3D"), lineWidth: 1))
    }
}

private struct PrimaryButtonStyle: ButtonStyle {
    let color: Color

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.headline)
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 13)
            .background(color.opacity(configuration.isPressed ? 0.7 : 1), in: RoundedRectangle(cornerRadius: 12))
    }
}
