import SwiftUI

/// Localized duration with large tabular figures and quiet units, not a hard-coded "min" suffix.
struct DurationFigure: View {
    let seconds: Int
    @ScaledMetric(relativeTo: .title2) var size: CGFloat = 28

    init(seconds: Int, size: CGFloat = 28) {
        self.seconds = seconds
        _size = ScaledMetric(wrappedValue: size, relativeTo: .title2)
    }

    var body: some View {
        #if os(Android)
        StyledText(runs)
            .lineLimit(1).minimumScaleFactor(0.7)
            .accessibilityLabel(Text(verbatim: Fmt.duration(seconds: seconds)))
        #else
        Text(styled)
            .lineLimit(1).minimumScaleFactor(0.7)
            .accessibilityLabel(Text(verbatim: Fmt.duration(seconds: seconds)))
        #endif
    }

    #if os(Android)
    private var runs: [TextRun] {
        var result: [TextRun] = []
        var group = ""
        var numeric = false
        func append() {
            guard !group.isEmpty else { return }
            result.append(numeric ? TextRun(group, color: Color.ink, size: size, weight: .bold)
                                  : TextRun(group, color: Color.secondary, size: size * 0.5, weight: .medium))
        }
        for character in Fmt.duration(seconds: seconds) {
            if character.isNumber != numeric { append(); group = ""; numeric = character.isNumber }
            group.append(character)
        }
        append()
        return result
    }
    #else

    private var styled: AttributedString {
        var result = AttributedString()
        var group = ""
        var numeric = false
        func append() {
            guard !group.isEmpty else { return }
            var part = AttributedString(group)
            // The app's own face with tabular digits (not SF Mono), so it matches the times next to it.
            part.font = .system(size: numeric ? size : size * 0.5, weight: numeric ? .bold : .medium).monospacedDigit()
            part.foregroundColor = numeric ? Color.ink : Color.secondary
            result += part
        }
        for character in Fmt.duration(seconds: seconds) {
            if character.isNumber != numeric { append(); group = ""; numeric = character.isNumber }
            group.append(character)
        }
        append()
        return result
    }
    #endif
}
