import SwiftUI

enum LegalDocument: Identifiable {
    case privacy
    case agreement

    var id: String {
        switch self {
        case .privacy: return "privacy"
        case .agreement: return "agreement"
        }
    }
}

struct LegalDocumentView: View {
    @Environment(\.dismiss) private var dismiss
    let document: LegalDocument
    let language: AppLanguage

    var body: some View {
        NavigationView {
            ScrollView {
                Text(loadText())
                    .font(.body)
                    .foregroundStyle(.primary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding()
            }
            .navigationTitle(title)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button(L10n.text("done", language)) { dismiss() }
                }
            }
        }
        .navigationViewStyle(.stack)
    }

    private var title: String {
        switch document {
        case .privacy: return L10n.text("privacy_policy", language)
        case .agreement: return L10n.text("user_agreement", language)
        }
    }

    private func loadText() -> String {
        let prefix = document == .privacy ? "privacy_policy" : "user_agreement"
        let fileName = "\(prefix)_\(language.legalSuffix)"
        let candidates = [
            Bundle.main.url(forResource: fileName, withExtension: "txt"),
            Bundle.main.url(forResource: fileName, withExtension: "txt", subdirectory: "Legal"),
            Bundle.main.url(forResource: fileName, withExtension: "txt", subdirectory: "Resources/Legal"),
        ]
        guard let url = candidates.compactMap({ $0 }).first,
              let text = try? String(contentsOf: url, encoding: .utf8) else {
            return L10n.text("document_unavailable", language)
        }
        return text
    }
}
