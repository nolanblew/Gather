# Gather Android assets

The welcome illustration is saved at `public/assets/gather-welcome.png`. It was generated with the built-in image generation tool on November 6, 2026, then copied into this project. The requested `OPENAI_API_KEY` was absent from the execution environment; the tool does not expose a model selector, so no claim is made that this used Imagen 2.6 Sunburst.

Final prompt:

> Create one final premium editorial illustration asset for Gather, a warm and restrained native Android everyday lists app. Landscape 3:2 composition, pale warm ivory paper background #F8F5EF. A beautifully arranged small stack of soft paper checklist cards with a sage-green checkmark, a simple curved leaf sprig, one peach circular accent, and a tiny lavender bookmark. Sophisticated tactile cut-paper and softly shaded matte ceramic aesthetic, subtle paper grain, refined organic rounded shapes, gentle daylight shadow, peaceful ordered everyday feeling. Central subject occupies middle 60 percent, generous quiet breathing room, no borders. Muted deep sage #496451, clay peach #E9BAA5, pale lavender #C9C3DA. Crisp professional app onboarding illustration, tasteful not childish, very low visual clutter. No letters, no text, no typography, no watermark. This is illustration only, not a phone mockup or UI screenshot.

The existing `public/gather.svg` remains the source for the Gather brand mark. Native vector drawables and Compose icons provide crisp controls at any display density; the raster illustration is decorative and should not replace accessible control labels.
