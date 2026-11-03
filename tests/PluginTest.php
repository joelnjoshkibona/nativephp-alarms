<?php

/**
 * Plugin validation tests for Alarms.
 *
 * Run with: ./vendor/bin/pest
 */

beforeEach(function () {
    $this->pluginPath = dirname(__DIR__);
    $this->manifest = json_decode(file_get_contents($this->pluginPath.'/nativephp.json'), true);
});

describe('Plugin Manifest', function () {
    it('is valid json with the required fields', function () {
        expect(json_last_error())->toBe(JSON_ERROR_NONE);
        expect($this->manifest)->toHaveKeys(['name', 'namespace', 'bridge_functions']);
        expect($this->manifest['name'])->toBe('blutrixx/nativephp-alarms');
        expect($this->manifest['namespace'])->toBe('Alarms');
    });

    it('names a Kotlin class for every bridge function', function () {
        $kotlin = file_get_contents($this->pluginPath.'/resources/android/AlarmsFunctions.kt');

        foreach ($this->manifest['bridge_functions'] as $function) {
            expect($function['name'])->toStartWith('Alarms.');
            $class = substr($function['android'], strrpos($function['android'], '.') + 1);
            expect($kotlin)->toContain("class {$class}(");
        }
    });

    it('calls only bridge functions that the manifest declares', function () {
        $php = file_get_contents($this->pluginPath.'/src/Alarms.php');
        preg_match_all("/'(Alarms\.[A-Za-z]+)'/", $php, $m);
        $declared = array_column($this->manifest['bridge_functions'], 'name');

        foreach (array_unique($m[1]) as $name) {
            expect($declared)->toContain($name);
        }
    });

    it('registers every receiver it ships', function () {
        $names = array_column($this->manifest['android']['receivers'], 'name');
        foreach ($names as $name) {
            $file = $this->pluginPath.'/resources/android/'.ltrim($name, '.').'.kt';
            expect(file_exists($file))->toBeTrue();
        }
    });

    it('restores reminders after a reboot (BOOT_COMPLETED filter on the boot receiver)', function () {
        $boot = collect($this->manifest['android']['receivers'])->firstWhere('name', '.AlarmsBootReceiver');
        expect($boot['intent_filters'][0]['action'])->toContain('android.intent.action.BOOT_COMPLETED');
        expect($this->manifest['android']['permissions'])->toContain('android.permission.RECEIVE_BOOT_COMPLETED');
    });
});

describe('Kotlin sources', function () {
    it('all declare the plugin package', function () {
        foreach (glob($this->pluginPath.'/resources/android/*.kt') as $file) {
            expect(file_get_contents($file))->toContain('package com.blutrixx.plugins.alarms');
        }
    });
});
