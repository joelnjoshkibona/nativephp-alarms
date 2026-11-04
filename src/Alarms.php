<?php

namespace Blutrixx\Alarms;

use DateTimeInterface;
use InvalidArgumentException;

/**
 * Reminders that fire on time with the app closed.
 *
 * A reminder is a list of times. Android is given the first one; when it fires, the phone arms the next
 * one from the list by itself, so a repeating reminder keeps going with no PHP running. Compute the next
 * few weeks of occurrences in PHP, pass them as `$times`, and top the list up whenever the app opens.
 *
 * Every method returns an array. A failed call returns ['status' => 'error', 'code' => ..., 'message' => ...].
 */
class Alarms
{
    /**
     * Send the notification channel, the missed-reminder window and the small icon (config/alarms.php) to the phone.
     * Call once when the app starts, or when those settings change.
     */
    public function configure(): array
    {
        $params = $this->channel();

        $icon = config('alarms.small_icon');
        if ($icon) {
            $path = base_path($icon);
            if (is_file($path)) {
                $params['icon'] = base64_encode((string) file_get_contents($path));
            }
        }

        return $this->call('Alarms.Configure', $params);
    }

    /**
     * Schedule (or replace) a reminder.
     *
     * @param  string  $id  Your own id. Scheduling the same id again replaces it.
     * @param  array<int, int|DateTimeInterface>  $times  Epoch seconds or dates. Past times are ignored; at most 100 are kept.
     * @param  string|null  $url  Opened when the reminder is tapped, for example 'myapp://tasks/42' (the app's deep link scheme).
     * @param  int  $snoozeMinutes  Adds a Snooze button that shows the reminder again after this many minutes. 0 for none.
     */
    public function schedule(string $id, string $title, string $body, array $times, ?string $url = null, int $snoozeMinutes = 0): array
    {
        if ($id === '') {
            throw new InvalidArgumentException('A reminder needs an id.');
        }

        $seconds = array_map(
            fn ($t) => $t instanceof DateTimeInterface ? $t->getTimestamp() : (int) $t,
            $times,
        );

        return $this->call('Alarms.Schedule', $this->channel() + [
            'id' => $id,
            'title' => $title,
            'body' => $body,
            'times' => array_values($seconds),
            'url' => $url,
            'snoozeMinutes' => max(0, $snoozeMinutes),
        ]);
    }

    public function cancel(string $id): array
    {
        return $this->call('Alarms.Cancel', ['id' => $id]);
    }

    public function cancelAll(): array
    {
        return $this->call('Alarms.CancelAll');
    }

    /** @return array{count?: int, items: array<int, array{id: string, title: string, next: int, remaining: int}>} */
    public function pending(): array
    {
        return $this->decodeItems($this->call('Alarms.GetPending'));
    }

    /**
     * What Android allows right now.
     *
     * @return array{notifications?: bool, exactAlarms?: bool, batteryUnrestricted?: bool, sdk?: int, manufacturer?: string}
     */
    public function status(): array
    {
        return $this->call('Alarms.Status');
    }

    /** Asks for the notification permission on Android 13 and above. Read status() afterwards. */
    public function requestNotificationPermission(): array
    {
        return $this->call('Alarms.RequestNotificationPermission');
    }

    /** @param  'notifications'|'exact'|'battery'|'autostart'|'app'  $page */
    public function openSettings(string $page = 'app'): array
    {
        return $this->call('Alarms.OpenSettings', ['page' => $page]);
    }

    /**
     * The last reminders that fired, newest first. `due` against `firedAt` shows how late Android was.
     *
     * @return array{items: array<int, array{id: string, title: string, due: int, firedAt: int, shown: bool, source: string}>}
     */
    public function history(int $limit = 50): array
    {
        return $this->decodeItems($this->call('Alarms.History', ['limit' => $limit]));
    }

    public function clearHistory(): array
    {
        return $this->call('Alarms.ClearHistory');
    }

    /** @return array<string, mixed> */
    private function channel(): array
    {
        return [
            'channelId' => config('alarms.channel_id'),
            'channelName' => config('alarms.channel_name'),
            'channelDescription' => config('alarms.channel_description'),
            'missedWindowMinutes' => config('alarms.missed_window_minutes'),
        ];
    }

    private function decodeItems(array $result): array
    {
        if (isset($result['items']) && is_string($result['items'])) {
            $result['items'] = json_decode($result['items'], true) ?? [];
        }

        return $result;
    }

    private function call(string $function, array $params = []): array
    {
        if (! function_exists('nativephp_call')) {
            return ['status' => 'error', 'code' => 'NOT_ON_DEVICE', 'message' => 'Alarms only work inside the NativePHP app.'];
        }

        $result = nativephp_call($function, json_encode($params === [] ? new \stdClass() : $params));

        return $result ? (json_decode($result, true) ?? []) : [];
    }
}
