import SwiftUI

/// Shown for a moment at launch while location and the first departures load underneath:
/// the "p" of the logo is drawn like a line on a map, then its stop appears.
struct LaunchSplash: View {
    @Environment(\.accessibilityReduceMotion) var reduceMotion
    @State var drawn: CGFloat = 0
    @State var stop = false

    var body: some View {
        ZStack {
            Color(.systemBackground).ignoresSafeArea()
            VStack(spacing: 16) {
                GeometryReader { geo in
                    let s = min(geo.size.width, geo.size.height) / 1024
                    ZStack {
                        PublitMarkLine()
                            .trim(from: 0, to: drawn)
                            .stroke(.white, style: StrokeStyle(lineWidth: 96 * s, lineCap: .round, lineJoin: .round))
                        Circle()
                            .fill(Color(red: 1, green: 0.773, blue: 0.192))
                            .frame(width: 116 * s, height: 116 * s)
                            .scaleEffect(stop ? 1 : 0.1)
                            .opacity(stop ? 1 : 0)
                            .position(x: geo.size.width / 2, y: geo.size.height / 2 + (402 - 512) * s)
                    }
                }
                .frame(width: 112, height: 112)
                .background(Color(red: 0.067, green: 0.067, blue: 0.067),
                            in: RoundedRectangle(cornerRadius: 112 * 0.225, style: .continuous))
            }
        }
        .accessibilityHidden(true)
        .onAppear {
            if reduceMotion {
                drawn = 1
                stop = true
            } else {
                withAnimation(.easeInOut(duration: 0.55)) { drawn = 1 }
                withAnimation(.spring(response: 0.35, dampingFraction: 0.6).delay(0.45)) { stop = true }
            }
        }
    }
}

/// The line of the "p" (same geometry as `PublitLogo`, 1024-unit grid), drawn from the bottom up.
struct PublitMarkLine: Shape {
    func path(in rect: CGRect) -> Path {
        let s = min(rect.width, rect.height) / 1024
        let ox = rect.minX + (rect.width - 1024 * s) / 2
        let oy = rect.minY + (rect.height - 1024 * s) / 2
        func pt(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: ox + x * s, y: oy + y * s) }
        var p = Path()
        p.move(to: pt(362, 772))
        p.addLine(to: pt(362, 402))
        p.addArc(center: pt(512, 402), radius: 150 * s, startAngle: .degrees(180), endAngle: .degrees(90), clockwise: false)
        p.addLine(to: pt(362, 552))
        return p
    }
}

#if !os(Android)
#Preview {
    LaunchSplash()
}
#endif
