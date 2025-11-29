Planned tasks for issue: Saved screen gating, checklist, UI improvements, and build fix.

1) Enforce profile-completion gating for generating visa roadmaps
- Add completeness check (nationality + (education or workYears))
- Show popup prompting to complete profile in Settings when incomplete
- Provide navigation callback to Settings/Profile from SavedScreen

2) Fix bottom navbar icon for Saved tab
- Replace construction icon with a proper Saved/Bookmarks icon in App.kt

3) Add Visa Checklist feature with persistence and downloads
- Define Checklist entity and ChecklistRepository
- Implement in-memory default and Android file-backed repository
- Wire into DataModule and ensureDataModuleInitialized()
- Add form-based generation flow on Saved screen using VisaChecklistTool
- Allow viewing and saving (.md) of generated checklists via saveTextFile

4) Improve Saved screen UI for scalability
- Section headers (Roadmaps, Checklists)
- Empty states, cards with actions (View, Download, Delete later)
- Progress/error messaging

5) Unblock Android build (resource error)
- Add drawable ic_launcher_foreground.xml to satisfy launcher resources

6) Build and verify
- Ensure compilation passes and features behave as expected on Android
- Verify persistence across app restarts
