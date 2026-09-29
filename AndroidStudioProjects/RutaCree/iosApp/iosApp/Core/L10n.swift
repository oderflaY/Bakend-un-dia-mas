import Foundation
import Shared

/// Texts from es.lproj / en.lproj (generated from the shared strings.xml by
/// scripts/generar-textos-ios.py), in the language chosen inside the app.
enum L10n {
    /// BCP-47 tag chosen in Settings; nil follows the phone's language.
    static var languageTag: String?

    private static var bundle: Bundle {
        if let tag = languageTag,
           let path = Bundle.main.path(forResource: tag, ofType: "lproj"),
           let bundle = Bundle(path: path) {
            return bundle
        }
        return .main
    }

    static var locale: Locale {
        languageTag.map(Locale.init(identifier:)) ?? .current
    }

    static func t(_ key: String, _ args: CVarArg...) -> String {
        format(key, args)
    }

    /// Spanish and English use "one" only for exactly 1.
    static func plural(_ key: String, count: Int, _ args: CVarArg...) -> String {
        format(key + (count == 1 ? "#one" : "#other"), args)
    }

    private static func format(_ key: String, _ args: [CVarArg]) -> String {
        let text = bundle.localizedString(forKey: key, value: nil, table: nil)
        return args.isEmpty ? text : String(format: text, locale: locale, arguments: args)
    }
}

/// Same rounding as the Android app (ui/Texts.kt and ui/Guidance.kt).
enum Format {
    /// "80 m", "1.2 km": to the nearest 10 m.
    static func distance(_ meters: Double) -> String {
        if meters < 1_000 {
            return L10n.t("distance_meters", Int((meters / 10).rounded()) * 10)
        }
        return L10n.t("distance_km", kilometers(meters))
    }

    /// Navigation style: steps of 10 m (under 100 m) or 50 m, so it doesn't flicker every fix.
    static func guidanceDistance(_ meters: Double) -> String {
        if meters < 1_000 {
            let step = meters < 100 ? 10 : 50
            return L10n.t("distance_m", max(step, Int((meters / Double(step)).rounded()) * step))
        }
        return L10n.t("distance_km", kilometers(meters))
    }

    static func minutes(_ seconds: Double) -> String {
        L10n.t("duration_min", max(1, Int((seconds / 60).rounded(.up))))
    }

    private static func kilometers(_ meters: Double) -> String {
        let tenths = Int((meters / 100).rounded())
        return "\(tenths / 10).\(tenths % 10)"
    }

    static func busName(_ bus: BusPosition) -> String {
        bus.name ?? L10n.t("bus_unit", String(bus.deviceId))
    }

    static func clock(_ minuteOfDay: Int32) -> String {
        TransitKt.formatClock(minuteOfDay: minuteOfDay)
    }

    static func compass(_ compass: Compass) -> String {
        switch compass.name {
        case "North": return L10n.t("compass_north")
        case "NorthEast": return L10n.t("compass_north_east")
        case "East": return L10n.t("compass_east")
        case "SouthEast": return L10n.t("compass_south_east")
        case "South": return L10n.t("compass_south")
        case "SouthWest": return L10n.t("compass_south_west")
        case "West": return L10n.t("compass_west")
        default: return L10n.t("compass_north_west")
        }
    }

    /// "Gira a la derecha en Av. Juárez": the maneuver, plus the street when it has a name.
    static func instruction(_ step: DirectionStep) -> String {
        let key: String
        switch step.maneuver.name {
        case "Straight": key = "maneuver_straight"
        case "SlightLeft": key = "maneuver_slight_left"
        case "Left": key = "maneuver_left"
        case "SharpLeft": key = "maneuver_sharp_left"
        case "SlightRight": key = "maneuver_slight_right"
        case "Right": key = "maneuver_right"
        case "SharpRight": key = "maneuver_sharp_right"
        case "UTurn": key = "maneuver_uturn"
        case "Roundabout": key = "maneuver_roundabout"
        default: key = "maneuver_arrive"
        }
        let action = L10n.t(key)
        if step.street.isEmpty || step.maneuver.name == "Arrive" { return action }
        return L10n.t("approach_street", action, step.street)
    }

    /// SF Symbol for a maneuver (mirrors `Maneuver?.icon` in Kotlin).
    static func maneuverSymbol(_ maneuver: Maneuver?, byStreet: Bool) -> String {
        guard let maneuver else { return byStreet ? "arrow.up" : "location.north.line.fill" }
        switch maneuver.name {
        case "Straight": return "arrow.up"
        case "SlightLeft": return "arrow.up.left"
        case "Left", "SharpLeft": return "arrow.turn.up.left"
        case "SlightRight": return "arrow.up.right"
        case "Right", "SharpRight": return "arrow.turn.up.right"
        case "UTurn": return "arrow.uturn.down"
        case "Roundabout": return "arrow.triangle.2.circlepath"
        default: return "flag.checkered"
        }
    }

    static func accountError(_ error: AccountError) -> String {
        switch error.name {
        case "InvalidCredentials": return L10n.t("err_invalid_credentials")
        case "Locked": return L10n.t("err_locked")
        case "RateLimited": return L10n.t("err_rate_limited")
        case "EmailTaken": return L10n.t("err_email_taken")
        case "WeakPassword": return L10n.t("err_weak_password")
        case "InvalidEmail": return L10n.t("err_invalid_email")
        case "MissingName": return L10n.t("err_missing_name")
        case "Network": return L10n.t("err_network")
        case "SessionExpired": return L10n.t("err_session_expired")
        default: return L10n.t("err_unknown")
        }
    }

    static func incidentTitle(_ incident: Incident) -> String {
        L10n.t(incident.type.name == "Accident" ? "incident_title_accident" : "incident_title_breakdown")
    }

    /// "Ruta 2 - Centro" -> "Ruta 2".
    static func shortName(_ route: TransitRoute) -> String {
        route.name.components(separatedBy: " - ").first ?? route.name
    }
}
