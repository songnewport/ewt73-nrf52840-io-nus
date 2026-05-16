# E73 nRF52840 IO NUS Test

Bring-up firmware for the EBYTE E73 / EWT73 nRF52840 test board.

BLE device name: `Justin_Shunt_Test`

This project advertises Nordic UART Service for debug and a custom Justin Smart
Shunt Test GATT service for product-flow security testing.

Known-good result on 2026-05-15:

```text
INA228 vbus_mv ~= 3329
INA228 temp_x10 = 216
nRF52840 nrf_temp_x10 = 220
```

Stage 1 button refactor verified on 2026-05-16:

```text
BUTTON,PAIR_SHORT,count=...
BUTTON,ENTER_PAIR_MODE,count=...
BUTTON,CLEAR_BONDS_REQUESTED,count=...
```

The user confirmed short press, 5-second long press, and 10-second long press
events over BLE NUS.

## Features

- BLE Nordic UART Service peripheral
- LED heartbeat and connection indication
- Devicetree-based button inputs
- Interrupt-based PAIR button events
- SAADC readings on AIN1 and AIN4
- INA228 over I2C
- nRF52840 internal die temperature
- Custom Justin Smart Shunt Test GATT service
- Bonding/encrypted LED control characteristic
- 60-second physical PAIR button bonding window
- Bond clearing with the 10-second PAIR button event

## Stage 2 Custom GATT

UUIDs used by both firmware and the Android test app:

```text
Service:       12345678-1234-5678-1234-56789abcdef0
Live Data:     12345678-1234-5678-1234-56789abcdef1  READ/NOTIFY
LED Control:   12345678-1234-5678-1234-56789abcdef2  WRITE encrypted
Device Status: 12345678-1234-5678-1234-56789abcdef3  READ/NOTIFY
```

Security flow:

```text
Boot:       bt_set_bondable(false)
5s PAIR:   bt_set_bondable(true), 60-second pairing window
10s PAIR:  bt_unpair(BT_ID_DEFAULT, BT_ADDR_LE_ANY), then non-bondable mode
```

The LED control characteristic uses `BT_GATT_PERM_WRITE_ENCRYPT`. The firmware
also checks that the encrypted peer is bonded before accepting `0x00` or `0x01`.
NUS remains enabled only for debug logs and terminal testing.

## Pins

- LED0: `P0.12`, active low
- LED1: `P0.04`, active low
- LED2: `P1.09`, active low
- SW1: `P0.15`, pull-up input
- SW3: `P0.20`, pull-up input
- SW4: `P0.17`, pull-up input
- AIN1: `P0.03`
- AIN4: `P0.28`
- INA228 SDA: `P1.11`
- INA228 SCL: `P1.10`
- INA228 I2C address: `0x40`

## Example NUS Output

```text
ADC,seq=12,ain1=3805/3344,ain4=0/0,sw1=0,sw3=0,sw4=0
INA,seq=12,vbus_mv=3329,shunt_uv=0,current_ma=0,power_mw=0,temp_x10=216,nrf_temp_x10=220
```

After the button refactor, the ADC line also includes button subsystem status:

```text
ADC,seq=12,ain1=3805/3344,ain4=0/0,btn_init=0,sw1=0,sw3=0,sw4=0,pair=1/0,clear=0
```

Button counters:

- `pair=a/b` means `a` short presses and `b` 5-second pair-mode requests
- `clear=c` means `c` 10-second clear-bonds requests

Button events are also sent immediately:

```text
BUTTON,PAIR_SHORT,count=1
BUTTON,ENTER_PAIR_MODE,count=1
BUTTON,CLEAR_BONDS_REQUESTED,count=1
```

Temperature fields use x10 Celsius:

- `temp_x10=216` means INA228 die temperature is 21.6 C
- `nrf_temp_x10=220` means nRF52840 die temperature is 22.0 C

## Build

```powershell
& "$env:LOCALAPPDATA\Microsoft\WinGet\Links\nrfutil.exe" toolchain-manager launch --ncs-version v3.3.0 -- west build --no-sysbuild -b nrf52840dk/nrf52840 . -d C:\ncs\build_ewt73_io_nus --pristine
```

If a fresh NCS install is missing BLE security crypto modules, run:

```powershell
& "$env:LOCALAPPDATA\Microsoft\WinGet\Links\nrfutil.exe" toolchain-manager launch --ncs-version v3.3.0 -- west update mbedtls
& "$env:LOCALAPPDATA\Microsoft\WinGet\Links\nrfutil.exe" toolchain-manager launch --ncs-version v3.3.0 -- west update oberon-psa-crypto
```

## Flash

```powershell
& "$env:LOCALAPPDATA\Microsoft\WinGet\Links\nrfutil.exe" device program --serial-number 69405231 --family nrf52 --swd-clock-frequency 1000 --firmware C:\ncs\build_ewt73_io_nus\zephyr\zephyr.hex --options chip_erase_mode=ERASE_ALL,verify=VERIFY_READ,reset=RESET_HARD --log-level info
& "$env:LOCALAPPDATA\Microsoft\WinGet\Links\nrfutil.exe" device reset --serial-number 69405231
```

## Android Test App

The Stage 2 Android proof-of-concept app is under:

```text
android/JustinShuntTest
```

It is a native Kotlin single-activity project. It requests Android 12+
Bluetooth permissions, scans for `Justin_Shunt_Test`, calls `createBond()`,
waits for `ACTION_BOND_STATE_CHANGED`, discovers the custom GATT service,
subscribes to Live Data, and writes the encrypted LED Control characteristic.

## Snapshots

The known-good local snapshot is stored under:

```text
snapshots/ina228_nrf_temp_ok_2026-05-15
```

It includes source files and a tested `merged.hex`.

## Button Architecture

Buttons are defined in devicetree with `gpio-keys` and `sw0`/`sw1`/`sw2`
aliases. Application code does not hardcode button pin numbers.

`src/button_control.c` owns the PAIR button interrupt and event generation:

- `gpio_pin_interrupt_configure_dt()` enables edge interrupts
- `gpio_init_callback()` and `gpio_add_callback()` register the GPIO callback
- the ISR only schedules debounce work
- `k_work_delayable` handles debounce and long-press timers

PAIR button behavior:

- short press: increments short press counter
- hold 5 seconds: generates pair-mode request
- hold 10 seconds: generates clear-bonds request
