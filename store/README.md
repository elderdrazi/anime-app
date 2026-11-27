# Putting Kumo on Google Play

Everything for the store listing is in this folder. Work through the steps in order.

## 1. Create the upload key (once)

Google Play only accepts apps signed with your own key. Run this in a terminal from the project folder. It asks you to choose a password and a few details about yourself:

```powershell
& (Join-Path (Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -Filter "jdk-17*")[0].FullName "bin\keytool.exe") -genkeypair -v -keystore upload-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias upload
```

Then create a file called `keystore.properties` next to it, with your password in place of the dots:

```
storeFile=upload-key.jks
storePassword=........
keyAlias=upload
keyPassword=........
```

Keep both files safe and backed up somewhere private. Neither is ever added to git. If the key is lost, Google can reset it, but it takes a support request.

## 2. Build the file to upload

```powershell
.\gradlew.bat bundlePlayRelease
```

The result is `app\build\outputs\bundle\playRelease\app-play-release.aab`.

## 3. Create the app in Play Console

- App name: **Kumo: Anime Lists with Friends**
- Default language: English (United States)
- App or game: App
- Free or paid: Free

## 4. Store listing

**Short description** (80 characters max)

> Track the anime you watch, rate it, and share your list with friends.

**Full description**

> Kumo keeps track of the anime you're watching and shows your friends what's on your list.
>
> ONE ENTRY PER SHOW
> Every season, film and special of a show sits under one entry. Tick off episodes as you go, and skip the OVA if you didn't watch it.
>
> SHARE WITH FRIENDS
> Add friends by username and see what they're watching, what they want to watch and how far along they are. Recommend a show with one tap, or agree on something to watch together.
>
> SEE WHAT YOUR FRIENDS RATE
> Rate shows from 1 to 100. Covers show your friends' average rating next to the community score, and Friends' favorites lists what they love that you haven't seen.
>
> LEVELS AND ACHIEVEMENTS
> Earn XP as you watch, unlock 100 achievements, and collect borders for your profile picture.
>
> YOUR PROFILE
> A cover photo made from your favourite shows, your ratings ranked best first, and your level and achievements for friends to see.
>
> BRING YOUR LIST
> Already track on AniList? Connect your username and Kumo imports your list and keeps picking up new episodes you log there.
>
> No ads. No tracking. Start with just a username, and add an email later if you want your list on another phone.
>
> Show information and artwork are provided by AniList. Kumo is not affiliated with AniList or any anime studio or streaming service, and does not stream video.

- App icon: `icon-512.png`
- Feature graphic: `feature-graphic-1024x500.png`
- Phone screenshots: everything in `screenshots/`
- Category: Entertainment
- Contact email: elderdrazi@gmail.com
- Privacy policy: https://github.com/elderdrazi/anime-app/blob/main/docs/privacy.md

The screenshots show anime artwork, which belongs to its owners. Other tracker apps do the same, but if Google objects, replace them with screens that show less artwork (the achievements screen is a safe one).

## 5. App content forms

**Privacy policy:** the link above.

**App access:** all features are available without special access. A reviewer can start with any username; no login is needed.

**Ads:** no ads.

**Content rating questionnaire:** category "Reference, News, or Educational" or "Entertainment" as offered. Users can interact with each other and share content they create (usernames, profile pictures, short notes). No location sharing, no purchases, no gambling. Reporting and blocking are built in.

**Target audience:** 13 and over. Do not include under-13s.

**Data safety:**

| Question | Answer |
|---|---|
| Does the app collect or share user data? | Yes, collects. Nothing is shared with third parties. |
| Is data encrypted in transit? | Yes |
| Can users request deletion? | Yes: https://github.com/elderdrazi/anime-app/blob/main/docs/delete-account.md |

Data types collected, all for "App functionality" (and "Account management" for the first two), none for ads or analytics:

| Data type | Required? |
|---|---|
| Personal info: Email address | Optional |
| Personal info: User IDs (username) | Required |
| Photos and videos: Photos (profile picture) | Optional |
| App activity: Other user-generated content (list, ratings, achievements) | Required |
| Messages: Other in-app messages (notes sent to friends) | Optional |

**Account deletion:** the app lets people create accounts, so give Google the deletion link above when it asks.

**User-generated content:** the app has terms of use (https://github.com/elderdrazi/anime-app/blob/main/docs/terms.md), in-app reporting and in-app blocking.

## 6. Testing, then release

New personal developer accounts must run a closed test before going public.

1. In Play Console, open **Testing → Closed testing** and create a release with the `.aab` file.
2. Add at least 12 testers by email (Google accounts) and send them the opt-in link.
3. Keep at least 12 opted in for 14 days in a row.
4. Then apply for production access from the Play Console dashboard.

Friends who installed Kumo from GitHub need to uninstall it before installing the Play version, because the two are signed with different keys. They should add an email on the Profile tab first so their list comes back when they sign in.

## 7. Reviewing reports

Reports filed in the app land in the `reports` collection in Firestore (Firebase console → Firestore Database → Data). Each one names who it's about and why. Google expects reports to be looked at and acted on.
