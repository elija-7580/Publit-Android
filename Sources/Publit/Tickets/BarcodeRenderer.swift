#if canImport(UIKit)
import CoreImage
#if canImport(CoreImage)
import CoreImage.CIFilterBuiltins
#endif
#if canImport(UIKit)
import UIKit
#endif

/// The original ticket a code was read from, for codes shown cut out of it.
enum BarcodeSource: @unchecked Sendable {
    case file(URL, TicketPass.Document.Kind, page: Int)
    case data(Data, TicketPass.Document.Kind, page: Int)
}

/// Draws a stored code again: black on white, every module a whole number of screen pixels
/// (no smoothing), so scanners read it like the original. The archived Vision descriptor
/// reproduces the scanned symbol exactly (version, error correction, mask, binary content).
/// Codes Publit cannot draw (EAN, ITF, Code 39 …) are cut out of the original instead.
enum BarcodeRenderer {
    private static let context = CIContext(options: [.useSoftwareRenderer: false])

    struct Rendered {
        let image: UIImage
        /// Drawn at screen pixels: show at its own size, without scaling.
        let exact: Bool
    }

    static func ciImage(_ code: TicketPass.Barcode) -> CIImage? {
        let classes: [AnyClass] = [CIBarcodeDescriptor.self, CIQRCodeDescriptor.self, CIAztecCodeDescriptor.self,
                                   CIPDF417CodeDescriptor.self, CIDataMatrixCodeDescriptor.self, NSData.self, NSNumber.self]
        if let archived = code.descriptor,
           let descriptor = (try? NSKeyedUnarchiver.unarchivedObject(ofClasses: classes, from: archived)) as? CIBarcodeDescriptor {
            let filter = CIFilter.barcodeGenerator()
            filter.barcodeDescriptor = descriptor
            if let out = filter.outputImage { return out }
        }
        guard let bytes = code.bytes else { return nil }
        switch code.format {
        case .qr:
            let f = CIFilter.qrCodeGenerator()
            f.message = bytes
            f.correctionLevel = "M"
            return f.outputImage
        case .aztec:
            let f = CIFilter.aztecCodeGenerator()
            f.message = bytes
            f.correctionLevel = 23
            return f.outputImage
        case .pdf417:
            let f = CIFilter.pdf417BarcodeGenerator()
            f.message = bytes
            return f.outputImage
        case .code128:
            let f = CIFilter.code128BarcodeGenerator()
            f.message = bytes
            // The white plate around the code is the quiet zone.
            f.quietSpace = 0
            return f.outputImage
        default:
            return nil
        }
    }

    /// The code as large as fits `size` (points), in whole pixels per module.
    static func render(_ code: TicketPass.Barcode, fitting size: CGSize, scale: CGFloat, source: BarcodeSource?) -> Rendered? {
        if let image = redrawn(code, maxPixels: CGSize(width: size.width * scale, height: size.height * scale), scale: scale) {
            return Rendered(image: image, exact: true)
        }
        if let source, let crop = cutOut(code, from: source) { return Rendered(image: crop, exact: false) }
        return nil
    }

    static func redrawn(_ code: TicketPass.Barcode, maxPixels: CGSize, scale: CGFloat) -> UIImage? {
        guard let image = ciImage(code)?.samplingNearest(), image.extent.width > 0, image.extent.height > 0 else { return nil }
        let factor = max(1, floor(min(maxPixels.width / image.extent.width, maxPixels.height / image.extent.height)))
        let scaled = image.transformed(by: CGAffineTransform(scaleX: factor, y: factor))
        guard let cg = context.createCGImage(scaled, from: scaled.extent.integral) else { return nil }
        return UIImage(cgImage: cg, scale: scale, orientation: .up)
    }

    /// The code's region of the original page, with a quiet zone, exactly as it was issued.
    static func cutOut(_ code: TicketPass.Barcode, from source: BarcodeSource) -> UIImage? {
        guard let region = code.region?.padded() else { return nil }
        let page: CGImage?
        switch source {
        case .file(let url, let kind, let n):
            guard let data = try? Data(contentsOf: url) else { return nil }
            page = kind == .pdf ? TicketRecognizer.renderPage(pdf: data, page: n)?.cgImage : TicketRecognizer.loadImage(data)
        case .data(let data, let kind, let n):
            page = kind == .pdf ? TicketRecognizer.renderPage(pdf: data, page: n)?.cgImage : TicketRecognizer.loadImage(data)
        }
        guard let page else { return nil }
        let w = Double(page.width), h = Double(page.height)
        let rect = CGRect(x: region.x * w, y: region.y * h, width: region.width * w, height: region.height * h).integral
        guard let cropped = page.cropping(to: rect) else { return nil }
        return UIImage(cgImage: cropped)
    }

    /// For Wallet and sharing: about `targetWidth` pixels wide.
    static func image(_ code: TicketPass.Barcode, targetWidth: CGFloat = 900) -> UIImage? {
        redrawn(code, maxPixels: CGSize(width: targetWidth, height: targetWidth), scale: 1)
    }
}
#endif
