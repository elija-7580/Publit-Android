import SwiftUI

/// The Publit mark drawn in SwiftUI: a line "p" with a yellow stop in its bowl.
/// Same geometry as docs/brand/glyph.svg (1024-unit grid).
struct PublitLogo: View {
    var line: Color = .white

    var body: some View {
        #if os(Android)
        // No Canvas on Android: the same geometry as a stroked shape and a circle.
        GeometryReader { geo in
            let side = min(geo.size.width, geo.size.height)
            let s = side / 1024
            ZStack {
                PublitLogoLine()
                    .stroke(line, style: StrokeStyle(lineWidth: 96 * s, lineCap: .round, lineJoin: .round))
                Circle()
                    .fill(Color(red: 1, green: 0.773, blue: 0.192))
                    .frame(width: 116 * s, height: 116 * s)
                    .offset(x: 0, y: (402 - 512) * s)
            }
            .frame(width: side, height: side)
            .frame(width: geo.size.width, height: geo.size.height)
        }
        .accessibilityHidden(true)
        #else
        Canvas { context, size in
            let s = min(size.width, size.height) / 1024
            let ox = (size.width - 1024 * s) / 2
            let oy = (size.height - 1024 * s) / 2
            func pt(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: ox + x * s, y: oy + y * s) }

            var p = Path()
            p.move(to: pt(362, 772))
            p.addLine(to: pt(362, 402))
            p.addArc(center: pt(512, 402), radius: 150 * s,
                     startAngle: .degrees(180), endAngle: .degrees(90), clockwise: false)
            p.addLine(to: pt(362, 552))
            context.stroke(p, with: .color(line),
                           style: StrokeStyle(lineWidth: 96 * s, lineCap: .round, lineJoin: .round))

            let r = 58 * s
            let dot = Path(ellipseIn: CGRect(x: ox + 512 * s - r, y: oy + 402 * s - r, width: 2 * r, height: 2 * r))
            context.fill(dot, with: .color(Color(red: 1, green: 0.773, blue: 0.192)))
        }
        .accessibilityHidden(true)
        #endif
    }
}

#if os(Android)
/// The "p" line of the mark in a 1024-unit square.
struct PublitLogoLine: Shape {
    func path(in rect: CGRect) -> Path {
        let side = min(rect.width, rect.height)
        let s = side / 1024
        let ox = rect.minX + (rect.width - side) / 2
        let oy = rect.minY + (rect.height - side) / 2
        func pt(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: ox + x * s, y: oy + y * s) }
        var p = Path()
        p.move(to: pt(362, 772))
        p.addLine(to: pt(362, 402))
        p.addArc(center: pt(512, 402), radius: 150 * s, startAngle: .degrees(180), endAngle: .degrees(90), clockwise: false)
        p.addLine(to: pt(362, 552))
        return p
    }
}
#endif

/// App icon look: the mark on the dark rounded square.
struct PublitAppIcon: View {
    var size: CGFloat = 56

    var body: some View {
        PublitLogo()
            .frame(width: size, height: size)
            .background(Color(red: 0.067, green: 0.067, blue: 0.067),
                        in: RoundedRectangle(cornerRadius: size * 0.225, style: .continuous))
    }
}

#if !os(Android)
#Preview {
    PublitAppIcon(size: 120)
}
#endif
