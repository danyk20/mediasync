# Translating MediaSync

MediaSync is written in English and translated into Czech, German, Spanish, French, Italian, Brazilian Portuguese, Russian, Slovak and Ukrainian. On Android 13+, each user can pick MediaSync's language in its system settings, apart from the phone's.

## Where the strings are

Each module keeps its own strings as Compose Multiplatform resources, in `src/commonMain/composeResources/values/strings.xml`, and their translations next to them in `values-cs`, `values-de`, `values-es`, `values-fr`, `values-it`, `values-pt-rBR`, `values-ru`, `values-sk` and `values-uk` (the same folders in every module, e.g. `feature/backup/src/commonMain/composeResources/values-de/strings.xml`). The Android app itself keeps only its name, in `app/src/main/res/values*/strings.xml`: it is the one string the launcher and Android's settings read, and those folders, together with the list in `app/build.gradle.kts` (`localeFilters`), are what Android 13+'s language list is generated from. A new language needs its `values-xx` folder in every module, a `values-xx/strings.xml` with `app_name` in `app/src/main/res`, and an entry in `localeFilters`.

## Rules for a translation

- Keep every `name`, and every placeholder exactly as it is. Placeholders are indexed, `%1$s`, `%2$d`, also when a string has just one; their order may change where the language needs it.
- Write apostrophes and quotes as they are: `'` and `"`, not `\'` and `\"` (Compose resources don't unescape them). Escape `&` and `<` as XML needs.
- Plurals list the forms the language uses: Czech, Slovak, Russian and Ukrainian have `one`, `few`, `many` and `other`. Plurals are `<plurals>` in the same file, each `<item quantity="...">` with indexed arguments (`%1$d`).
- Names stay as they are: MediaSync, Google Photos, Cloudflare, Firebase, Wi-Fi, Wi-Fi Direct, Turbo. Settings in other apps, such as Google Photos' *Storage saver* or the Pixel's *Smart Storage*, take the name that app shows in the language. When unsure, keep the English name.
- Say *you* informally where the language allows it in apps (German *du*, Spanish and Italian *tu*, Brazilian Portuguese *você*). French, Czech, Slovak, Russian and Ukrainian use the polite form.
- Keep it as short and plain as the English, because the screens are narrow.

## New and changed strings

A new English string shows in English until the next translation pass: a missing translation falls back to `values/strings.xml`. Compose resources have no Lint checks, so a translation with a broken placeholder or an obsolete name is only found when a screen shows it. Compare the names and placeholders with the English file before sending a translation.

## Checking a layout without a translation

Debug builds still enable Android's two pseudo-locales, *English (XA)* and *Arabic (XB)*, but Android only pseudo-localizes its own resources, not Compose's. *Arabic (XB)* still turns the layout right to left, with English text. *English (XA)*, the longer accented text, no longer works. To see where text gets cut, use the largest font size in Android's settings, or the screenshots from `-Pmediasync.largeFont` (`LargeFontTest`), or a long language such as German or Russian. `PseudoLocalesTest` keeps these facts true.
