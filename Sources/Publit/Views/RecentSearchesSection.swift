import SwiftUI

struct RecentSearchesSection: View {
    var goNow = false
    @Environment(SearchHistoryStore.self) var history
    @Environment(AppState.self) var app
    @State var confirmClear = false

    var body: some View {
        if !history.entries.isEmpty {
            Section {
                ForEach(Array(history.entries.prefix(goNow ? 3 : 5))) { search in
                    Button {
                        if goNow {
                            search.apply(to: app.goNow)
                            app.goNow.timeMode = .now
                            app.showGoNow = true
                        } else {
                            search.apply(to: app.plan)
                            app.plan.searchStarted = true // a past search: run it again
                        }
                    } label: {
                        HStack(alignment: .top, spacing: 12) {
                        Image(systemName: (search.source ?? .search).symbol)
                            .font(.subheadline.weight(.semibold)).foregroundStyle(.secondary)
                            .frame(width: 22).padding(.top, 2)
                            .accessibilityHidden(true)
                        VStack(alignment: .leading, spacing: 4) {
                            Text(search.to.title).font(.subheadline.weight(.semibold)).foregroundStyle(.primary)
                            Text(L10n.f("from %@", search.from.title)).font(.caption).foregroundStyle(.secondary)
                            // To the minute from the shared clock: a `.relative` date redraws every second.
                            Text(verbatim: Fmt.ago(search.searchedAt, now: Ticker.minute.now))
                                .font(.caption2).foregroundStyle(.secondary)
                        }
                        Spacer(minLength: 0)
                        }
                        .padding(.vertical, 3)
                    }
                    .accessibilityIdentifier("recentSearch")
                    .swipeActions { Button("Remove", role: .destructive) { history.remove(search.id) } }
                }
                Button("Clear history", role: .destructive) { confirmClear = true }
                    .font(.subheadline)
            } header: { Text("Recently searched") }
            .confirmationDialog("Clear search history?", isPresented: $confirmClear, titleVisibility: .visible) {
                Button("Clear history", role: .destructive) { history.clear() }
                Button("Cancel", role: .cancel) { }
            }
        }
    }
}
