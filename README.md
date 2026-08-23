# Offline Tuition Manager

English-only Android tuition management app based on the supplied UI reference.

## Offline architecture
- Kotlin + Jetpack Compose
- Room / SQLite local database
- No server
- No login
- No internet required
- Local backup/restore design

## Implemented foundation
- Student entity and student list
- Add student flow
- Fee record entity
- Multiple independent payment transactions
- Payment modes and references
- Partial/Paid/Pending calculation model
- Payment correction entity and audit trail
- Payment reversal entity
- Attendance entity
- Audit log
- Dashboard / Students / Fees / Attendance / More navigation shell

## Financial rules
Money is stored in integer minor units (`Long`), never floating point.

A payment is never silently deleted. Reversal changes the transaction status and creates a reversal record. Corrections create correction history before updating the active transaction.

## Next implementation modules
1. Full monthly fee list and student fee profile
2. Receive payment form
3. Multiple payment history
4. Correction confirmation screens
5. Reversal confirmation screens
6. Undo last payment
7. PDF receipt generation
8. Android sharing and print integration
9. Full backup/export and restore/import
10. Attendance marking
11. Reports dashboard
12. Needs Attention calculations

## Open in Android Studio
1. Open the `OfflineTuitionManager` folder.
2. Use JDK 17.
3. Let Gradle sync dependencies.
4. Run on an Android phone or emulator.
5. Build > Build APK(s).

The generated project is an offline source foundation. It must be compiled in Android Studio to produce the installable APK.


## GitHub cloud build
This repository includes `.github/workflows/build-apk.yml`.

See `GITHUB_BUILD_GUIDE.md` for the no-Android-Studio build process.
