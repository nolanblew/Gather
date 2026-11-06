# Installing Gather

The installation offer is a convenience, not a task the user must complete.

- Show a compact, themed inline card in the authenticated collection screen, below its header. Do not cover navigation, lists, or input fields.
- Offer it after a short delay only when the browser offers native installation or the device has a supported manual installation path. Suppress it while viewing/editing a list or using a dialog.
- Copy: **Keep Gather close**. Supporting text: **Add it to your home screen for quick access.**
- Native action: **Install Gather**. Manual action: **How to install**. Include an accessible dismissal control; dismissing either the card or native prompt snoozes the offer for 30 days.
- Never open a browser installation prompt automatically. Installation always starts with a user action.
- Keep installation help available in Settings even after dismissal. On iOS, explain Share → Add to Home Screen → Open as Web App (if shown) → Add.
- Hide the offer in an installed standalone window. Installation detected in a browser is best-effort; browser tabs cannot universally query whether an app was installed elsewhere.
- Inherit current light/dark palettes, use touch-friendly controls, and stack actions on narrow screens. Avoid animation or a repeating attention indicator.
- Installation does not imply offline editing. A public offline page should say that Gather needs a connection to load/save lists, with a Retry action. Never cache list contents, authentication responses, searches, or mutations in the service worker.

Final review must cover desktop and narrow layouts, dismissal persistence, Settings recovery, native prompt eligibility, standalone suppression, and private-request cache exclusion. Actual OS installation is a separate user action and must not be claimed from a simulated browser event.
