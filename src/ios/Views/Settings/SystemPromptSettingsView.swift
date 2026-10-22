import SwiftUI

/// Settings page for the editable static prose of the agent's system prompt.
/// Mirrors `SoulSettingsView` structure: a monospace editor, a live length
/// footer with an over-limit state, an app-bar Save, and a Restore-Default
/// confirmation. Lives right after Soul in the Agent Runtime section —
/// Soul edits identity, this edits behaviour.
///
/// Only the STATIC PROSE block is editable. The tool bullet list, the
/// SOUL identity, the skill / MCP fragments and the dynamic time line are
/// app-owned and are spliced in at prompt-build time (see
/// `AIChatViewModel.spliceToolBullets`), so they are deliberately absent here.
struct SystemPromptSettingsView: View {
    @State private var bodyText: String = SystemPromptProseStore.defaultContent
    @State private var loadedRaw: String? = nil
    @State private var saveError: String? = nil
    @State private var didJustSave: Bool = false
    @State private var showRestoreConfirm: Bool = false

    var body: some View {
        Form {
            Section {
                editor
            } header: {
                Text(AppLocalized("System Prompt Prose"))
            } footer: {
                lengthFooter
            }

            Section {
                Button(role: .destructive) {
                    showRestoreConfirm = true
                } label: {
                    Label(AppLocalized("Reset to Default"), systemImage: "arrow.uturn.backward")
                }
            } footer: {
                Text(AppLocalized("Only the static prose of the system prompt is editable here. The Soul identity, the tool list, skill and MCP fragments, and the runtime context are supplied by the app and cannot be changed."))
            }

            if let saveError {
                Section {
                    Text(saveError)
                        .font(.footnote)
                        .foregroundStyle(.red)
                }
            }
        }
        .navigationTitle(AppLocalized("System Prompt"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button(AppLocalized("Save")) { save() }
                    .disabled(!isDirty || isOverLimit)
            }
        }
        .onAppear(perform: reload)
        .alert(
            AppLocalized("Reset to Default"),
            isPresented: $showRestoreConfirm
        ) {
            Button(AppLocalized("Reset to Default"), role: .destructive, action: resetDefault)
            Button(AppLocalized("Cancel"), role: .cancel) {}
        } message: {
            Text(AppLocalized("Reset to the default system prompt prose? Your custom text will be deleted and the built-in default resumes."))
        }
    }

    // MARK: - Editor

    private var editor: some View {
        ZStack(alignment: .topLeading) {
            TextEditor(text: $bodyText)
                .font(.system(.body, design: .monospaced))
                .frame(minHeight: 240)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
            if bodyText.isEmpty {
                Text(AppLocalized("Guidance the Agent reads on every turn — runtime notes, directories, file creation, tone…"))
                    .font(.system(.body, design: .monospaced))
                    .foregroundStyle(.tertiary)
                    .padding(.top, 8)
                    .padding(.leading, 5)
                    .allowsHitTesting(false)
            }
        }
    }

    /// Footer under the editor. Green count within budget, red over-limit
    /// message otherwise; Save is disabled in the over-limit branch.
    private var lengthFooter: some View {
        let check = SystemPromptProseStore.isOverLimit(bodyText)
        return HStack(spacing: 6) {
            if check.isOverLimit {
                Image(systemName: "exclamationmark.triangle.fill")
                    .foregroundStyle(.red)
            }
            switch check {
            case .ok:
                Text(countText(bodyText))
                    .foregroundStyle(.secondary)
            case .overLimit(let count, let cap):
                Text(AppLocalized("Over limit: \(count) / \(cap) tokens. Each CJK character and each Latin word counts as one."))
                    .foregroundStyle(.red)
            }
        }
        .font(.footnote)
    }

    private func countText(_ body: String) -> String {
        let count = SoulStore.tokenCount(body)
        return AppLocalized("\(count) / \(SoulStore.bodyTokenLimit) tokens")
    }

    private var isOverLimit: Bool {
        SystemPromptProseStore.isOverLimit(bodyText).isOverLimit
    }

    private var isDirty: Bool {
        // Compare against the raw file text (or the default when there is no
        // override) so the dirty check is a value comparison, not a flag that
        // can drift out of sync.
        let baseline = loadedRaw ?? SystemPromptProseStore.defaultContent
        return bodyText != baseline
    }

    // MARK: - Persistence

    private func reload() {
        bodyText = SystemPromptProseStore.load()
        loadedRaw = SystemPromptProseStore.loadRaw()
        saveError = nil
    }

    private func save() {
        do {
            try SystemPromptProseStore.save(bodyText)
            loadedRaw = SystemPromptProseStore.loadRaw()
            saveError = nil
            withAnimation { didJustSave = true }
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
                withAnimation { didJustSave = false }
            }
        } catch {
            saveError = "Save failed: \(error.localizedDescription)"
        }
    }

    private func resetDefault() {
        SystemPromptProseStore.reset()
        bodyText = SystemPromptProseStore.defaultContent
        loadedRaw = nil
        saveError = nil
    }
}
