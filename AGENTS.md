# Nordic BLE Project Rules for Codex

## Purpose

This document defines the mandatory rules Codex must follow when working on
this Nordic / Zephyr / nRF Connect SDK BLE project.

The goal is to keep this project close to official Nordic examples, avoid
unnecessary rewrites, and maintain long-term compatibility with Nordic NCS and
Zephyr.

## Core Principle

This project must use the official Nordic / Zephyr BLE sample structure as the
baseline.

The project should feel like:

```text
Official Nordic sample + minimal patch
```

It must not become:

```text
Custom BLE framework inspired by Nordic
```

Priority order:

1. Official Nordic flow
2. Minimum modification
3. Easy debugging
4. Easy SDK upgrade
5. Stable BLE behavior
6. Long-term maintainability

## Official Baseline Requirement

Use official Nordic / Zephyr BLE examples as the source of truth.

Preferred references:

- Nordic `peripheral_uart`
- Nordic / Zephyr NUS-style BLE data flow
- Nordic `peripheral_lbs` / LED Button Service concept
- Zephyr official Bluetooth connection callbacks
- Zephyr official GATT service declaration style
- Zephyr official SMP / pairing / bonding / security flow
- Zephyr settings subsystem for bond storage

Codex must preserve the official SDK structure unless there is a very strong
technical reason not to.

## Absolute Restrictions

Codex must not:

- Rewrite the project from scratch
- Rewrite `main.c` completely
- Replace the official BLE init flow
- Replace Zephyr/Nordic connection callbacks with a custom framework
- Create a custom BLE architecture
- Create a custom pairing manager
- Create a custom bonding database
- Create a custom reconnect manager
- Create a custom GATT engine
- Manually store BLE bond keys
- Create an application-level encryption system
- Treat BLE like Wi-Fi, TCP, or socket programming
- Add a large new abstraction layer
- Add unnecessary modules
- Rename official-style functions unnecessarily
- Remove official sample logic without clear justification
- Modify unrelated files
- Modify build system unnecessarily
- Reimplement anything the Nordic / Zephyr SDK already provides

The most important rule:

```text
Do not reimplement what the SDK already provides.
```

## Allowed Changes

Codex may only make minimal changes required for the requested feature.

Allowed changes include:

- Adding custom service UUIDs
- Adding custom characteristic UUIDs
- Adding GATT read/write callbacks
- Adding notification support
- Adding CCCD handling
- Adding encrypted read/write permissions
- Enabling bonding/security options in `prj.conf`
- Calling `settings_load()` after `bt_enable()` when bond storage is enabled
- Registering Zephyr authentication callbacks when required
- Adding a button-controlled pairing window
- Adding LED control characteristic
- Adding simple real-time data notifications
- Adding clear debug logs
- Adding a minimal Android test app if requested

All changes must be as small as possible.

## BLE Security Philosophy

Codex must follow the official Nordic / Zephyr BLE security model.

BLE pairing is not an application-level state machine. BLE pairing is driven by:

- GATT security permissions
- Zephyr Bluetooth stack
- SMP, the BLE Security Manager Protocol
- Bond storage in the Zephyr settings subsystem

The application layer should only:

- Enable a temporary pairing window if required
- Start/stop advertising when needed
- React to official callbacks
- Expose encrypted characteristics
- Control application features such as LED and data notification

The BLE stack handles:

- Pairing
- Bonding
- Encryption
- Key exchange
- Reconnect encryption
- Long-term key storage
- MITM / passkey / confirmation flow

## Official Pairing Flow

Expected first-use flow:

```text
Advertising
Phone connects
Phone accesses encrypted characteristic
Zephyr/Nordic stack automatically triggers pairing
User confirms pairing / passkey if enabled
Bond keys are exchanged
Bond is stored automatically
Future reconnect is automatically encrypted
```

Codex must not replace this with custom logic.

## Permission-Driven Security

Pairing should normally be triggered by encrypted GATT permissions.

Preferred permissions for protected characteristics:

```c
BT_GATT_PERM_READ_ENCRYPT
BT_GATT_PERM_WRITE_ENCRYPT
```

Do not manually force a custom pairing process unless the official
Zephyr/Nordic flow requires it.

The app should connect and access a protected characteristic. The BLE stack
should then request pairing automatically.

## Hardware Button Pairing Window

A hardware button may be used only as a physical security gate. The button does
not implement pairing.

The button may enable a temporary pairing window:

