#if os(Android)
import Foundation
import SwiftUI
import SkipFuse
import SkipBridge

/// Wraps its children onto new lines when they do not fit (long routes with many changes).
/// Android: Compose's FlowRow (Skip/PublitCompose.kt), which SwiftUI's custom Layout cannot reach.
struct FlowRow<Content: View>: View {
    var spacing: SwiftUI.CGFloat = 4
    @ViewBuilder var content: Content

    init(spacing: SwiftUI.CGFloat = 4, @ViewBuilder content: () -> Content) {
        self.spacing = spacing
        self.content = content()
    }

    var body: some View {
        ComposeView {
            let child = content.Java_viewOrEmpty as? JConvertible
            return try! AnyDynamicObject(className: "publit.module.FlowComposer", arguments: [Double(spacing), child])
        }
    }
}
#endif
