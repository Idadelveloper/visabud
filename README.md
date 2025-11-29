# VisaBud (Multiplatform)

An on-device, privacy-first visa assistant. VisaBud helps travelers and migrants understand visa options, requirements, costs, and where to apply — all through a single chat interface. The assistant runs locally, uses a small curated facts database plus embeddings for grounded answers, and keeps your profile and documents on your device.

Current date: 2025-11-29

## Highlights
- Single chat interface that orchestrates tools automatically
- Local memory: your profile, documents, and embeddings are stored on-device
- Verified facts via a local JSON + embeddings (RAG)
- Natural-language answers with citations to official sites
- When information is missing, the agent asks first before running tools

## Features
- Q&A / Chat Orchestrator
  - Intent detection, missing-info prompts, tool calling, citations, disclaimers
  - Always runs the Profile Builder first to extract info from each message
- Visa Facts Database (RAG)
  - Local JSON of official links + facts per country, with embeddings for retrieval
- User Profile Memory
  - Persistent local profile (nationality, passport expiry, education, work, finances, travel history, preferences, goals)
- Profile Builder Tool
  - Auto-extracts nationality, passport validity, occupation, financial capacity, travel experience, and purpose from chat
- Visa Type Tool
  - Recommends the appropriate visa category based on destination + purpose (e.g., conference in the US → B-1)
- Visa Checklist Tool
  - Personalized document checklist by country and visa type; warns on possible gaps (e.g., passport validity < 6 months)
- Visa Roadmap Generator
  - Produces 1–3 possible paths (e.g., Study → Graduate → Work → PR) with timelines and prerequisites
- Cost / Expense Estimator
  - Estimates visa fees and ground costs (biometrics, services, translations, flights, living) with assumptions and citations
- Document Review Module (on-device)
  - Parses passports/bank statements/degrees (from text/OCR), checks basic validity, and suggests fixes
- Embassy / Consulate Locator
  - Finds nearest mission to your city from a local dataset and provides contact info + map links
- Visa Comparison Tool
  - Compares visa difficulty heuristically across countries or regions (e.g., “Schengen”) and cites official links
- Visa Eligibility Tool
  - Checks if you’re likely eligible, partially eligible, or ineligible for a given visa type and destination, with reasons and suggestions to improve

## Tech Stack
- Kotlin Multiplatform (Android, iOS, Web/Wasm, Desktop/JVM)
- Compose Multiplatform UI
- Local LLM integration (CactusLM on Android; expect/actual abstraction elsewhere)
- Lightweight RAG: local JSON resources + in-memory embeddings (with cosine search)
- Ktor server module (optional, for experiments)
- In-memory repositories (Profile, Documents, Embeddings, Roadmaps, Chats) with a swappable DataModule

Key modules/files (commonMain):
- ai/ChatAgent.kt — front-layer orchestrator
- ai/VisaFactsRag.kt — facts dataset + retrieval and citations
- ai/UserProfileMemory.kt — persistent local profile API
- ai/ProfileBuilderTool.kt — extraction + targeted prompts
- ai/VisaTypeTool.kt — visa category recommendation
- ai/VisaChecklistTool.kt — personalized checklist
- ai/RoadmapGenerator.kt — roadmap paths (LLM-backed with heuristic fallback)
- ai/CostEstimator.kt — cost breakdowns
- ai/DocumentReviewModule.kt — document parsing/validation (on-device)
- ai/EmbassyLocator.kt — nearest embassy/consulate lookup
- ai/VisaComparisonTool.kt — comparison across countries

## Privacy by Design
- All processing is local: profile, embeddings, document parsing, and reasoning
- No network calls in agent tools; official links are shown for user to verify
- You choose what to store; sensitive fields can be encrypted when adding persistent storage later

## Project Structure
- composeApp/src/commonMain — shared business logic and UI
- composeApp/src/androidMain, iosMain, webMain, wasmJsMain, desktopMain — platform code
- server — optional Ktor server module
- shared — additional shared code (if used)

## Prerequisites
- JDK 17+
- Android Studio Giraffe/Koala or IntelliJ IDEA with KMP support
- Xcode 15+ for iOS builds (on macOS)
- Recent Chrome/Edge/Safari for Web/Wasm

The app uses a local model (e.g., CactusLM on Android). The first run may download a small model on supported platforms.

## Build & Run

### Android
- From IDE: run the Android run configuration
- CLI (debug):
  - macOS/Linux:
    ```sh
    ./gradlew :composeApp:assembleDebug
    ```
  - Windows:
    ```bat
    .\gradlew.bat :composeApp:assembleDebug
    ```

### iOS
Preferred workflow (avoids IDE CidrBuild toolchain):
- Build iOS frameworks with Gradle
  - Simulator (Apple Silicon):
    ```sh
    ./gradlew :composeApp:buildIosSim
    # or
    ./scripts/build-ios.sh
    ```
  - Device:
    ```sh
    ./gradlew :composeApp:buildIosDevice
    # or
    ./scripts/build-ios.sh device
    ```
  - Universal XCFramework:
    ```sh
    ./gradlew :composeApp:buildIosXCFramework
    # or
    ./scripts/build-ios.sh xcframework
    ```