```text
Button pressed
allow_pairing = true for 60 seconds
Advertising allows new phone connection
Phone connects
Phone accesses encrypted characteristic
Zephyr stack performs pairing/bonding
Bond stored by settings subsystem
allow_pairing = false
```

Do not implement a custom pairing protocol behind the button.

## Bond Storage

Use the official Zephyr settings subsystem for bond storage.

Required concepts:

```text
CONFIG_SETTINGS=y
CONFIG_BT_SETTINGS=y
settings_load()
```

When persistent bonding is enabled, `settings_load()` must be called after
`bt_enable()`.

Do not create custom flash storage for bonds. Do not manually save phone
identity, keys, or LTK values unless explicitly required by official Zephyr
APIs.

## Official Callback Architecture

Use Zephyr/Nordic official callbacks as the state flow.

Typical callbacks include:

```c
connected()
disconnected()
security_changed()
pairing_confirm()
passkey_display()
passkey_confirm()
auth_cancel()
```

Avoid large custom BLE state machines unless there is a clear technical reason.
Small application variables such as `allow_pairing` or `notify_enabled` are
acceptable.

## Android App Pairing Logic

The Android test app must not implement a fake pairing system.

Expected first-use flow:

```text
Show instruction: press the pairing button on the device
Scan for BLE device
Connect
Access encrypted characteristic
Android BLE stack triggers system pairing popup
User accepts pairing
Bond is stored by phone OS and peripheral
Enter main page
```

Expected later-use flow:

```text
Scan or reconnect to bonded device
BLE stack restores encrypted connection
No pairing popup required
Enter main page directly
```

The app may store the device address for reconnection convenience, but it must
not store BLE encryption keys manually.

## Android Test App Scope

The Android app is a simple test app unless otherwise requested.

It should include:

- First-use pairing instruction screen
- BLE scan/connect
- System pairing/bonding through Android BLE stack
- Main page showing real-time data
- Connection status
- One LED ON/OFF control button

Do not over-engineer the Android app with unnecessary architecture, dependency
injection, databases, or custom security managers.

## Patch-First Workflow

Before modifying code, Codex must inspect the current project and explain:

1. Which official Nordic sample structure is being preserved
2. Which files will be changed
3. Why each file must be changed
4. What will not be changed
5. Whether any requested change risks deviating from official Nordic flow

Then Codex should make a small patch.

Prefer small commits and small diffs.

## Change Size Limit

The first implementation should be small:

- No large refactor
- No unnecessary modules
- No complex custom state machine
- No production-level Android architecture unless requested
- No unrelated cleanup
- No formatting-only changes across unrelated files

If the task requires a large rewrite, Codex must stop and explain why before
changing code.

## Verification Requirements

After changes, verify as much as possible:

- Project builds successfully
- BLE advertising works
- Phone can discover the device
- Phone can connect
- Pairing is triggered by encrypted characteristic access
- Bonding is stored
- Reconnection works after power cycle
- Encrypted connection is restored after reconnect
- Real-time notification works
- CCCD enable/disable works
- LED write command works
- Logs clearly show connection, security, CCCD, notification, and write events

If physical testing cannot be performed, Codex must clearly say what was not
tested.

## Logging Requirements

Add clear but not excessive logs for:

- Bluetooth initialization
- Settings load
- Advertising start/stop
- Connection
- Disconnection
- Security level changes
- Pairing confirmation/passkey if used
- Bonding success/failure if available
- CCCD notification enabled/disabled
- Notification sent/failure
- LED write command received

Do not flood logs inside high-frequency loops.

## Bad Examples

Do not:

```text
Create new custom_ble_manager.c
Create custom bonding database
Create custom reconnect state machine
Manually save phone keys to flash
Bypass Zephyr GATT security
Replace official callbacks with custom event engine
Rewrite main.c into a new architecture
```

These approaches are architecture drift and are not acceptable.

## Good Examples

Good approach:

```text
Start from official peripheral_uart or peripheral_lbs
Keep bt_enable() flow
Enable required Kconfig options
Call settings_load() after bt_enable()
Add encrypted GATT characteristic
Register official auth callbacks
Use connected/disconnected/security_changed callbacks
Use button only to allow temporary pairing window
Keep LED and notification logic simple
```

## When Unsure

When unsure, preserve official Nordic behavior over implementing new
abstractions.

When a requested feature conflicts with official Nordic/Zephyr flow, Codex must
explain the conflict before changing code.

## Final Reminder

This is an embedded BLE project.

Long-term stability matters more than clever code. Official SDK compatibility
matters more than fast implementation. Small patches are better than large
rewrites.

