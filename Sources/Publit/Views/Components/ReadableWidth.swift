import SwiftUI

/// Keeps scrolling lists at a comfortable reading width on wide screens: iPad, iPad Split View and
/// the inner display of iPhone Duo. In compact width (every iPhone, narrow Split View) nothing changes,
/// so the iPhone layout stays exactly as it is.
struct ReadableWidth: ViewModifier {
    var maxWidth: CGFloat
    @Environment(\.horizontalSizeClass) var sizeClass
    @State var width: CGFloat = 0

    private var inset: CGFloat? {
        guard sizeClass == .regular, width > maxWidth + 40 else { return nil }
        return ((width - maxWidth) / 2).rounded()
    }

    func body(content: Content) -> some View {
        content
            .contentMargins(.horizontal, inset, for: .scrollContent)
            .background {
                GeometryReader { proxy in
                    Color.clear
                        .onAppear { width = proxy.size.width }
                        .onChange(of: proxy.size.width) { _, new in width = new }
                }
            }
    }
}

extension View {
    /// Centers list content in a column of at most `maxWidth` points when the width is regular.
    func readableWidth(_ maxWidth: CGFloat = 720) -> some View {
        modifier(ReadableWidth(maxWidth: maxWidth))
    }
}
