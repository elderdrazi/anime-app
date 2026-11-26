# Kumo

An Android app for sharing what anime you've watched, what you want to watch, and how far along you are, then picking shows to watch with friends.

- **Discover:** every anime on AniList, one card per show. Sort by Popular, Trending, Top rated, Airing or Upcoming, filter by genre or theme (Isekai, Shounen, School and so on), or search. Each show has its cover art, banner and full synopsis.
- **Seasons in one place:** AniList lists every season, film and special separately. Kumo follows their prequel/sequel links and folds them into one show, listed in release order on the show's page.
- **My list:** Want to watch, Watching and Watched. Each season, film and OVA is ticked on its own, so you can skip an OVA between two seasons. The tracker follows the furthest point you've reached ("Season 2 · Episode 5"), and finishing the newest released season marks a show watched.
- **Updates from inside the app:** Kumo checks GitHub for a newer release at launch. The Profile tab shows an Update button that downloads it and opens Android's install prompt.
- **Recommend:** send a show to a friend with a note; they can add it to their list from the inbox.
- **Ratings and top ten:** score any show you've started from 1 to 100. Your ten highest appear on your profile.
- **Profile:** your username, member-since date, list counts and top ten, with an optional email so the account survives a new phone. Friends see a separate public profile with only your lists and top ten; your email is never stored where they can read it.
- **Your lists in Discover:** the Watching, Want to watch and Watched filters show your own list, most recently updated first.
- **Friends:** add people by username, or share a `kumo://u/<you>` link. Their lists, progress and the shows you both want to watch appear on their profile.
- **Watch together:** suggest an anime to a friend; when they say "I'm in" it lands on both of your Watching together rows.
- **Inbox and notifications:** friend requests, suggestions, replies, and "@friend caught up to you on X".

Anime data comes from the public [AniList API](https://anilist.co) (no key needed). Accounts and sharing use Firebase on the free plan.

## One-time Firebase setup (about 5 minutes)

Without this, the app still builds and Discover works, but lists and friends show a "not set up" notice.

1. Go to <https://console.firebase.google.com>, click **Create a project**, and give it any name. You can turn Google Analytics off.
2. In the project, click the **Android** icon to add an app. Package name: `com.kumo.anime`. Skip the SHA-1. Download `google-services.json` and put it in `app/` (next to `app/build.gradle.kts`).
3. **Build > Authentication > Get started > Sign-in method:** enable **Anonymous** and **Email/Password**.
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

- **Accounts start out tied to the install.** Until you add an email on the Profile tab, uninstalling or clearing app data loses the account.
- **Background notifications arrive within about 15 minutes**, because Android batches background checks. Instant push needs Firebase Cloud Functions, which requires the paid Blaze plan.
- **Version 1.0 saved one list entry per season.** 1.1 converts your own entries the first time it opens; a friend's list lines up with yours once they update too.
- **AniList allows 30 requests a minute.** Fast browsing can pause for a few seconds while the app waits its turn.
- The share link uses a `kumo://` address, which some chat apps don't make tappable. The username in the message always works.
