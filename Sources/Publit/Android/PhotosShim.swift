#if os(Android)
import SwiftUI

// Android: the part of PhotosUI Publit uses (choose one image), on the system photo picker.

struct PHPickerFilter: Sendable {
    static let images = PHPickerFilter()
}

/// A chosen photo: a copy in the app's cache.
struct PhotosPickerItem: Equatable, Hashable, Sendable {
    let url: URL

    func loadTransferable(type: Data.Type) async throws -> Data? {
        let url = url
        return try await Task.detached(priority: .userInitiated) { try Data(contentsOf: url) }.value
    }
}

struct PhotosPicker<Label: View>: View {
    @Binding var selection: PhotosPickerItem?
    let label: Label

    init(selection: Binding<PhotosPickerItem?>, matching filter: PHPickerFilter? = nil, @ViewBuilder label: () -> Label) {
        _selection = selection
        self.label = label()
    }

    var body: some View {
        Button {
            Task { @MainActor in
                if let url = await DroidPicker.image() { selection = PhotosPickerItem(url: url) }
            }
        } label: { label }
    }
}

extension View {
    func photosPicker(isPresented: Binding<Bool>, selection: Binding<PhotosPickerItem?>, matching filter: PHPickerFilter? = nil) -> some View {
        onChange(of: isPresented.wrappedValue) { _, on in
            guard on else { return }
            Task { @MainActor in
                let url = await DroidPicker.image()
                isPresented.wrappedValue = false
                if let url { selection.wrappedValue = PhotosPickerItem(url: url) }
            }
        }
    }
}
#endif
