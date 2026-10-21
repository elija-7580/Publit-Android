# Installing an update on your iPhone

Publit is installed from Xcode with a Personal Team (free Apple ID). An update is installed over the
existing app: saved places, trips, lines and history stay on the phone.

## Steps

1. Get the latest code: `git pull` (branch `main`). The [CI](../.github/workflows/ci.yml) run for that commit should be green.
2. Regenerate the project, since files were added: `xcodegen generate`.
3. Open `Publit.xcodeproj`, connect the iPhone by cable (or the same Wi-Fi after the first cable install) and pick it as the run destination.
4. Check *Signing & Capabilities* for both targets (`Publit`, `PublitWidgets`): your team, automatic signing.
5. For real-world speed use the Release configuration: *Product → Scheme → Edit Scheme… → Run → Build Configuration → Release*.
   Debug builds are slower and include demo screens for the UI tests. Switch back to Debug for development.
6. *Product → Run* (⌘R). The app's settings (gear button) show the version from `project.yml` (now 0.5.1, build 6).

## Good to know

- **Free Apple ID:** the app stops opening after 7 days. Run it from Xcode again to renew; data stays.
- **First launch after an update:** iOS may ask for location again only if the permission was reset. Widgets may take a few minutes to refresh, or remove and add them again.
- **Developer Mode** must be on (*Settings → Privacy & Security → Developer Mode*); iOS asks once after the first install.
- **"Untrusted developer":** *Settings → General → VPN & Device Management*, trust your Apple ID.
- **Live Activities** need *Settings → Publit → Live Activities* on.

## Before you rely on the update

Go through the outdoor checklist in [performance.md](performance.md#outdoor-checklist-physical-iphone):
Nearby with location, a planned route, start a trip (Live Activity), widget, Siri shortcut, airplane mode,
Dark Mode, larger text, VoiceOver.

## Releasing the next version

- Raise `MARKETING_VERSION` (user-visible, e.g. 0.3.0) and `CURRENT_PROJECT_VERSION` (build, +1) in `project.yml`.
- Add a section to [CHANGELOG.md](../CHANGELOG.md).
- Push, wait for green CI, then install as above.
