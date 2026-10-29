#if os(Android)
import SwiftUI
import SkipFuse
import SkipBridge

/// Wraps its children onto new lines when they do not fit (long routes with many changes).
/// Android: Compose's FlowRow (Skip/PublitCompose.kt), which SwiftUI's custom Layout cannot reach.
struct FlowRow<Content: View>: View {
    var spacing: CGFloat = 4
    @ViewBuilder var content: Content

    init(spacing: CGFloat = 4, @ViewBuilder content: () -> Content) {
        self.spacing = spacing
        self.content = content()
    }

    @ViewBuilder var body: some View {
        // No `try!`: a failed reflective call would end the app; a plain stack still shows everything.
        if let flow = try? AnyDynamicObject(className: "publit.module.FlowComposer",
                                            arguments: [Double(spacing), 0.0, content.Java_viewOrEmpty as? JConvertible]) {
            ComposeView { flow }
        } else {
            VStack(alignment: .leading, spacing: spacing) { content }
        }
    }
}

/// Equal-width columns (the action buttons of a trip). Android: FlowRow with a fixed number
/// of equally weighted cells per row.
struct EqualColumns<Content: View>: View {
    var columns: Int
    var spacing: CGFloat
    @ViewBuilder var content: Content

    init(columns: Int, spacing: CGFloat, @ViewBuilder content: () -> Content) {
        self.columns = columns
        self.spacing = spacing
        self.content = content()
    }

    @ViewBuilder var body: some View {
        if let flow = try? AnyDynamicObject(className: "publit.module.FlowComposer",
                                            arguments: [Double(spacing), Double(max(1, columns)), content.Java_viewOrEmpty as? JConvertible]) {
            ComposeView { flow }
        } else {
            VStack(spacing: spacing) { content }
        }
    }
}
#endif
