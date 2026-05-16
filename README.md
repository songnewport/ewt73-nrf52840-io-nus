# E73 nRF52840 IO NUS Test

Bring-up firmware for the EBYTE E73 / EWT73 nRF52840 test board.

BLE device name: `E73_IO_NUS`

This project advertises Nordic UART Service and sends test data every 3 seconds
after a phone or PC connects and subscribes to NUS TX.

Known-good result on 2026-05-15:

```text
INA228 vbus_mv ~= 3329
INA228 temp_x10 = 216
nRF52840 nrf_temp_x10 = 220
```

## Features

- BLE Nordic UART Service peripheral
- LED heartbeat and connection indication
- Devicetree-based button inputs
- Interrupt-based PAIR button events
- SAADC readings on AIN1 and AIN4
- INA228 over I2C
- nRF52840 internal die temperature

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
& "$env:LOCALAPPDATA\Microsoft\WinGet\Links\nrfutil.exe" toolchain-manager launch --ncs-version v3.3.0 -- west build -b nrf52840dk/nrf52840 . -d C:\ncs\build_ewt73_io_nus --pristine
```

## Flash

```powershell
& "$env:LOCALAPPDATA\Microsoft\WinGet\Links\nrfutil.exe" device program --serial-number 69405231 --family nrf52 --swd-clock-frequency 1000 --firmware C:\ncs\build_ewt73_io_nus\merged.hex --options chip_erase_mode=ERASE_ALL,verify=VERIFY_READ,reset=RESET_HARD --log-level info
& "$env:LOCALAPPDATA\Microsoft\WinGet\Links\nrfutil.exe" device reset --serial-number 69405231
```

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
