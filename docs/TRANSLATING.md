# Translating MediaSync

MediaSync is written in English and translated into Czech, German, Spanish, French, Italian, Brazilian Portuguese, Russian, Slovak and Ukrainian. On Android 13+, each user can pick MediaSync's language in its system settings, apart from the phone's.

## Where the strings are

Each module keeps its own strings in `src/main/res/values/strings.xml`, and their translations next to them in `values-cs`, `values-de`, `values-es`, `values-fr`, `values-it`, `values-pt-rBR`, `values-ru`, `values-sk` and `values-uk`. The languages are listed once, in `app/build.gradle.kts` (`localeFilters`). Android 13+'s language list is generated from the translations.

## Rules for a translation

- Keep every `name`, and every placeholder exactly as it is: `%1$s`, `%2$d`. Their order may change where the language needs it.
- Escape apostrophes and quotes as in English: `\'` and `\"`.
- Plurals list the forms the language uses: Czech, Slovak, Russian and Ukrainian have `one`, `few`, `many` and `other`. Lint says when one is missing or isn't used.
- Names stay as they are: MediaSync, Google Photos, Cloudflare, Firebase, Wi-Fi, Wi-Fi Direct, Turbo. Settings in other apps, such as Google Photos' *Storage saver* or the Pixel's *Smart Storage*, take the name that app shows in the language. When unsure, keep the English name.
- Say *you* informally where the language allows it in apps (German *du*, Spanish and Italian *tu*, Brazilian Portuguese *você*). French, Czech, Slovak, Russian and Ukrainian use the polite form.
- Keep it as short and plain as the English, because the screens are narrow.

## New and changed strings

A new English string shows in English until the next translation pass, since Lint doesn't require every string to be translated. Lint does fail the build on broken placeholders, missing plural forms, and translations of strings that no longer exist.

## Checking a layout without a translation

Debug builds include two pseudo-locales: *English (XA)*, with longer, accented text, and *Arabic (XB)*, right to left. Choose them as MediaSync's language on the phone to see where text gets cut or turned around.
