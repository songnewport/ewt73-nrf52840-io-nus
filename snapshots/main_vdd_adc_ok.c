/*
 * E73 nRF52840 BLE NUS recovery baseline.
 *
 * This image intentionally starts with only LEDs + BLE advertising. Once this
 * advertises reliably, switches and ADC can be added back one at a time.
 */

#include <errno.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/conn.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <zephyr/drivers/adc.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/kernel.h>
#include <zephyr/sys/atomic.h>

#include <bluetooth/services/nus.h>
#include <soc.h>

#define DEVICE_NAME CONFIG_BT_DEVICE_NAME
#define DEVICE_NAME_LEN (sizeof(DEVICE_NAME) - 1)
#define SW1_PIN 15
#define SW3_PIN 20
#define SW4_PIN 17
#define ADC_VDD_CHANNEL 0

static const struct gpio_dt_spec heartbeat_led = GPIO_DT_SPEC_GET(DT_ALIAS(led0), gpios);
static const struct gpio_dt_spec conn_led = GPIO_DT_SPEC_GET(DT_ALIAS(led1), gpios);
static const struct gpio_dt_spec activity_led = GPIO_DT_SPEC_GET(DT_ALIAS(led2), gpios);
static const struct device *const gpio0 = DEVICE_DT_GET(DT_NODELABEL(gpio0));
static const struct adc_dt_spec vdd_adc =
	ADC_DT_SPEC_GET_BY_IDX(DT_PATH(zephyr_user), ADC_VDD_CHANNEL);

static atomic_t leds_ready;
static atomic_t adc_ready;
static atomic_t ble_connected;
static struct bt_conn *current_conn;
static struct k_work_delayable activity_led_off_work;
static int cached_vdd_err = -ENODEV;
static int32_t cached_vdd_mv;
static uint16_t cached_vdd_raw;

K_SEM_DEFINE(start_ble_sem, 0, 1);

static const struct bt_data ad[] = {
	BT_DATA_BYTES(BT_DATA_FLAGS, (BT_LE_AD_GENERAL | BT_LE_AD_NO_BREDR)),
	BT_DATA(BT_DATA_NAME_COMPLETE, DEVICE_NAME, DEVICE_NAME_LEN),
};

static const struct bt_data sd[] = {
	BT_DATA_BYTES(BT_DATA_UUID128_ALL, BT_UUID_NUS_VAL),
};

static void led_set(const struct gpio_dt_spec *led, int value)
{
	(void)gpio_pin_set_dt(led, value);
}

static void activity_led_off(struct k_work *work)
{
	ARG_UNUSED(work);

	led_set(&activity_led, 0);
}

static void activity_pulse(void)
{
	led_set(&activity_led, 1);
	(void)k_work_reschedule(&activity_led_off_work, K_MSEC(80));
}

static void error_blink_forever(uint8_t code)
{
	for (;;) {
		led_set(&heartbeat_led, 0);
		led_set(&conn_led, 0);
		led_set(&activity_led, 0);
		k_sleep(K_MSEC(900));

		for (uint8_t i = 0; i < code; i++) {
			led_set(&activity_led, 1);
			k_sleep(K_MSEC(150));
			led_set(&activity_led, 0);
			k_sleep(K_MSEC(180));
		}
	}
}

static int leds_init(void)
{
	const struct gpio_dt_spec *leds[] = {
		&heartbeat_led,
		&conn_led,
		&activity_led,
	};

	for (size_t i = 0; i < ARRAY_SIZE(leds); i++) {
		if (!gpio_is_ready_dt(leds[i])) {
			return -ENODEV;
		}

		int err = gpio_pin_configure_dt(leds[i], GPIO_OUTPUT_INACTIVE);
		if (err) {
			return err;
		}
	}

	for (int step = 0; step < 6; step++) {
		for (size_t i = 0; i < ARRAY_SIZE(leds); i++) {
			led_set(leds[i], step % 2 == 0);
		}
		k_sleep(K_MSEC(120));
	}

	k_work_init_delayable(&activity_led_off_work, activity_led_off);
	atomic_set(&leds_ready, 1);
	k_sem_give(&start_ble_sem);

	return 0;
}

