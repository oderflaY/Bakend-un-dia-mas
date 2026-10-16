import SwiftUI
import UIKit

/// Soft-neumorphic palette; mirrors NeuPalette in Kotlin (ui/Neumorphism.kt).
struct NeuPalette {
    let isDark: Bool
    let bg: Color
    let shadowDark: Color
    let shadowLight: Color
    let text: Color
    let textMuted: Color
    let mint: Color
    let blue: Color
    let mintOnBg: Color
    let blueOnBg: Color
    let chipBg: Color
    let chipText: Color
    let onBlue: Color
    let mapGrid: Color
    let stopFill: Color

    static let light = NeuPalette(
        isDark: false,
        bg: Color(hex: 0xE6ECF5), shadowDark: Color(hex: 0xC5CBD5), shadowLight: .white,
        text: Color(hex: 0x2C3E50), textMuted: Color(hex: 0x5A6C7D),
        // Colores DIF Durango: azul principal, magenta secundario (igual que Neumorphism.kt).
        mint: Color(hex: 0xD91178), blue: Color(hex: 0x2F5DA8),
        mintOnBg: Color(hex: 0xB80E66), blueOnBg: Color(hex: 0x2A55A0),
        chipBg: Color(hex: 0xF7D3E5), chipText: Color(hex: 0x9C1866),
        onBlue: .white, mapGrid: Color(hex: 0xF3F6FA), stopFill: .white
    )

    static let dark = NeuPalette(
        isDark: true,
        bg: Color(hex: 0x292D32), shadowDark: Color(hex: 0x1A1D20), shadowLight: Color(hex: 0x383D44),
        text: Color(hex: 0xE6ECF5), textMuted: Color(hex: 0xA9B4C2),
        mint: Color(hex: 0xF56AAE), blue: Color(hex: 0x7FA6EE),
        mintOnBg: Color(hex: 0xF56AAE), blueOnBg: Color(hex: 0x7FA6EE),
        chipBg: Color(hex: 0xD91178).opacity(0.2), chipText: Color(hex: 0xF56AAE),
        onBlue: Color(hex: 0x0D1B33), mapGrid: Color(hex: 0x33383E), stopFill: Color(hex: 0x292D32)
    )
}

/// High-contrast alert colors, legible in both themes.
enum AlertColors {
    static let danger = Color(hex: 0xE31E31)
    static let warning = Color(hex: 0xF8C817)
    static let onWarning = Color(hex: 0x1C1C1C)
    static let onDanger = Color.white
}

private struct PaletteKey: EnvironmentKey {
    static let defaultValue = NeuPalette.light
}

extension EnvironmentValues {
    var neu: NeuPalette {
        get { self[PaletteKey.self] }
        set { self[PaletteKey.self] = newValue }
    }
}

extension Color {
    init(hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }

    /// "#8ECAE6" as used by the route files.
    init(hexString: String) {
        self.init(hex: UInt32(hexString.trimmingCharacters(in: CharacterSet(charactersIn: "#")), radix: 16) ?? 0x2F5DA8)
    }
}

// MARK: - Surfaces

/// Extruded surface (`.neumorphic-card`).
struct NeuCard<S: Shape>: ViewModifier {
    @Environment(\.neu) private var neu
    var shape: S
    var distance: CGFloat = 8
    var fill: Color?

    func body(content: Content) -> some View {
        content.background(
            shape
                .fill(fill ?? neu.bg)
                .shadow(color: neu.shadowDark, radius: distance, x: distance, y: distance)
                .shadow(color: neu.shadowLight, radius: distance, x: -distance, y: -distance)
        )
    }
}

/// Pressed-in surface (`.neumorphic-inset`).
struct NeuInset<S: Shape>: ViewModifier {
    @Environment(\.neu) private var neu
    var shape: S
    var distance: CGFloat = 4

    func body(content: Content) -> some View {
        content.background(
            shape
                .fill(neu.bg)
                .overlay(
                    shape
                        .stroke(neu.shadowDark, lineWidth: distance * 1.5)
                        .blur(radius: distance)
                        .offset(x: distance / 2, y: distance / 2)
                        .mask(shape.fill(LinearGradient(colors: [.black, .clear], startPoint: .topLeading, endPoint: .bottomTrailing)))
                )
                .overlay(
                    shape
                        .stroke(neu.shadowLight, lineWidth: distance * 1.5)
                        .blur(radius: distance)
                        .offset(x: -distance / 2, y: -distance / 2)
                        .mask(shape.fill(LinearGradient(colors: [.clear, .black], startPoint: .topLeading, endPoint: .bottomTrailing)))
                )
                .clipShape(shape)
        )
    }
}

