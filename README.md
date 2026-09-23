# Google Maps Data Extractor — Android v0.2

## What is implemented
- Native Android / Kotlin / Jetpack Compose UI.
- Google Places API (New) Text Search integration.
- Search query + country/city/area.
- Up to 60 results per Text Search request using pagination.
- Place ID based deduplication.
- Live progress/status.
- Pause / resume / stop at page boundaries.
- Filters: minimum rating, phone only, website only.
- CSV export (UTF-8 BOM).
- XLSX export without third-party spreadsheet libraries.
- Local API-key setting.
- Local search history (last 20 operations).

## Important
Google Places Text Search (New) currently documents a maximum of 60 results across pages for a search. For larger datasets, the next version should split searches geographically/category-wise and deduplicate the combined result set rather than pretending one query can return thousands of places.

The app expects a Google Places API (New) key. Enable billing and the Places API in Google Cloud, and restrict the key appropriately. Do not hard-code a production key into the source code.

## Build
Open this folder in Android Studio with a recent Android Gradle Plugin/Kotlin toolchain. The source project is provided; Gradle wrapper files are not bundled in this prototype.