static int switches_init(void)
{
	if (!device_is_ready(gpio0)) {
		return -ENODEV;
	}

	(void)gpio_pin_configure(gpio0, SW1_PIN, GPIO_INPUT | GPIO_PULL_UP);
	(void)gpio_pin_configure(gpio0, SW3_PIN, GPIO_INPUT | GPIO_PULL_UP);
	(void)gpio_pin_configure(gpio0, SW4_PIN, GPIO_INPUT | GPIO_PULL_UP);

	return 0;
}

static int switch_pressed(uint32_t pin)
{
	int value = gpio_pin_get(gpio0, pin);

	if (value < 0) {
		return -1;
	}

	return value == 0 ? 1 : 0;
}

static int adc_vdd_init(void)
{
	if (!adc_is_ready_dt(&vdd_adc)) {
		return -ENODEV;
	}

	return adc_channel_setup_dt(&vdd_adc);
}

static int zephyr_read_vdd_mv(int32_t *mv, uint16_t *raw)
{
	uint16_t sample = 0;
	struct adc_sequence sequence = {
		.buffer = &sample,
		.buffer_size = sizeof(sample),
	};
	int err;

	adc_sequence_init_dt(&vdd_adc, &sequence);
	err = adc_read_dt(&vdd_adc, &sequence);
	if (err) {
		return err;
	}

	*raw = sample;
	*mv = (int32_t)sample;
	err = adc_raw_to_millivolts_dt(&vdd_adc, mv);
	if (err) {
		return err;
	}

	return 0;
}

static void connected(struct bt_conn *conn, uint8_t err)
{
	if (err) {
		return;
	}

	current_conn = bt_conn_ref(conn);
	atomic_set(&ble_connected, 1);
	led_set(&conn_led, 1);
}

static void disconnected(struct bt_conn *conn, uint8_t reason)
{
	ARG_UNUSED(conn);
	ARG_UNUSED(reason);

	if (current_conn) {
		bt_conn_unref(current_conn);
		current_conn = NULL;
	}

	atomic_set(&ble_connected, 0);
	led_set(&conn_led, 0);
}

BT_CONN_CB_DEFINE(conn_callbacks) = {
	.connected = connected,
	.disconnected = disconnected,
};

static void nus_received(struct bt_conn *conn, const uint8_t *const data, uint16_t len)
{
	ARG_UNUSED(conn);

	if (len >= 4 && memcmp(data, "PING", 4) == 0 && current_conn) {
		static const char pong[] = "PONG\r\n";

		(void)bt_nus_send(current_conn, pong, sizeof(pong) - 1);
	}

	activity_pulse();
}

static struct bt_nus_cb nus_cb = {
	.received = nus_received,
};

int main(void)
{
	int err = leds_init();
	uint32_t seq = 0;

	if (err) {
		return err;
	}

	(void)switches_init();
	err = adc_vdd_init();
	if (err) {
		cached_vdd_err = err;
	} else {
		atomic_set(&adc_ready, 1);
		cached_vdd_err = zephyr_read_vdd_mv(&cached_vdd_mv, &cached_vdd_raw);
	}

	err = bt_enable(NULL);
	if (err) {
		error_blink_forever(2);
	}

	err = bt_nus_init(&nus_cb);
	if (err) {
		error_blink_forever(3);
	}

	err = bt_le_adv_start(BT_LE_ADV_CONN_FAST_2, ad, ARRAY_SIZE(ad), sd, ARRAY_SIZE(sd));
	if (err) {
		error_blink_forever(4);
	}

	for (;;) {
		led_set(&heartbeat_led, (++seq) % 2);

		if (atomic_get(&ble_connected) && current_conn) {
			char line[96];
			int len;

			if (cached_vdd_err) {
				len = snprintk(line, sizeof(line),
					       "VDDC,seq=%u,err=%d,sw1=%d,sw3=%d,sw4=%d\r\n",
					       seq, cached_vdd_err,
					       switch_pressed(SW1_PIN),
					       switch_pressed(SW3_PIN),
					       switch_pressed(SW4_PIN));
			} else {
				len = snprintk(line, sizeof(line),
					       "VDDC,seq=%u,raw=%d,mv=%ld,sw1=%d,sw3=%d,sw4=%d\r\n",
					       seq, cached_vdd_raw, (long)cached_vdd_mv,
					       switch_pressed(SW1_PIN),
					       switch_pressed(SW3_PIN),
					       switch_pressed(SW4_PIN));
			}

			if (len > 0) {
				(void)bt_nus_send(current_conn, line, len);
				activity_pulse();
			}
		}

		k_sleep(K_SECONDS(1));
	}
}