enum Shapes {
    static let card = RoundedRectangle(cornerRadius: 16, style: .continuous)
    static let inset = RoundedRectangle(cornerRadius: 12, style: .continuous)
    static let pill = Capsule()
}

extension View {
    func neuCard(distance: CGFloat = 8, fill: Color? = nil) -> some View {
        modifier(NeuCard(shape: Shapes.card, distance: distance, fill: fill))
    }

    func neuCard<S: Shape>(_ shape: S, distance: CGFloat = 8, fill: Color? = nil) -> some View {
        modifier(NeuCard(shape: shape, distance: distance, fill: fill))
    }

    func neuInset(distance: CGFloat = 4) -> some View {
        modifier(NeuInset(shape: Shapes.inset, distance: distance))
    }

    func neuInset<S: Shape>(_ shape: S, distance: CGFloat = 4) -> some View {
        modifier(NeuInset(shape: shape, distance: distance))
    }
}

// MARK: - Controls

/// Raised button that sinks while pressed. `fill` nil uses the background color.
struct NeuButtonStyle<S: Shape>: ButtonStyle {
    @Environment(\.neu) private var neu
    @Environment(\.isEnabled) private var isEnabled
    var shape: S
    var fill: Color?
    var minHeight: CGFloat = 56

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .frame(maxWidth: .infinity, minHeight: minHeight)
            .padding(.horizontal, 20)
            .padding(.vertical, 10)
            .modifier(Surface(pressed: configuration.isPressed, shape: shape, fill: fill ?? neu.bg))
            .contentShape(shape)
            .opacity(isEnabled ? 1 : 0.5)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }

    private struct Surface: ViewModifier {
        let pressed: Bool
        let shape: S
        let fill: Color

        func body(content: Content) -> some View {
            if pressed {
                content.background(shape.fill(fill)).modifier(NeuInset(shape: shape, distance: 4))
            } else {
                content.modifier(NeuCard(shape: shape, distance: 6, fill: fill))
            }
        }
    }
}

extension ButtonStyle where Self == NeuButtonStyle<RoundedRectangle> {
    static var neu: NeuButtonStyle<RoundedRectangle> { NeuButtonStyle(shape: Shapes.card, fill: nil) }
    static func neu(fill: Color?, minHeight: CGFloat = 56) -> NeuButtonStyle<RoundedRectangle> {
        NeuButtonStyle(shape: Shapes.card, fill: fill, minHeight: minHeight)
    }
}

extension ButtonStyle where Self == NeuButtonStyle<Capsule> {
    static var neuPill: NeuButtonStyle<Capsule> { NeuButtonStyle(shape: Shapes.pill, fill: nil) }
    static func neuPill(fill: Color?, minHeight: CGFloat = 52) -> NeuButtonStyle<Capsule> {
        NeuButtonStyle(shape: Shapes.pill, fill: fill, minHeight: minHeight)
    }
}

/// Round icon button (profile shortcut on the Radar).
struct NeuIconButton: View {
    @Environment(\.neu) private var neu
    let systemImage: String
    let label: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 20, weight: .semibold))
                .foregroundStyle(neu.blueOnBg)
                .frame(width: 52, height: 52)
                .neuCard(Circle(), distance: 5)
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

/// Text field sunk into the surface, with its label above. Passwords can be revealed.
struct NeuTextField: View {
    @Environment(\.neu) private var neu
    let label: String
    let placeholder: String
    @Binding var text: String
    var keyboard: UIKeyboardType = .default
    var contentType: UITextContentType?
    var secret = false
    var multiline = false
    var submitLabel: SubmitLabel = .done
    var onSubmit: () -> Void = {}
    @State private var revealed = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            FieldLabel(label)
            HStack(spacing: 4) {
                Group {
                    if secret && !revealed {
                        SecureField(placeholder, text: $text)
                    } else if multiline {
                        TextField(placeholder, text: $text, axis: .vertical).lineLimit(3...8)
                    } else {
                        TextField(placeholder, text: $text)
                    }
                }
                .font(.system(size: 17))
                .foregroundStyle(neu.text)
                .keyboardType(keyboard)
                .textContentType(contentType)
                .textInputAutocapitalization(secret || keyboard == .emailAddress ? .never : .sentences)
                .autocorrectionDisabled(secret || keyboard == .emailAddress)
                .submitLabel(submitLabel)
                .onSubmit(onSubmit)
                .accessibilityLabel(label)
                .padding(.vertical, 16)

                if secret {
                    Button {
                        revealed.toggle()
                    } label: {
                        Image(systemName: revealed ? "eye.slash" : "eye")
                            .foregroundStyle(neu.textMuted)
                            .frame(width: 48, height: 48)
                    }
                    .accessibilityLabel(L10n.t(revealed ? "hide_password" : "show_password"))
                }
            }
            .padding(.leading, 18)
            .padding(.trailing, secret ? 4 : 18)
            .frame(minHeight: 56)
            .neuInset()
        }
    }
}

