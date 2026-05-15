# E73_IO_NUS INA228 + nRF TEMP OK Snapshot

Date: 2026-05-15

Known-good test result reported by user:

- INA228 VBUS: about 3329 mV
- INA228 temperature: temp_x10=216, about 21.6 C
- nRF52840 internal temperature: nrf_temp_x10=220, about 22.0 C
- BLE NUS device name: E73_IO_NUS

Features in this snapshot:

- BLE Nordic UART Service peripheral
- LEDs on P0.12, P0.04, P1.09
- Switch inputs on P0.15, P0.20, P0.17
- SAADC AIN1 on P0.03 and AIN4 on P0.28
- INA228 on I2C0: SDA P1.11, SCL P1.10, address 0x40
- nRF52840 internal die temperature via Zephyr TEMP_NRF5 sensor driver

Included files:

- main.c
- nrf52840dk_nrf52840.overlay
- prj.conf
- CMakeLists.txt
- merged.hex

Flash command used:

```powershell
& "$env:LOCALAPPDATA\Microsoft\WinGet\Links\nrfutil.exe" device program --serial-number 69405231 --family nrf52 --swd-clock-frequency 1000 --firmware C:\ncs\build_ewt73_io_nus\merged.hex --options chip_erase_mode=ERASE_ALL,verify=VERIFY_READ,reset=RESET_HARD --log-level info
```
