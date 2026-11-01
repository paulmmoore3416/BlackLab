# BlackLab on Android Auto — 2023 Hyundai Santa Fe

BlackLab 5.0 runs on the car's display through Android Auto. It was designed around the 2023 Santa Fe's
two head units — the 8-inch display (wireless Android Auto) and the 10.25-inch wide navigation display
(USB) — but works on any Android Auto car.

## One-time setup

BlackLab isn't installed from the Play Store, so Android Auto has to be told to show it:

1. On the phone: **Settings › Connected devices › Connection preferences › Android Auto**.
2. Scroll to the bottom and tap **Version** about ten times until it says developer settings are enabled.
3. Tap **⋮ › Developer settings** and turn on **Unknown sources**.
4. Back in Android Auto settings, open **Customize launcher** and make sure **BlackLab** is checked
   (it appears twice: the BlackLab app, and BlackLab's music & recordings).
5. Grant BlackLab the microphone permission on the phone (it already has it if you use the assistant).

Then connect to the car:

- **8-inch display**: wireless Android Auto — pair once over Bluetooth, then it connects automatically.
- **10.25-inch navigation display**: plug the phone into the front USB data port with a data-capable cable.

Open the app launcher on the car screen and tap **BlackLab**.

## What's on the car screen

| Tile | What it does |
| --- | --- |
| **Ask BlackLab** (also the mic in the header) | Hands-free assistant through the car's microphone. Everything the phone assistant does: tasks, calendar, notes, email, summaries, knowledge questions, Garage logging, and "navigate to my next meeting" / "find a gas station" (hands off to Google Maps or Waze). |
| **Brief me** | Daily briefing plus spoken summaries: tomorrow, the week ahead, latest meeting, recent notes, open tasks, your journal week. |
| **Agenda** | Today and tomorrow. Tap an event for **Navigate** to its location and **Call** if a number is in the notes. |
| **Tasks** | Due, overdue, My Day and important tasks with check-off toggles; **Add** a task by voice; **Read** them aloud. |
| **Talk** | The seven conversation knowledge bases, by voice and hands-free — it listens again after each answer. Say "that's all" to finish. |
| **Voice note** | Dictate as long as you like; it's saved to Brainstorm notes with the label `car`. |
| **Santa Fe** (Garage) | Odometer and fuel level/range when the car reports them, what service is due, MPG; **Log fuel** and **Log service** by voice; **Schedule** and a **Gas** shortcut; **Dealer** navigates to your service center. |

Task and event reminders also pop up on the car display, with **Done** and **Snooze** on task reminders.

Music: BlackLab's library (songs, albums, playlists and your recordings and dictations) shows up in
Android Auto's media launcher. "Hey Google, play *song* on BlackLab" searches it.

## Driving-safety design

- No typing while driving — everything is a tap or your voice.
- Short titles and two-line rows; replies are spoken through the car speakers and music ducks under them.
- Screens update in place rather than stacking up new ones, which keeps within Android Auto's limits.
- Lists respect how many rows the car allows at the moment (some cars show fewer while moving).

## Settings

**Settings › Android Auto**: speak replies in the car, hands-free conversations, and brief me when I connect.

## Car data

When the Santa Fe shares them, BlackLab reads the model and year, fuel level and range, and the odometer
(which updates the Garage automatically). What a car shares is up to the manufacturer; anything not
reported is simply not shown, and the Garage uses the readings you log.

## Testing without the car

Google's Desktop Head Unit (DHU) runs Android Auto on a computer:

```bash
~/Android/Sdk/tools/bin/sdkmanager "extras;google;auto"     # or SDK Manager › SDK Tools › Android Auto Desktop Head Unit
# On the phone: Android Auto › ⋮ › Start head unit server
~/Android/Sdk/platform-tools/adb forward tcp:5277 tcp:5277
~/Android/Sdk/extras/google/auto/desktop-head-unit
```

`app/src/androidTest/.../car/CarScreensTest.kt` builds every car screen's template on the emulator,
so Android Auto's layout rules (row and action limits) are checked in CI.
