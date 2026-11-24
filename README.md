# Kumo

An Android app for sharing what anime you've watched, what you want to watch, and how far along you are, then picking shows to watch with friends.

- **Discover:** every anime on AniList, sorted by popularity, trending or rating, filtered by genre, with search. Each anime has its cover art, banner and full synopsis.
- **My list:** Want to watch, Watching (with an episode tracker) and Watched. Reaching the last episode marks a show watched.
- **Friends:** add people by username, or share a `kumo://u/<you>` link. Their lists, progress and the shows you both want to watch appear on their profile.
- **Watch together:** suggest an anime to a friend; when they say "I'm in" it lands on both of your Watching together rows.
- **Inbox and notifications:** friend requests, suggestions, replies, and "@friend caught up to you on X".

Anime data comes from the public [AniList API](https://anilist.co) (no key needed). Accounts and sharing use Firebase on the free plan.

## One-time Firebase setup (about 5 minutes)

Without this, the app still builds and Discover works, but lists and friends show a "not set up" notice.

1. Go to <https://console.firebase.google.com>, click **Create a project**, and give it any name. You can turn Google Analytics off.
2. In the project, click the **Android** icon to add an app. Package name: `com.kumo.anime`. Skip the SHA-1. Download `google-services.json` and put it in `app/` (next to `app/build.gradle.kts`).
3. **Build > Authentication > Get started > Sign-in method:** enable **Anonymous**.
4. **Build > Firestore Database > Create database:** pick a location near you and start in **production mode**.
5. In Firestore, open the **Rules** tab, replace everything with the contents of [`firestore.rules`](firestore.rules), and click **Publish**.
6. Rebuild the app.

## Building

Open this folder in Android Studio and press Run, or from a terminal:

```powershell
.\gradlew.bat assembleRelease
```

The APK lands in `app/build/outputs/apk/release/app-release.apk`. Friends can install it directly; their phone will ask them to allow installs from that source.

## Known limits

- **Accounts are anonymous, tied to the install.** Uninstalling or clearing app data loses the account. Adding Google sign-in is the fix.
- **Background notifications arrive within about 15 minutes**, because Android batches background checks. Instant push needs Firebase Cloud Functions, which requires the paid Blaze plan.
- The share link uses a `kumo://` address, which some chat apps don't make tappable. The username in the message always works.
