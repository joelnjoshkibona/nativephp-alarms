<?php

return [
    /*
     * The notification channel reminders appear under in Android's settings.
     * The name is what the person sees. Android keeps the importance of a channel
     * once it exists, so choose the channel id once and keep it.
     */
    'channel_id' => env('ALARMS_CHANNEL_ID', 'blutrixx_alarms'),
    'channel_name' => env('ALARMS_CHANNEL_NAME', 'Reminders'),
    'channel_description' => env('ALARMS_CHANNEL_DESCRIPTION', 'Reminders you set'),

    /*
     * A reminder whose time passed while the phone was off still shows once after the
     * phone starts, if it passed no more than this many minutes ago.
     */
    'missed_window_minutes' => (int) env('ALARMS_MISSED_WINDOW_MINUTES', 360),

    /*
     * Path (relative to the app's base path) of a monochrome PNG, about 96 x 96, white on
     * transparent, used as the small notification icon. Sent to the phone by Alarms::configure().
     * Without one, Android's bell is used.
     */
    'small_icon' => env('ALARMS_SMALL_ICON'),
];
