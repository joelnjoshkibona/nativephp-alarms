<?php

namespace Blutrixx\Alarms\Facades;

use Illuminate\Support\Facades\Facade;

/**
 * @method static array configure()
 * @method static array schedule(string $id, string $title, string $body, array $times, ?string $url = null, int $snoozeMinutes = 0)
 * @method static array cancel(string $id)
 * @method static array cancelAll()
 * @method static array pending()
 * @method static array status()
 * @method static array requestNotificationPermission()
 * @method static array openSettings(string $page = 'app')
 * @method static array history(int $limit = 50)
 * @method static array clearHistory()
 *
 * @see \Blutrixx\Alarms\Alarms
 */
class Alarms extends Facade
{
    protected static function getFacadeAccessor(): string
    {
        return \Blutrixx\Alarms\Alarms::class;
    }
}
