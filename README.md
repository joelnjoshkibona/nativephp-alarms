# Alarms plugin for NativePHP Mobile

Reminders that fire on time with the app closed. Android only. No server, no Firebase.

NativePHP Mobile has no on-device scheduled notifications in core (push is server-sent). This plugin schedules them with Android's `AlarmManager`: exact when Android allows it, restored after a reboot, and with no PHP running when the alarm fires.

## Installation

```bash
composer require blutrixx/nativephp-alarms
php artisan native:plugin:register blutrixx/nativephp-alarms
```

In a project that uses the local `packages/*` path repositories (as DevBiz does), require it as `*@dev`.

Optional config: `php artisan vendor:publish --tag=alarms-config`. Set the channel name the person sees in Android settings, how late a missed reminder may still show after a reboot, and a small notification icon.

## How it works

A reminder is a **list of times**. Android is given the first one. When it fires, the phone shows the notification and arms the next time in the list by itself. So a repeating reminder keeps going while the app stays closed, whatever the repeat rule is: compute the next weeks of occurrences in PHP (daily, "2nd Tuesday", every 3 months...) and pass them as `$times`. Top the list up whenever the app opens. At most 100 times are kept per reminder.

Android forgets every alarm when the phone restarts or the app is updated. The boot receiver arms them again from the phone's own storage. A time that passed while the phone was off shows once after boot if it passed within `missed_window_minutes` (default 6 hours).

## Usage

```php
use Blutrixx\Alarms\Facades\Alarms;

Alarms::configure();   // once at start: channel, icon, missed window

// Needs "Allow notifications" (Android 13+). Read status() afterwards.
Alarms::requestNotificationPermission();

Alarms::schedule(
    id: 'routine-r4',
    title: 'Morning walk',
    body: '30 minutes, then breakfast',
    times: [$next->timestamp, $afterThat->timestamp],   // epoch seconds or DateTimeInterface
    url: 'hodie://routine/r4',                         // opened when tapped (your deep link scheme)
    snoozeMinutes: 10,                                   // adds a Snooze button
);

Alarms::cancel('routine-r4');
Alarms::pending();   // ['items' => [['id', 'title', 'next', 'remaining'], ...]]
```

`Alarms::status()` returns what Android allows right now: `notifications`, `exactAlarms`, `batteryUnrestricted`. `Alarms::openSettings('notifications' | 'exact' | 'battery' | 'app')` opens the right Android page. `Alarms::history()` lists the last 100 reminders that fired with `due` and `firedAt`, which is how to see how late Android was, and `shown: false` when notifications were off.

Every call returns an array. A failure is `['status' => 'error', 'code' => ..., 'message' => ...]`.

## Permissions

| Permission | Why |
| --- | --- |
| `POST_NOTIFICATIONS` | Show the reminder (Android 13+, asked at run time) |
| `SCHEDULE_EXACT_ALARM` | Fire at the minute. Granted by default up to Android 13. From Android 14 the person switches it on in "Alarms & reminders" (`openSettings('exact')`). Without it, reminders still fire, a few minutes late |
| `RECEIVE_BOOT_COMPLETED` | Restore reminders after a restart |
| `VIBRATE` | Notification vibration |

The plugin does **not** declare `USE_EXACT_ALARM`: Google Play limits it to alarm-clock and calendar apps.

## Limits

* Android only. No iOS.
* Some makers kill background apps or block alarms (battery "restricted", auto-start lists). Check `status()['batteryUnrestricted']` and point the person to `openSettings('battery')`.
* A tap opens `url` through the app's deep link scheme (NativePHP `NATIVEPHP_DEEPLINK_SCHEME`). No PHP event is sent for taps.
* No PHP runs when an alarm fires. Anything that needs PHP at a set time (a midnight roll-over) should be done when the app opens, with the reminder as the prompt.

## Why not an existing plugin

`ikromjon/nativephp-mobile-local-notifications` (MIT) schedules alarms too, but as shipped its boot receiver has no `BOOT_COMPLETED` intent filter, so Android never calls it and every reminder is lost at a restart. This plugin was written after testing it on a device; the checks it passed are in `tests/PluginTest.php` and the device results below.

## Device results

Telpo M1K, Android 12, NativePHP Mobile 4.7.0, app swiped away and screen off, driven with `adb` (3 November 2026):

| Check | Result |
| --- | --- |
| One-time reminders 60 s and 120 s ahead, app swiped away, screen off | Fired at the second they were due |
| Chain of 3 times (90, 150, 210 s) | All three fired on time; times 2 and 3 were armed by the phone with no PHP running |
| Reboot with reminders pending | Restored. One due after 5 minutes fired 1 s late after the restart; one that fell due while the phone was off showed once right after boot (`source: restore`) |
| Exact alarms switched off for the app | `schedule()` reports `exact: false`; Android used an inexact alarm with a 45 s window and the reminder still fired on time |
| Snooze button | Pressing it showed the reminder again a minute later |
| Tap the notification | Opens the app and clears the notification |
| `status()`, `pending()`, `history()` | Return what Android and the store hold |
| Notifications switched off | Not run: on Android 12 they can only be switched off by hand in Settings. History marks such reminders `shown: false` |

## Tests

`./vendor/bin/pest` checks the manifest against the Kotlin and PHP sources. Behaviour on a phone is tested with `adb` in a spike app (reminder with the app swiped away, forced Doze, reboot).

## License

MIT