/// Mutually exclusive options: raised when available, sunk when chosen.
struct NeuSegmented<T: Hashable>: View {
    @Environment(\.neu) private var neu
    let options: [T]
    let selected: T?
    let label: (T) -> String
    let onSelect: (T) -> Void

    var body: some View {
        HStack(spacing: 12) {
            ForEach(options, id: \.self) { option in
                let isSelected = option == selected
                Button {
                    onSelect(option)
                } label: {
                    Text(label(option))
                        .font(.system(size: 15, weight: isSelected ? .bold : .medium))
                        .foregroundStyle(isSelected ? neu.blueOnBg : neu.text)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity, minHeight: 56)
                        .padding(.horizontal, 6)
                        .modifier(SegmentSurface(selected: isSelected))
                        .contentShape(Shapes.card)
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(isSelected ? [.isSelected] : [])
            }
        }
    }

    private struct SegmentSurface: ViewModifier {
        let selected: Bool
        func body(content: Content) -> some View {
            if selected { content.neuInset() } else { content.neuCard(distance: 5) }
        }
    }
}

struct FieldLabel: View {
    @Environment(\.neu) private var neu
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text)
            .font(.system(size: 15, weight: .semibold))
            .foregroundStyle(neu.text)
            .padding(.leading, 4)
    }
}

struct SectionTitle: View {
    @Environment(\.neu) private var neu
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text.uppercased(with: L10n.locale))
            .font(.system(size: 13, weight: .bold))
            .tracking(1)
            .foregroundStyle(neu.textMuted)
            .padding(.leading, 4)
            .padding(.top, 28)
            .padding(.bottom, 14)
            .accessibilityAddTraits(.isHeader)
    }
}

struct ScreenHeader: View {
    @Environment(\.neu) private var neu
    let title: String
    var subtitle: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.system(size: 26, weight: .bold))
                .foregroundStyle(neu.text)
                .accessibilityAddTraits(.isHeader)
            if let subtitle {
                Text(subtitle).font(.system(size: 15)).foregroundStyle(neu.textMuted)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.top, 12)
    }
}

struct MintChip: View {
    @Environment(\.neu) private var neu
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: "checkmark").font(.system(size: 14, weight: .bold))
            Text(text).font(.system(size: 14, weight: .bold))
        }
        .foregroundStyle(neu.chipText)
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(Capsule().fill(neu.chipBg))
        .accessibilityElement(children: .combine)
    }
}

/// Persistent strip for problems (offline, errors); announced when it appears.
struct StatusBanner: View {
    let text: String
    init(_ text: String) { self.text = text }

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "exclamationmark.triangle.fill").font(.system(size: 15))
            Text(text).font(.system(size: 14, weight: .bold)).fixedSize(horizontal: false, vertical: true)
        }
        .foregroundStyle(AlertColors.onWarning)
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(AlertColors.warning))
        .accessibilityElement(children: .combine)
        .onAppear { UIAccessibility.post(notification: .announcement, argument: text) }
    }
}

/// A whole row that toggles, so VoiceOver reads title, description and state as one control.
struct AssistanceToggle: View {
    @Environment(\.neu) private var neu
    let systemImage: String
    let title: String
    let description: String
    let isOn: Bool
    let onChange: (Bool) -> Void

    var body: some View {
        Toggle(isOn: Binding(get: { isOn }, set: onChange)) {
            HStack(spacing: 14) {
                Image(systemName: systemImage)
                    .font(.system(size: 22))
                    .foregroundStyle(isOn ? neu.blueOnBg : neu.textMuted)
                    .frame(width: 48, height: 48)
                    .neuInset(Circle(), distance: 3)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.system(size: 17, weight: .semibold)).foregroundStyle(neu.text)
                    Text(description).font(.system(size: 14)).foregroundStyle(neu.textMuted)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .tint(neu.mint)
        .padding(16)
        .neuCard(distance: 6)
    }
}

/// Screen background plus the standard horizontal padding and scroll.
struct NeuScroll<Content: View>: View {
    @Environment(\.neu) private var neu
    var spacing: CGFloat = 0
    @ViewBuilder let content: () -> Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: spacing) { content() }
                .padding(.horizontal, 20)
                .padding(.vertical, 8)
                .padding(.bottom, 24)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(neu.bg.ignoresSafeArea())
    }
}
