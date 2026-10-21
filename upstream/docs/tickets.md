# Tickets as passes

Status: **in the app since 0.5.0** (Tickets tab, trip screens, Live Activity, deep links; `Features.passes`).
The first ticket version (`TicketStore`, `TicketsView`) stays switched off with `Features.tickets = false`;
its tickets are imported as passes on launch.

The idea follows apps like NeatPass: your ticket arrives as a PDF, photo or screenshot; Publit reads it on the
iPhone, you check the result, and you get a clean pass with the code ready for the gate. Publit's own take: it stays
inside a transit app, so a pass can belong to a saved trip, and passes valid at the trip's time show up there.

## Shared PDF / image import (0.6.0, build 31)

If the source app offers **Open In / Copy to Publit**, choose it. iOS hands the local file to Publit;
it switches to Tickets and reads it on the device. **Every import opens the review screen before anything
is stored**, including recognizable shared tickets. Save keeps the checked fields and the unmodified source;
Cancel leaves no new ticket. No ticket upload or automatic operator-logo lookup runs while reading the file.

- Images and single-page PDFs: read on-device, then review and Save.
- PDFs up to 12 pages: scan one page at a time; auto-select only a unique recognizable ticket page.
- Several ticket pages, no unique match, or longer PDF: choose the page; all pages remain available.
- Unrecognized documents: normal editable review, never silently converted into a valid ticket.
- Labelled validity dates are extracted; unlabelled dates are not invented as validity. Missing dates stay unknown.
- A repeated identical document/page reviews the existing pass, including its saved corrections; different pages remain different tickets.
- Maximum source size is 25 MiB. Dismissing an unfinished import prevents a late save.

**Scope:** this registers document handlers, not an iOS Share Extension. Ordinary image share sheets,
including some WhatsApp flows, may not list Publit. If not listed, save to Files and use the in-app
**Tickets → Add ticket → PDF or image file** flow (review + Save). Direct source-app visibility and
security-scoped file-provider access must be verified on a physical iPhone. A universal Share Extension
with reliable app handoff would need a separate extension plus shared-container setup (App Groups),
which the current Personal Team signing does not support. Do not use extension URL-opening hacks or
put private ticket bytes in a deep-link URL.

Recognition is not an authenticity check. Always compare date, route and code with the operator's
original; rotating codes still belong in the operator's app. Import keeps codes and source bytes,
but scanner compatibility still needs real-ticket/device testing.

## Flow

1. **Add** (`PassImportView`): photo or screenshot (PhotosPicker), PDF or image file (file importer, 25 MB limit),
   or scan a paper ticket (VisionKit document camera). Multi-page PDFs ask for the page.
2. **Read on the device** (`TicketRecognizer`): Vision finds QR, Aztec, PDF417, Data Matrix, Code 128, EAN-13/8,
   UPC-E, Code 39/93, ITF and Codabar codes and the text.
   PDFs use their embedded text when there is enough of it. The page's main colour is offered as "From ticket".
   Nothing is uploaded.
3. **Understand** (`TicketTextParser`, platform-independent): operator, product (Deutschlandticket, KlimaTicket,
   Sparschiene, …), type, route, validity (labels in German, English, French and Italian; birth and purchase dates
   are ignored), passenger, class, price, ticket number. The issuer of a UIC 918.3 rail code (`#UT…`) is read
   from the code header. Fields stay empty when nothing supports them.
4. **Check** (`PassEditorView`): live preview of the pass; every field editable; choose among several codes or
   none; colour (presets, operator colour, colour from the ticket) and symbol. **Assign fields on the ticket**
   opens the original selected page with tappable OCR regions (also available when editing a saved ticket). Pinch/double-tap to zoom; tap text, correct it
   if needed and choose title, operator, origin, destination, via, passenger, price, ticket number, validity
   start/end or notes. Assignments only change the draft. Invalid date text leaves the existing value intact;
   the final editor also rejects an end date before the start. If OCR is unavailable, the normal manual fields
   remain usable. The document and barcode are never rewritten.
5. **Use** (`PassWalletView`, `PassDetailView`): passes grouped as valid now, upcoming, no dates, expired
   (folded). The pass card shows operator, title, validity badge, route, details, a perforation and the code.
   Tapping the code opens it full screen on white; brightness goes to 100 % while a code is shown and comes
   back when it leaves the screen or the app goes to the background. "Show original" opens the PDF (PDFKit)
   or image (pinch and double-tap zoom).
6. **Apple Wallet (optional)** (`AddToWalletButton`, `WalletPassBuilder`): see below.

Codes that change every few seconds (many operator apps) cannot be copied. Publit says so on the import and check
screens, and every pass says that only the operator's ticket is valid.

## Files

