import MapKit
import Shared
import SwiftUI

/// A bus pin on the map.
struct MapBusPin: Identifiable {
    let id: Int64
    let point: GeoPoint
    let highlighted: Bool
}

/// Everything the map draws; mirrors MapContent in Kotlin (map/TransitMap.kt).
struct MapModel {
    var route: TransitRoute?
    var stops: [Stop] = []
    var selectedStopId: String?
    var buses: [MapBusPin] = []
    /// The rider's GPS position, drawn as a blue dot.
    var user: GeoPoint?
    /// Where the camera centers (the selected stop, or the driver's bus).
    var focus: GeoPoint?
    /// Web-map zoom level around `focus`; nil keeps a neighbourhood view.
    var zoom: Double?
    /// Street path to follow on foot or to join the route, drawn dashed.
    var guidance: [GeoPoint] = []
    /// Points the camera keeps in view; when set it wins over `focus`.
    var frame: [GeoPoint] = []
}

/// Native Apple map (MapKit). Visual only: each screen describes it in words for VoiceOver.
struct TransitMapView: View {
    @Environment(\.neu) private var neu
    let model: MapModel
    let accessibilityDescription: String
    @State private var position: MapCameraPosition = .automatic

    var body: some View {
        Map(position: $position, interactionModes: [.pan, .zoom]) {
            if let route = model.route {
                // Return first, so the outbound color wins where both legs share a street.
                if !route.inbound.isEmpty {
                    MapPolyline(coordinates: route.inbound.map(\.coordinate))
                        .stroke(Color(hexString: DirectionLegend.inboundHex), style: StrokeStyle(lineWidth: 7, lineCap: .round, lineJoin: .round))
                }
                MapPolyline(coordinates: route.outbound.map(\.coordinate))
                    .stroke(Color(hexString: DirectionLegend.outboundHex), style: StrokeStyle(lineWidth: 7, lineCap: .round, lineJoin: .round))
            }
            if model.guidance.count >= 2 {
                MapPolyline(coordinates: model.guidance.map(\.coordinate))
                    .stroke(neu.blueOnBg, style: StrokeStyle(lineWidth: 5, lineCap: .round, dash: [9, 8]))
            }
            ForEach(model.stops, id: \.id) { stop in
                Annotation(stop.name, coordinate: stop.point.coordinate, anchor: .center) {
                    StopDot(
                        selected: stop.id == model.selectedStopId,
                        ring: Color(hexString: model.route?.colorHex ?? "#2F5DA8")
                    )
                }
                .annotationTitles(.hidden)
            }
            if let user = model.user {
                Annotation("", coordinate: user.coordinate, anchor: .center) { UserDot() }
            }
            ForEach(model.buses) { bus in
                Annotation("", coordinate: bus.point.coordinate, anchor: .center) {
                    BusMarker(highlighted: bus.highlighted)
                }
            }
        }
        .mapStyle(.standard(pointsOfInterest: .excludingAll))
        .mapControlVisibility(.hidden)
        .onAppear { position = camera }
        .onChange(of: cameraKey) {
            withAnimation(.easeInOut(duration: 0.9)) { position = camera }
        }
        .overlay(alignment: .topTrailing) { DirectionLegend(route: model.route).padding(10) }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityDescription)
    }

    /// Changes only when the camera should move (another stop, the rider left the path...).
    private var cameraKey: String {
        func rounded(_ p: GeoPoint?, _ digits: Double) -> String {
            guard let p else { return "-" }
            return "\((p.lat * digits).rounded()),\((p.lon * digits).rounded())"
        }
        return [
            model.route?.id ?? "-",
            rounded(model.focus, 10_000),
            model.zoom.map { String($0) } ?? "-",
            rounded(model.frame.first, 1_000),
            rounded(model.frame.last, 1_000),
        ].joined(separator: "|")
    }

    private var camera: MapCameraPosition {
        if !model.frame.isEmpty { return .rect(Self.boundingRect(model.frame)) }
        if let focus = model.focus {
            // Roughly what a web map shows at that zoom on a phone.
            let meters = 40_075_016 / pow(2, model.zoom ?? 15) * 1.5
            return .region(MKCoordinateRegion(center: focus.coordinate, latitudinalMeters: meters, longitudinalMeters: meters))
        }
        if let route = model.route { return .rect(Self.boundingRect(route.geometry.points)) }
        return .automatic
    }

    /// The points plus a margin, at least a few hundred meters across.
    private static func boundingRect(_ points: [GeoPoint]) -> MKMapRect {
        let mapPoints = points.map { MKMapPoint($0.coordinate) }
        let minX = mapPoints.map(\.x).min() ?? 0
        let maxX = mapPoints.map(\.x).max() ?? 0
        let minY = mapPoints.map(\.y).min() ?? 0
        let maxY = mapPoints.map(\.y).max() ?? 0
        let minSide = 400 * MKMapPointsPerMeterAtLatitude(points.first?.lat ?? 0)
        let width = max(maxX - minX, minSide)
        let height = max(maxY - minY, minSide)
        let rect = MKMapRect(x: (minX + maxX - width) / 2, y: (minY + maxY - height) / 2, width: width, height: height)
        return rect.insetBy(dx: -width * 0.18, dy: -height * 0.18)
    }
}

