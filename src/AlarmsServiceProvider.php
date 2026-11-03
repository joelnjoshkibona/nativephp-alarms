<?php

namespace Blutrixx\Alarms;

use Illuminate\Support\ServiceProvider;

class AlarmsServiceProvider extends ServiceProvider
{
    public function register(): void
    {
        $this->mergeConfigFrom(__DIR__.'/../config/alarms.php', 'alarms');

        $this->app->singleton(Alarms::class, fn () => new Alarms());
        $this->app->alias(Alarms::class, 'nativephp.alarms');
    }

    public function boot(): void
    {
        if ($this->app->runningInConsole()) {
            $this->publishes([
                __DIR__.'/../config/alarms.php' => config_path('alarms.php'),
            ], 'alarms-config');
        }
    }
}