| Part | File | Runs on |
| --- | --- | --- |
| Model, validity, grouping | `Sources/Tickets/TicketPass.swift` | iOS, Linux |
| Colours with WCAG AA text and labels | `Sources/Tickets/PassStyle.swift` | iOS, Linux |
| Explicit field assignment | `Sources/Tickets/TicketFieldMapping.swift` | iOS, Linux |
| Text rules, known operators | `Sources/Tickets/TicketTextParser.swift` | iOS, Linux |
| Store, documents, legacy import | `Sources/Tickets/PassStore.swift` | iOS, Linux |
| pass.json, manifest, package, signer client | `Sources/Tickets/WalletPass.swift`, `Sources/Util/SHA1.swift`, `Sources/Util/ZipWriter.swift` | iOS, Linux |
| Vision and PDF reading | `Sources/Tickets/TicketRecognizer.swift` | iOS |
| Drawing codes again | `Sources/Tickets/BarcodeRenderer.swift` | iOS |
| Screens | `Sources/Tickets/Views/*` | iOS |
| Demo data for screenshots | `Sources/Tickets/PassSamples.swift` (DEBUG only) | iOS |
| Signing service | `services/pass-signer` | Cloudflare Workers |

Codes are stored with their text, raw bytes when the text does not carry them (binary rail codes), the
archived `CIBarcodeDescriptor`, and their position on the page:

- QR, Aztec, PDF417 and Data Matrix are redrawn from the descriptor: the same symbol version, error correction
  level, mask and data codewords as the scanned one, binary content (UIC 918.3 `#UT…`) included.
- Code 128 is redrawn from its content.
- EAN, UPC, ITF, Code 39/93 and Codabar cannot be generated on iOS; they are cut out of the original page
  with a quiet zone, so the bars are exactly the issued ones.
- Redrawn codes use a whole number of screen pixels per module and nearest-neighbour sampling (no smoothed
  edges), on white with a quiet zone. Wallet gets binary content as ISO-8859-1 text, which keeps every byte.

## Operator logos

`OperatorLogos` (platform-independent, tested) looks up the operator's Wikidata item (known operators by ID,
others by search), takes its logo statements (P154: preferred and current first, no white variants, no
deprecated ones) and loads them as PNG from Wikimedia Commons (`Special:FilePath`, 320 px). The website's icon is
the fallback. New tickets of known operators get the first logo as a suggestion; the editor's "Operator logo"
row offers all suggestions, a search by name, an image from Photos, or none. Only the operator name or ID is
sent, with Publit's User-Agent. Logos are stored as PNG (max. 480 × 160 px) next to the documents and used on
the card and in Wallet (`logo.png`, `icon.png` on a white plate). Logos are trademarks of their owners.

Tests: `Tests/TicketPassTests.swift` (parser, validity, styles, SHA-1, CRC-32, ZIP, pass.json, store) and
`testF12TicketPassScreens` in the UI tests (screenshots `pass-*.png` in the `ci-results` branch).

## Apple Wallet

Wallet only accepts passes signed with a Pass Type ID certificate (paid Apple Developer Program). The private key
must not ship in an open-source app, so `services/pass-signer` holds it. The app sends only pass.json and
manifest.json (no document, no image of the ticket); the service checks Pass Type ID, Team ID, file names and the
pass.json hash, refuses web-service fields, and returns the detached signature. Export is hidden until these
Info.plist keys are set:

| Key | Example |
| --- | --- |
| `PublitPassTypeIdentifier` | `pass.io.github.publit.ticket` |
| `PublitTeamIdentifier` | your 10-character Team ID |
| `PublitPassSignerURL` | `https://publit-pass-signer.<account>.workers.dev/sign` (HTTPS only) |

Wallet passes are `sharingProhibited` and have no web service, so they are never updated remotely.

## Wiring it in (when the design is approved)

- [x] Inject a `PassStore` in `PublitApp` and run `importLegacy` for existing tickets.
- [x] Tickets tab with `PassWalletView`; ticket deep links open `PassDetailView` or the trip's tickets.
- [x] Trip screens: `passes(forTrip:at:)` with "Show ticket" (one ticket with a code opens the code directly).
- [x] Live Activity "Show ticket" with the new store.
- [ ] Siri "Show ticket".
- [ ] Share extension: "Share to Publit" from Mail and the Files app.
- [ ] Remove `TicketStore`, `TicketsView`, `TicketCard`, `Features.tickets` after the migration release.
- [ ] Test on a device: recognition of real tickets (ÖBB, DB, Deutschlandticket, KlimaTicket, Wiener Linien),
      gate scanners with the redrawn codes, brightness, VoiceOver, large text.
- [ ] Wallet: Apple Developer Program, Pass Type ID, deploy the signer, add the Info.plist keys, test on a device.
