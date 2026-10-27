# SmarterTube fork

This is a personal fork of [SmartTube](https://github.com/yuliskov/SmartTube). It adds one row to the top of Home, "For You (Local)", which is ranked on the device from your own watch history and subscriptions. It builds as a separate app called SmarterTube (`org.smarttube.localrecs`), so it installs next to official SmartTube on a Fire TV.

## Branches

`master` mirrors `upstream/master` exactly. Never commit to it.

`custom` holds all of our work and gets rebased onto `master` whenever upstream moves.

The `MediaServiceCore` and `SharedModules` submodules always point at whatever upstream pins. We don't change anything inside them.

## Syncing with upstream

```sh
scripts/sync-upstream.sh
git push origin master
git push --force-with-lease origin custom
```

The script fetches upstream, fast-forwards `master`, updates the submodules, and rebases `custom`. If the rebase hits a conflict it stops and tells you what to do. Every edit we make to an upstream file has a `LOCALRECS` comment next to it, so `grep -rn LOCALRECS` on the conflicted files shows you which lines are ours.

## Building

The toolchain comes from devenv (JDK 17, Android SDK, adb). Run `direnv allow` once, or prefix commands with `devenv shell --`. Upstream ships `gradlew` without the executable bit, so call it through bash:

```sh
devenv shell -- bash gradlew :smarttubetv:assembleStlocalDebug
devenv shell -- bash gradlew :common:testStstableDebugUnitTest --tests 'com.liskovsoft.smartyoutubetv2.common.localrecs.*'
```

Run the build and the tests as two separate commands. If a Gradle invocation mentions `Ststable` anywhere, upstream's build applies the Google Services plugin to every variant in it, and that plugin fails on our package name.

We don't upgrade Gradle, AGP or SDK levels. That would conflict with upstream on every sync.

## Signing

Android only installs an update over an existing app if both are signed with the same key. Create one keystore and keep it somewhere safe outside the repo:

```sh
keytool -genkeypair -v -keystore ~/keys/smartertube.jks -alias smartertube -keyalg RSA -keysize 2048 -validity 10000
```

Then create `keystore.properties` in the repo root:

```
storeFile=/home/you/keys/smartertube.jks
storePassword=...
keyAlias=smartertube
keyPassword=...
```

`smarttubetv/build.gradle` reads this file for both debug and release builds. `.gitignore` covers `keystore.properties`, `*.jks` and `*.keystore`. Without the file, builds are signed with the debug key in `.android/` inside this checkout. That works, but a build from a different checkout or machine won't install over it until you uninstall first.

## Installing on a Fire TV

On the TV, turn on Settings > My Fire TV > Developer options > ADB debugging, and find the IP under Settings > My Fire TV > About > Network. Then:

```sh
scripts/install-firetv.sh 192.168.1.50
```

The script connects with adb, builds the `stlocal` debug APK and installs it with `adb install -r`. It installs the `armeabi-v7a` APK by default; set `ABI=arm64-v8a` or `ABI=universal` to install a different one. The first time, accept the debugging prompt on the TV.

## How the row works

The code is in `common/src/main/java/com/liskovsoft/smartyoutubetv2/common/localrecs/`.

The row is built for discovery. By default it only suggests channels you have never watched or subscribed to.

1. Seeds are the last 20 videos in your YouTube history. Recent videos count for more, and so do videos you finished. Completion comes from local playback state when we have it, otherwise from YouTube's progress bar.
2. Known channels are every channel in the first two pages of your history, your subscriptions and SmartTube's local playback history. TV tiles often leave out the channel id, so ids are filled in from other tiles of the same channel by name.
3. We fetch YouTube's related videos for the 10 strongest seeds. Videos you abandoned before 30% don't count as seeds for this, since they say little about your taste. A new channel scores the sum of the weights of the distinct seeds it turned up next to. So a channel that appears beside five different videos you liked beats one that appears five times beside a single video.
4. Second hop: for the 10 best new channels we open their best video. Its page gives the channel id, which TV tiles rarely include, and its own related videos. Those reach channels one step further out and count for 60% as much. One parent video can add at most 3 videos to the row, so a single odd hop (say, a Tiny Desk concert) can't fill it with pop music.
5. We fetch the latest uploads (RSS) from the 12 best new channels, so the row isn't limited to what YouTube chose to put in its related lists.
6. Each candidate gets a score that mixes that channel score, how many seeds it was related to, and how new it is.
7. The filter drops known channels, watched videos, videos longer than the length limit (2 hours by default, changeable in settings), videos under 1,000 views, films, blank-looking titles, anything YouTube's own Home is showing right now, Shorts, live and upcoming streams, duplicates, channels you've blocked in SmartTube, and titles that contain your blocked words.
8. Each channel gets at most 2 items, and a channel loses score for every item it already has in the row. If the row is still short, the best channels can have a third.
9. The row holds up to 60 items. With fewer than 10 it is hidden.

### AI assistance (optional)

With API keys set, two models help. Both are off until their keys are set, and the row works exactly as before without them.

Jev (TypeSafe) judges the best 150 main-row candidates on each refresh. It sees a video's title, channel, length and view count, plus about 15 titles you finished and 15 you removed or gave up on. It answers three questions: is this background filler, is it made for young children, and how likely is this viewer to enjoy it. Filler and kids' content are dropped, and the rest are weighted by the enjoyment score. You never write a profile; the examples are the profile, and they update as you watch.

Claude (Anthropic, `claude-opus-5`) writes about 8 Left Field ideas a day. It reads the same examples plus your subscriptions, works out why you like what you finish, and proposes topics that share the reason but not the subject, each with a YouTube search. The app runs the searches, keeps only channels you've never watched, asks Jev whether each video is well made and really fits the idea, and builds a second row, "Left Field (Local)", taking turns between ideas. An idea that gets two or more removals and fewer finished videos is dropped and reported back to Claude as rejected. One with two finished videos is reported as landed, so Claude builds on it.

The app also tracks what happens to the videos it shows: finished (80% or more), abandoned (under 30%), or shown in 4 refreshes without being played. Videos you pass over are dropped, and finished and abandoned ones become examples.

Keys are set in Settings > General > For You (Local), or by writing them to the app over adb, which is easier than typing with a remote:

```sh
printf 'typesafe=...\nanthropic=...\n' | adb shell run-as org.smarttube.localrecs sh -c 'cat > files/localrecs_keys.txt'
```

The app reads that file on the next Home load and deletes it. Keys are kept in the app's private storage. They survive updates installed with `adb install -r`, but not an uninstall. Anyone with root, or adb access to this debug build, can read them.

Using these means video titles, channel names and your subscriptions are sent to TypeSafe and Anthropic. Calls go straight from the TV. They are made only during the background refresh (every 2 hours by default) and the daily idea generation, never while you browse.

### Removing videos

Long-press a video in the row and choose "Remove from For You (Local)". It disappears straight away, and the next refresh learns from it in three ways. The video never comes back. Its channel scores half as much for each removal, and after two removals the channel is dropped entirely. The watched videos that led to it (its seeds) count for 30% less per removal, so fewer videos like it turn up. The last 300 removals are remembered and older ones fade out. Settings > General > For You (Local) > Forget removed videos clears them. Removals stay on the device and are not sent to YouTube.

The menu item is switched on the first time the row loads. Like any other menu item, it can be hidden in Settings > General > Context menu.

Each refresh writes a summary to `files/app_prefs/localrecs_debug` in the app's storage: seeds, subscriptions, known channels, top new channels and why each video made the row. Read it with `adb shell run-as org.smarttube.localrecs cat files/app_prefs/localrecs_debug`.

If you turn off "Only suggest channels I haven't watched", the row becomes a mix. Your favourite channels' latest uploads come back (RSS from the top 8 channels by affinity), new channels score slightly lower than known ones, and two slots are kept for the best new-channel picks. Changing the mode throws away the cached row and builds a new one.

The scoring code in `localrecs/ranking` and `localrecs/cache` is plain Java with no Android or network calls, and the unit tests cover it. `Ranker` is the place to plug in a different ranker later. Diversity rules run after it, so a new ranker only has to score relevance.

When Home loads, the cached row shows straight away. If it's older than the refresh interval (2 hours by default), a refresh runs in the background and the new row appears on the next Home load. The one exception is the very first run, when there's no cache yet. Home then waits up to 6 seconds for the first refresh before it carries on without the row. A refresh makes at most 30 service calls, runs 3 video-page lookups at a time, gives each call 10 seconds and the whole refresh 90. Any error is logged and the row is skipped. Home itself never fails because of it.

Signed-out users get no row, because there's no history to seed it. The row also hides when the device is offline.

Settings live under Settings > General > For You (Local). There you can turn the row on or off, choose between new channels only and a mix, change the refresh interval, add blocked title words, or start a refresh.

The built-in updater is off in the `stlocal` flavour. It's controlled by the `localrecs_updater_disabled` bool resource, which `smarttubetv/src/stlocal/res/values/strings.xml` sets to true. "Check for updates" just shows a message.

## Upstream files we touch

| File | Why |
|---|---|
| `common/.../presenters/BrowsePresenter.java` | Puts our row in front of the Home feed with `Observable.concat` |
| `common/.../presenters/settings/GeneralSettingsPresenter.java` | Adds the For You (Local) settings entry |
| `common/.../presenters/dialogs/AppUpdatePresenter.java` | Stops update checks when the build flag is set |
| `common/.../dialogs/menu/providers/ContextMenuManager.java` | Registers the "Remove from For You (Local)" menu item |
| `smarttubetv/build.gradle` | Adds the `stlocal` flavour |
| `.gitignore` | Ignores devenv state and signing files |

Everything else is new files: `localrecs/`, `common/src/main/res/values/localrecs.xml`, `smarttubetv/src/stlocal/`, `scripts/`, the devenv files and this document.

## Manual test checklist (Fire TV)

1. Install with `scripts/install-firetv.sh`. SmarterTube should show up next to official SmartTube.
2. Sign in and watch a few videos to the end. Open Home. The first time, the row can take a few seconds to appear, or only show up on the next Home load.
3. Check that "For You (Local)" is the first row and the YouTube rows load below it as usual.
4. Play a video from the row. Playback, the long-press menu and history should all work.
5. Long-press a video in the row and choose "Remove from For You (Local)". It should vanish at once and not return after Refresh now. Remove two videos from the same channel and that channel should stop appearing.
6. Check that none of the channels in the row are ones you have watched or subscribed to, and that the videos are not the ones on YouTube's Home rows below. Then finish a video from the row. Go to Settings > General > For You (Local) > Refresh now, then reopen Home. The video you watched should be gone.
7. Turn the row off in the same settings dialog. Back on Home, the row should be gone and the rest of Home unchanged. Turn it back on and it should return.
8. Add a blocked word that matches a title in the row and refresh. That video should drop out.
9. Turn off Wi-Fi and open Home. The row should not appear, and Home should show its usual offline error.
10. Sign out. The row should not appear.
11. Go to Settings > About > Check for updates. You should get the "Updates are turned off" message, and no update dialog.