- Open iosApp/iosApp.xcodeproj in Xcode and run on a simulator or device (links the generated ComposeApp framework).

### Web
- Wasm target (modern browsers):
  - macOS/Linux:
    ```sh
    ./gradlew :composeApp:wasmJsBrowserDevelopmentRun
    ```
  - Windows:
    ```bat
    .\gradlew.bat :composeApp:wasmJsBrowserDevelopmentRun
    ```
- JS target (legacy support):
  - macOS/Linux:
    ```sh
    ./gradlew :composeApp:jsBrowserDevelopmentRun
    ```
  - Windows:
    ```bat
    .\gradlew.bat :composeApp:jsBrowserDevelopmentRun
    ```

### Server (optional)
Run the sample Ktor server:
- macOS/Linux:
  ```sh
  ./gradlew :server:run
  ```
- Windows:
  ```bat
  .\gradlew.bat :server:run
  ```

## Quick Start (Agent)
1) Launch the app on your platform.
2) Start chatting. The agent will first extract/update your profile from your message (on-device).
3) Ask things like:
   - “Which visa should I apply for to attend a conference in the US?”
   - “Create a checklist for a UK tourist visa.”
   - “What’s a roadmap for study in Canada?”
   - “Estimate costs for 12 months in Germany (study).”
   - “Nearest US embassy to me in London?”
4) When you upload a document (passport, bank statement, degree), the app reviews it locally and suggests fixes.

## Notes & Troubleshooting
- The first AI call on supported platforms may download a local model; please wait until it shows “Model ready”.
- Our facts dataset is intentionally small for MVP. When data is missing, the agent will tell you and link to the official site.
- iOS build tip: prefer the Gradle tasks above over IDE CidrBuild.

## License
TBD. For internal/demo use. Replace with your organization’s license as needed.


## Visa Facts Database — New Hierarchical/Bilateral Schema
The bundled visa_facts.json now uses a hierarchical, bilateral structure optimized for RAG:
- Countries → Visa Types → Bilateral Requirements (by origin nationality)
- Structured criteria fields (e.g., age limits, English level, work experience, sponsorship) enable pre-filtering and better fact-checking.
- Semantic chunking guidance included in the JSON for embedding/retrieval tuning.

App integration:
- The loader maps the new schema into a backward-compatible internal format so existing tools (Checklist, Cost, Compare, Visa Type, Roadmap, Eligibility) keep working.
- RAG embeddings are built from semantic summaries per country/visa type and bilateral criteria, and citations use the officialSourceUrl when present.
- When data is missing for a country/route, the agent falls back to general guidance and always includes an official link reminder.

## RAG Chunking & Hybrid Retrieval (implementation overview)
- VisaFactsRag now builds an in-memory, chunk-level index (RagChunk) instead of per-fact only.
- Each chunk stores: text, country code/name, official site, inferred chunkType (overview/eligibility/documents/timeline/restrictions), and room for structured metadata (visaTypeId, category, originIso3, fees/timeline, lastVerified).
- A new retrieveHybrid(query, embedder, filters, topK) API runs vector search + lightweight metadata filtering:
  - Filters: destination ISO, origin ISO3 (from profile nationality), visa category (derived from user goal), min verified date (recency)
  - Reranking adds small boosts to eligibility/documents chunks for matching queries and recent data
- ChatAgent uses the hybrid retrieval in generic Q&A, assembling a system preamble + sources and keeping replies natural-language.
- Embeddings remain ephemeral/in-memory to avoid stale vectors; ensurePersisted() clears any old repository state.
- As dataset richness grows (fees, timelines, nationality-specific criteria), extend RagChunk population to include those metadata fields for stronger filtering.

## Embassy / Consulate Database — New Rooted Schema
The bundled embassies.json has been upgraded to a rooted schema with a diplomaticMissions array and rich nested fields:
- Mission record example fields: missionType, representingCountry (name, iso2/code), hostCountry, missionName/officialTitle,
  location { coordinates { latitude, longitude, precision }, address { city, fullAddress, postalCode } }, contact { phone, email, website }, services.

App integration:
- EmbassyLocator now parses the new root object into a backward‑compatible internal model (EmbassyCountryEntry → missions of Mission).
- Grouping is by representingCountry (destination). The tool computes nearest using a built‑in haversine function; no network/geocoding.
- If parsing the new schema fails, it gracefully falls back to the legacy flat array to preserve compatibility.
- The ChatAgent embassy flow stays the same and returns a human‑readable answer (address, contact, map link) with alternatives.

Notes:
- We currently use city keywords or lat,lon provided by the user/profile to estimate proximity. In future, operating hours and “open now” logic can be added using the hours fields in the dataset.
- All processing remains on‑device; no external geospatial DB is required for the MVP.