/// Key for the two line colors; only for routes that come back (have a return leg).
struct DirectionLegend: View {
    /// Same colors as OUTBOUND_COLOR_HEX / INBOUND_COLOR_HEX in transit/Transit.kt.
    static let outboundHex = "#2F5DA8"
    static let inboundHex = "#F19025"
    @Environment(\.neu) private var neu
    let route: TransitRoute?

    var body: some View {
        if route?.returnStartIndex != nil {
            HStack(spacing: 12) {
                item(Self.outboundHex, L10n.t("map_outbound"))
                item(Self.inboundHex, L10n.t("map_inbound"))
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .neuCard(Capsule(), distance: 4)
        }
    }

    private func item(_ hex: String, _ label: String) -> some View {
        HStack(spacing: 6) {
            Capsule().fill(Color(hexString: hex)).frame(width: 18, height: 5)
            Text(label).font(.system(size: 13, weight: .semibold)).foregroundStyle(neu.text)
        }
    }
}

/// Light dot ringed in the route color; mint and larger when selected.
struct StopDot: View {
    @Environment(\.neu) private var neu
    let selected: Bool
    let ring: Color

    var body: some View {
        ZStack {
            if selected {
                Circle().fill(neu.mint.opacity(0.45)).frame(width: 34, height: 34)
            }
            Circle()
                .fill(selected ? neu.mint : neu.stopFill)
                .overlay(Circle().stroke(ring, lineWidth: 3))
                .frame(width: selected ? 18 : 13, height: selected ? 18 : 13)
        }
    }
}

struct UserDot: View {
    @Environment(\.neu) private var neu

    var body: some View {
        ZStack {
            Circle().fill(neu.blue.opacity(0.3)).frame(width: 30, height: 30)
            Circle().fill(neu.stopFill).frame(width: 17, height: 17)
            Circle().fill(neu.blueOnBg).frame(width: 11, height: 11)
        }
    }
}

/// Soft-neumorphic bus pin, like `BusMarker` in Compose.
struct BusMarker: View {
    @Environment(\.neu) private var neu
    let highlighted: Bool

    var body: some View {
        Image(systemName: "bus.fill")
            .font(.system(size: highlighted ? 24 : 19, weight: .semibold))
            .foregroundStyle(neu.blue)
            .frame(width: highlighted ? 50 : 42, height: highlighted ? 50 : 42)
            .neuCard(Circle(), distance: highlighted ? 4 : 3)
    }
}

extension GeoPoint {
    var coordinate: CLLocationCoordinate2D { CLLocationCoordinate2D(latitude: lat, longitude: lon) }
}
