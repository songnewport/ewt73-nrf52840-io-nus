/*
 * E73 nRF52840 BLE NUS + JSS official baseline firmware.
 * FW version: 0.2.6-OFFICIAL-BASELINE
 *
 * Aligned with Nordic official peripheral_uart sample (sdk-nrf).
 *
 * Design principles:
 *  - No custom notify pipeline; direct bt_gatt_notify at 1 Hz
 *  - No manual bond rejection in GATT write; let stack enforce WRITE_ENCRYPT
 *  - No fake bonded_count; use delayed bond refresh after pairing
 *  - Status characteristic notifies after every update
 *  - Advertising layout matches official: name in ad, UUID in sd
 */

#include <errno.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/conn.h>
#include <zephyr/bluetooth/gatt.h>
#include <zephyr/bluetooth/hci.h>
#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <zephyr/drivers/adc.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/drivers/i2c.h>
#include <zephyr/drivers/sensor.h>
#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>
#include <zephyr/settings/settings.h>
#include <zephyr/sys/atomic.h>

#include <bluetooth/services/nus.h>
#include <soc.h>

#include "button_control.h"
#include "jss_service.h"

LOG_MODULE_REGISTER(app, LOG_LEVEL_INF);

#define DEVICE_NAME CONFIG_BT_DEVICE_NAME
#define DEVICE_NAME_LEN (sizeof(DEVICE_NAME) - 1)
#define STATUS_REPORT_INTERVAL_SECONDS 3
#define PAIR_MODE_WINDOW_SECONDS 60
#define MAX_CONN CONFIG_BT_MAX_CONN

#define ADC_AIN1_CHANNEL 0
#define ADC_AIN4_CHANNEL 1

#define INA228_DEFAULT_RATED_MV 150
#define INA228_DEFAULT_RATED_MA 10000
#define INA228_DEFAULT_AVG_COUNT 16

#define INA228_REG_CONFIG 0x00
#define INA228_REG_ADC_CONFIG 0x01
#define INA228_REG_SHUNT_CAL 0x02
#define INA228_REG_VSHUNT 0x04
#define INA228_REG_VBUS 0x05
#define INA228_REG_DIETEMP 0x06
#define INA228_REG_CURRENT 0x07
#define INA228_REG_POWER 0x08
#define INA228_REG_MANUFACTURER_ID 0x3e
#define INA228_REG_DEVICE_ID 0x3f

static const struct gpio_dt_spec heartbeat_led = GPIO_DT_SPEC_GET(DT_ALIAS(led0), gpios);
static const struct gpio_dt_spec conn_led = GPIO_DT_SPEC_GET(DT_ALIAS(led1), gpios);
static const struct gpio_dt_spec activity_led = GPIO_DT_SPEC_GET(DT_ALIAS(led2), gpios);
static const struct device *const nrf_temp = DEVICE_DT_GET_ANY(nordic_nrf_temp);
static const struct i2c_dt_spec ina228_i2c = I2C_DT_SPEC_GET(DT_NODELABEL(ina228));
static const struct adc_dt_spec adc_channels[] = {
	ADC_DT_SPEC_GET_BY_IDX(DT_PATH(zephyr_user), ADC_AIN1_CHANNEL),
	ADC_DT_SPEC_GET_BY_IDX(DT_PATH(zephyr_user), ADC_AIN4_CHANNEL),
};

static atomic_t leds_ready;
static atomic_t adc_ready;
static atomic_t ina228_ready;
static atomic_t nrf_temp_ready;
static atomic_t ble_connected_count;
static atomic_t pair_mode_active;
static struct bt_conn *active_conns[MAX_CONN];
static struct k_work_delayable activity_led_off_work;
static struct k_work_delayable pair_mode_timeout_work;
static struct k_work_delayable pair_mode_blink_work;
static struct k_work_delayable bond_refresh_work;
static struct k_work adv_work;
static atomic_t pair_short_press_count;
static atomic_t pair_mode_request_count;
static atomic_t clear_bonds_request_count;
static atomic_t bonded_count;
static atomic_t app_led_on;
static int cached_adc_init_err = -ENODEV;
static int cached_button_init_err = -ENODEV;
static int cached_ina228_init_err = -ENODEV;
static int cached_nrf_temp_init_err = -ENODEV;
static int64_t ina228_current_lsb_na;
static uint16_t ina228_manufacturer_id;
static uint16_t ina228_device_id;

K_SEM_DEFINE(start_ble_sem, 0, 1);

/* Official layout: name in ad (visible during passive scan), UUID in sd. */
static const struct bt_data ad[] = {
	BT_DATA_BYTES(BT_DATA_FLAGS, (BT_LE_AD_GENERAL | BT_LE_AD_NO_BREDR)),
	BT_DATA(BT_DATA_NAME_COMPLETE, DEVICE_NAME, DEVICE_NAME_LEN),
};

static const struct bt_data sd[] = {
	BT_DATA_BYTES(BT_DATA_UUID128_ALL, JSS_SERVICE_UUID_VAL),
};

static void pair_mode_timeout_handler(struct k_work *work);
static void pair_mode_blink_handler(struct k_work *work);
static void bond_refresh_work_handler(struct k_work *work);
static void advertising_start(void);

/* ---------- LED helpers ---------- */

static void led_set(const struct gpio_dt_spec *led, int value)
{
	(void)gpio_pin_set_dt(led, value);
}

static void app_led_set(bool on)
{
	atomic_set(&app_led_on, on ? 1 : 0);
	led_set(&activity_led, on ? 1 : 0);
}

static void activity_led_off(struct k_work *work)
{
	ARG_UNUSED(work);

	if (!atomic_get(&app_led_on)) {
		led_set(&activity_led, 0);
	}
}

static void activity_pulse(void)
{
	if (atomic_get(&app_led_on)) {
		return;
	}

	led_set(&activity_led, 1);
	(void)k_work_reschedule(&activity_led_off_work, K_MSEC(80));
}

/* ---------- NUS debug output ---------- */

static void nus_send_text(const char *text)
{
	struct bt_conn *conn = NULL;

	if (!atomic_get(&ble_connected_count)) {
		return;
	}

	for (size_t i = 0; i < ARRAY_SIZE(active_conns); i++) {
		if (active_conns[i]) {
			conn = active_conns[i];
			break;
		}
	}

	if (!conn) {
		return;
	}

	(void)bt_nus_send(conn, text, strlen(text));
	activity_pulse();
}

/* ---------- Error handler ---------- */

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

/* ---------- Peripheral init ---------- */

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
	k_work_init_delayable(&pair_mode_timeout_work, pair_mode_timeout_handler);
	k_work_init_delayable(&pair_mode_blink_work, pair_mode_blink_handler);
	k_work_init_delayable(&bond_refresh_work, bond_refresh_work_handler);
	atomic_set(&leds_ready, 1);
	k_sem_give(&start_ble_sem);

	return 0;
}

static int adc_inputs_init(void)
{
	for (size_t i = 0; i < ARRAY_SIZE(adc_channels); i++) {
		if (!adc_is_ready_dt(&adc_channels[i])) {
			return -ENODEV;
		}

		int err = adc_channel_setup_dt(&adc_channels[i]);
		if (err) {
			return err;
		}
	}

	return 0;
}

static int zephyr_read_adc_mv(size_t channel_index, int32_t *mv, uint16_t *raw)
{
	uint16_t sample = 0;
	struct adc_sequence sequence = {
		.buffer = &sample,
		.buffer_size = sizeof(sample),
	};
	int err;

	if (channel_index >= ARRAY_SIZE(adc_channels)) {
		return -EINVAL;
	}

	adc_sequence_init_dt(&adc_channels[channel_index], &sequence);
	err = adc_read_dt(&adc_channels[channel_index], &sequence);
	if (err) {
		return err;
	}

	*raw = sample;
	*mv = (int32_t)sample;
	err = adc_raw_to_millivolts_dt(&adc_channels[channel_index], mv);
	if (err) {
		return err;
	}

	return 0;
}

static int nrf_temp_init(void)
{
	if (nrf_temp == NULL || !device_is_ready(nrf_temp)) {
		return -ENODEV;
	}

	return 0;
}

static int nrf_temp_read_x10(int32_t *temp_x10)
{
	struct sensor_value value;
	int err;

	if (!atomic_get(&nrf_temp_ready)) {
		return cached_nrf_temp_init_err;
	}

	err = sensor_sample_fetch(nrf_temp);
	if (err) {
		return err;
	}

	err = sensor_channel_get(nrf_temp, SENSOR_CHAN_DIE_TEMP, &value);
	if (err) {
		return err;
	}

	*temp_x10 = value.val1 * 10 + value.val2 / 100000;
	return 0;
}

/* ---------- INA228 driver ---------- */

struct ina228_sample {
	int32_t bus_mv;
	int32_t shunt_uv;
	int32_t current_ma;
	int32_t power_mw;
	int32_t temp_x10;
};

static int32_t sign_extend_u32(uint32_t value, uint8_t bits)
{
	uint32_t sign_bit = BIT(bits - 1);

	return (int32_t)((value ^ sign_bit) - sign_bit);
}

static int ina228_read_reg(uint8_t reg, uint8_t *buf, size_t len)
{
	return i2c_write_read_dt(&ina228_i2c, &reg, sizeof(reg), buf, len);
}

static int ina228_read16(uint8_t reg, uint16_t *value)
{
	uint8_t buf[2];
	int err = ina228_read_reg(reg, buf, sizeof(buf));

	if (err) {
		return err;
	}

	*value = ((uint16_t)buf[0] << 8) | buf[1];
	return 0;
}

static int ina228_read24(uint8_t reg, uint32_t *value)
{
	uint8_t buf[3];
	int err = ina228_read_reg(reg, buf, sizeof(buf));

	if (err) {
		return err;
	}

	*value = ((uint32_t)buf[0] << 16) | ((uint32_t)buf[1] << 8) | buf[2];
	return 0;
}

static int ina228_write16(uint8_t reg, uint16_t value)
{
	uint8_t buf[] = {
		reg,
		(uint8_t)(value >> 8),
		(uint8_t)value,
	};

	return i2c_write_dt(&ina228_i2c, buf, sizeof(buf));
}

static int ina228_avg_bits(uint16_t avg_count, uint16_t *bits)
{
	switch (avg_count) {
	case 1:
		*bits = 0;
		return 0;
	case 4:
		*bits = 1;
		return 0;
	case 16:
		*bits = 2;
		return 0;
	case 64:
		*bits = 3;
		return 0;
	case 128:
		*bits = 4;
		return 0;
	case 256:
		*bits = 5;
		return 0;
	case 512:
		*bits = 6;
		return 0;
	case 1024:
		*bits = 7;
		return 0;
	default:
		LOG_WRN("Unsupported INA228 avg_count %u", avg_count);
		return -EINVAL;
	}
}

static int ina228_inputs_init(uint16_t rated_mv, uint32_t rated_ma, uint16_t avg_count)
{
	uint16_t avg;
	uint16_t shunt_cal;
	uint16_t adc_config;
	int err;

	if (!i2c_is_ready_dt(&ina228_i2c)) {
		return -ENODEV;
	}

	if (rated_mv < 10 || rated_mv > 163 || rated_ma < 1000 || rated_ma > 1000000) {
		return -EINVAL;
	}

	err = ina228_avg_bits(avg_count, &avg);
	if (err) {
		return err;
	}

	err = ina228_read16(INA228_REG_MANUFACTURER_ID, &ina228_manufacturer_id);
	if (err) {
		return err;
	}

	err = ina228_read16(INA228_REG_DEVICE_ID, &ina228_device_id);
	if (err) {
		return err;
	}

	if (ina228_manufacturer_id != 0x5449 || ((ina228_device_id >> 4) & 0x0fff) != 0x228) {
		return -ENODEV;
	}

	ina228_current_lsb_na = DIV_ROUND_UP((int64_t)rated_ma * 1000000LL, 524288LL);
	shunt_cal = rated_mv * 25U;
	adc_config = (0xfb68U & ~0x0007U) | avg;

	err = ina228_write16(INA228_REG_CONFIG, 0x0000);
	if (err) {
		return err;
	}

	err = ina228_write16(INA228_REG_SHUNT_CAL, shunt_cal);
	if (err) {
		return err;
	}

	err = ina228_write16(INA228_REG_ADC_CONFIG, adc_config);
	if (err) {
		return err;
	}

	k_sleep(K_MSEC(avg_count * 4 + 50));

	return 0;
}

static int ina228_read_all(struct ina228_sample *sample)
{
	uint16_t raw16;
	uint32_t raw24;
	int32_t signed20;
	int err;

	err = ina228_read24(INA228_REG_VBUS, &raw24);
	if (err) {
		return err;
	}
	sample->bus_mv = (int32_t)(((uint64_t)(raw24 >> 4) * 195313ULL) / 1000000ULL);

	err = ina228_read24(INA228_REG_VSHUNT, &raw24);
	if (err) {
		return err;
	}
	signed20 = sign_extend_u32(raw24 >> 4, 20);
	sample->shunt_uv = (int32_t)(((int64_t)signed20 * 3125LL) / 10000LL);

	err = ina228_read24(INA228_REG_CURRENT, &raw24);
	if (err) {
		return err;
	}
	signed20 = sign_extend_u32(raw24 >> 4, 20);
	sample->current_ma = (int32_t)(((int64_t)signed20 * ina228_current_lsb_na) / 1000000LL);

	err = ina228_read24(INA228_REG_POWER, &raw24);
	if (err) {
		return err;
	}
	sample->power_mw = (int32_t)(((uint64_t)raw24 * (uint64_t)ina228_current_lsb_na * 32ULL) /
				     10000000ULL);
	if (sample->current_ma < 0) {
		sample->power_mw = -sample->power_mw;
	}

	err = ina228_read16(INA228_REG_DIETEMP, &raw16);
	if (err) {
		return err;
	}
	sample->temp_x10 = (int32_t)(((int64_t)(int16_t)raw16 * 78125LL) / 1000000LL);

	return 0;
}

/* ---------- Bond management ---------- */

static void count_bond(const struct bt_bond_info *info, void *user_data)
{
	uint32_t *count = user_data;

	ARG_UNUSED(info);

	(*count)++;
}

static uint32_t refresh_bonded_count(void)
{
	uint32_t count = 0;

	bt_foreach_bond(BT_ID_DEFAULT, count_bond, &count);
	atomic_set(&bonded_count, count);

	return count;
}

static void update_jss_status(void);

static void bond_refresh_work_handler(struct k_work *work)
{
	ARG_UNUSED(work);

	refresh_bonded_count();
	update_jss_status();
	LOG_INF("Delayed bond refresh: bonded_count=%ld",
		(long)atomic_get(&bonded_count));
}

/* ---------- Connection helpers ---------- */

static uint16_t active_uatt_mtu(void)
{
	for (size_t i = 0; i < ARRAY_SIZE(active_conns); i++) {
		if (active_conns[i]) {
			return bt_gatt_get_uatt_mtu(active_conns[i]);
		}
	}

	return 0;
}

static bt_security_t active_security_level(void)
{
	for (size_t i = 0; i < ARRAY_SIZE(active_conns); i++) {
		if (active_conns[i]) {
			return bt_conn_get_security(active_conns[i]);
		}
	}

	return BT_SECURITY_L0;
}

static bool active_link_is_bonded(void)
{
	/* Debug/status helper only. Do not use this to authorize writes;
	 * BT_GATT_PERM_WRITE_ENCRYPT is the official baseline enforcement. */
	for (size_t i = 0; i < ARRAY_SIZE(active_conns); i++) {
		if (active_conns[i] &&
		    bt_conn_get_security(active_conns[i]) >= BT_SECURITY_L2 &&
		    atomic_get(&bonded_count) > 0) {
			return true;
		}
	}

	return false;
}

static struct bt_conn *first_live_subscribed_conn(void)
{
	for (size_t i = 0; i < ARRAY_SIZE(active_conns); i++) {
		if (active_conns[i] &&
		    jss_service_live_is_subscribed(active_conns[i])) {
			return active_conns[i];
		}
	}

	return NULL;
}

static bool any_live_subscribed_conn(void)
{
	return first_live_subscribed_conn() != NULL;
}

static bool any_bond_exists(void)
{
	return refresh_bonded_count() > 0;
}

static void remove_conn(struct bt_conn *conn)
{
	for (size_t i = 0; i < ARRAY_SIZE(active_conns); i++) {
		if (active_conns[i] == conn) {
			bt_conn_unref(active_conns[i]);
			active_conns[i] = NULL;
			atomic_dec(&ble_connected_count);
			return;
		}
	}
}

static int store_conn(struct bt_conn *conn)
{
	for (size_t i = 0; i < ARRAY_SIZE(active_conns); i++) {
		if (!active_conns[i]) {
			active_conns[i] = bt_conn_ref(conn);
			atomic_inc(&ble_connected_count);
			return 0;
		}
	}

	return -ENOMEM;
}

/* ---------- Status reporting ---------- */

static void update_jss_status(void)
{
	char status[256];

	(void)snprintk(status, sizeof(status),
		       "PAIR_MODE=%d,BONDED_COUNT=%ld,LED=%ld,FW=0.2.6-OFFICIAL-BASELINE,"
		       "MTU=%u,SEC_LEVEL=%u,IS_BONDED=%d,LIVE_CCC=%d,LIVE_SUB=%d,"
		       "STATUS_CCC=%d,LIVE_NTF=%lu/%lu,LIVE_ERR=%d,LIVE_SKIP=%lu,"
		       "LAST_WRITE_ERR=%d",
		       atomic_get(&pair_mode_active) ? 1 : 0,
		       (long)atomic_get(&bonded_count),
		       (long)atomic_get(&app_led_on),
		       active_uatt_mtu(),
		       active_security_level(),
		       active_link_is_bonded() ? 1 : 0,
		       jss_service_live_notify_enabled() ? 1 : 0,
		       any_live_subscribed_conn() ? 1 : 0,
		       jss_service_status_notify_enabled() ? 1 : 0,
		       (unsigned long)jss_service_live_notify_successes(),
		       (unsigned long)jss_service_live_notify_attempts(),
		       jss_service_live_notify_last_err(),
		       (unsigned long)jss_service_live_notify_skips(),
		       jss_service_led_write_last_err());
	jss_service_set_status(status);
	/* Notify status to subscribed peers immediately. */
	jss_service_notify_status();
}

/* ---------- Pair mode ---------- */

static void pair_mode_blink_handler(struct k_work *work)
{
	static bool on;

	ARG_UNUSED(work);

	if (!atomic_get(&pair_mode_active)) {
		led_set(&heartbeat_led, 0);
		return;
	}

	on = !on;
	led_set(&heartbeat_led, on ? 1 : 0);
	(void)k_work_reschedule(&pair_mode_blink_work, K_MSEC(150));
}

static void pair_mode_timeout_handler(struct k_work *work)
{
	ARG_UNUSED(work);

	bt_set_bondable(false);
	atomic_set(&pair_mode_active, 0);
	update_jss_status();
	nus_send_text("SECURITY,PAIR_MODE_TIMEOUT\r\n");
}

static void enter_pair_mode(void)
{
	int sec_err;

	bt_set_bondable(true);
	atomic_set(&pair_mode_active, 1);
	(void)k_work_reschedule(&pair_mode_timeout_work, K_SECONDS(PAIR_MODE_WINDOW_SECONDS));
	(void)k_work_reschedule(&pair_mode_blink_work, K_NO_WAIT);
	update_jss_status();
	nus_send_text("SECURITY,PAIR_MODE_ON,timeout=60\r\n");

	for (size_t i = 0; i < ARRAY_SIZE(active_conns); i++) {
		if (!active_conns[i]) {
			continue;
		}

		sec_err = bt_conn_set_security(active_conns[i], BT_SECURITY_L2);
		if (sec_err) {
			LOG_WRN("Pair-mode security request failed: %d", sec_err);
		}
	}
}

static void clear_all_bonds(void)
{
	int err;

	bt_set_bondable(false);
	atomic_set(&pair_mode_active, 0);
	(void)k_work_cancel_delayable(&pair_mode_timeout_work);
	(void)k_work_cancel_delayable(&pair_mode_blink_work);

	err = bt_unpair(BT_ID_DEFAULT, BT_ADDR_LE_ANY);
	refresh_bonded_count();
	update_jss_status();

	if (err) {
		nus_send_text("SECURITY,CLEAR_BONDS_FAILED\r\n");
	} else {
		nus_send_text("SECURITY,CLEAR_BONDS_OK\r\n");
	}
}

/* ---------- Advertising ---------- */

static void adv_work_handler(struct k_work *work)
{
	int err;

	ARG_UNUSED(work);

	err = bt_le_adv_start(BT_LE_ADV_CONN_FAST_2, ad, ARRAY_SIZE(ad), sd, ARRAY_SIZE(sd));
	if (err == -EALREADY) {
		LOG_DBG("Advertising already active");
		return;
	}

	if (err) {
		LOG_ERR("Advertising failed to start (err %d)", err);
		return;
	}

	LOG_INF("Advertising successfully started");
}

static void advertising_start(void)
{
	k_work_submit(&adv_work);
}

/* ---------- BLE connection callbacks ---------- */

static void connected(struct bt_conn *conn, uint8_t err)
{
	char addr[BT_ADDR_LE_STR_LEN];
	int sec_err;

	if (err) {
		LOG_ERR("Connection failed, err 0x%02x %s", err, bt_hci_err_to_str(err));
		return;
	}

	bt_addr_le_to_str(bt_conn_get_dst(conn), addr, sizeof(addr));

	if (store_conn(conn)) {
		LOG_WRN("Connection pool full, disconnecting %s", addr);
		bt_conn_disconnect(conn, BT_HCI_ERR_CONN_LIMIT_EXCEEDED);
		return;
	}

	led_set(&conn_led, 1);
	LOG_INF("Connected: %s", addr);

	if (any_bond_exists() || atomic_get(&pair_mode_active)) {
		sec_err = bt_conn_set_security(conn, BT_SECURITY_L2);
		if (sec_err) {
			LOG_WRN("Security request failed: %d", sec_err);
		} else {
			LOG_INF("Security requested");
		}
	}
}

static void disconnected(struct bt_conn *conn, uint8_t reason)
{
	char addr[BT_ADDR_LE_STR_LEN];

	bt_addr_le_to_str(bt_conn_get_dst(conn), addr, sizeof(addr));
	remove_conn(conn);
	LOG_INF("Disconnected: %s, reason 0x%02x %s", addr, reason,
		bt_hci_err_to_str(reason));

	if (!atomic_get(&ble_connected_count)) {
		led_set(&conn_led, 0);
	}
}

static void recycled_cb(void)
{
	LOG_INF("Connection object recycled");
	advertising_start();
}

static void security_changed(struct bt_conn *conn, bt_security_t level,
			     enum bt_security_err err)
{
	char addr[BT_ADDR_LE_STR_LEN];

	bt_addr_le_to_str(bt_conn_get_dst(conn), addr, sizeof(addr));

	if (!err) {
		LOG_INF("Security changed: %s level %u", addr, level);
	} else {
		LOG_WRN("Security failed: %s level %u err %d %s", addr, level,
			err, bt_security_err_to_str(err));
	}
}

static void pairing_complete(struct bt_conn *conn, bool bonded)
{
	char addr[BT_ADDR_LE_STR_LEN];

	bt_addr_le_to_str(bt_conn_get_dst(conn), addr, sizeof(addr));
	LOG_INF("Pairing completed: %s, bonded: %d", addr, bonded);

	if (bonded) {
		bt_set_bondable(false);
		atomic_set(&pair_mode_active, 0);
		(void)k_work_cancel_delayable(&pair_mode_timeout_work);
		(void)k_work_cancel_delayable(&pair_mode_blink_work);
		/*
		 * Do not fake bonded_count here. The Zephyr settings subsystem
		 * may not have persisted the bond yet, so bt_foreach_bond()
		 * can return 0 immediately after pairing. Use a delayed work
		 * item to refresh the count after settings have had time to
		 * flush.
		 */
		(void)k_work_reschedule(&bond_refresh_work, K_MSEC(500));
		nus_send_text("SECURITY,PAIRING_COMPLETE,bonded=1\r\n");
	}
}

static void pairing_failed(struct bt_conn *conn, enum bt_security_err reason)
{
	char addr[BT_ADDR_LE_STR_LEN];

	bt_addr_le_to_str(bt_conn_get_dst(conn), addr, sizeof(addr));
	LOG_INF("Pairing failed: %s, reason %d %s", addr, reason,
		bt_security_err_to_str(reason));

	nus_send_text("SECURITY,PAIRING_FAILED\r\n");
}

BT_CONN_CB_DEFINE(conn_callbacks) = {
	.connected = connected,
	.disconnected = disconnected,
	.recycled = recycled_cb,
	.security_changed = security_changed,
};

static struct bt_conn_auth_info_cb auth_info_cb = {
	.pairing_complete = pairing_complete,
	.pairing_failed = pairing_failed,
};

/* ---------- NUS receive ---------- */

static void nus_received(struct bt_conn *conn, const uint8_t *const data, uint16_t len)
{
	if (len >= 4 && memcmp(data, "PING", 4) == 0 && conn) {
		static const char pong[] = "PONG\r\n";

		(void)bt_nus_send(conn, pong, sizeof(pong) - 1);
	}

	activity_pulse();
}

static struct bt_nus_cb nus_cb = {
	.received = nus_received,
};

/* ---------- Button events ---------- */

static void button_event_handler(enum button_control_event event, void *user_data)
{
	char line[64];

	ARG_UNUSED(user_data);

	switch (event) {
	case BUTTON_CONTROL_EVENT_PAIR_SHORT_PRESS:
		atomic_inc(&pair_short_press_count);
		snprintk(line, sizeof(line), "BUTTON,PAIR_SHORT,count=%ld\r\n",
			 (long)atomic_get(&pair_short_press_count));
		nus_send_text(line);
		break;
	case BUTTON_CONTROL_EVENT_ENTER_PAIR_MODE:
		atomic_inc(&pair_mode_request_count);
		snprintk(line, sizeof(line), "BUTTON,ENTER_PAIR_MODE,count=%ld\r\n",
			 (long)atomic_get(&pair_mode_request_count));
		nus_send_text(line);
		enter_pair_mode();
		break;
	case BUTTON_CONTROL_EVENT_CLEAR_BONDS_REQUESTED:
		atomic_inc(&clear_bonds_request_count);
		snprintk(line, sizeof(line), "BUTTON,CLEAR_BONDS_REQUESTED,count=%ld\r\n",
			 (long)atomic_get(&clear_bonds_request_count));
		nus_send_text(line);
		clear_all_bonds();
		break;
	default:
		break;
	}
}

/* ---------- main ---------- */

int main(void)
{
	static const struct jss_service_handlers jss_handlers = {
		.led_write = app_led_set,
	};
	int err = leds_init();
	uint32_t seq = 0;

	if (err) {
		return err;
	}

	cached_button_init_err = button_control_init(button_event_handler, NULL);
	jss_service_init(&jss_handlers);
	cached_adc_init_err = adc_inputs_init();
	if (!cached_adc_init_err) {
		atomic_set(&adc_ready, 1);
	}

	cached_ina228_init_err = ina228_inputs_init(INA228_DEFAULT_RATED_MV,
						    INA228_DEFAULT_RATED_MA,
						    INA228_DEFAULT_AVG_COUNT);
	if (!cached_ina228_init_err) {
		atomic_set(&ina228_ready, 1);
	}

	cached_nrf_temp_init_err = nrf_temp_init();
	if (!cached_nrf_temp_init_err) {
		atomic_set(&nrf_temp_ready, 1);
	}

	/* adv_work must be initialised before bt_enable: recycled_cb may fire immediately. */
	k_work_init(&adv_work, adv_work_handler);

	err = bt_conn_auth_info_cb_register(&auth_info_cb);
	if (err) {
		error_blink_forever(5);
	}

	err = bt_enable(NULL);
	if (err) {
		error_blink_forever(2);
	}

	LOG_INF("Bluetooth initialized");

	if (IS_ENABLED(CONFIG_SETTINGS)) {
		(void)settings_load();
	}
	refresh_bonded_count();
	bt_set_bondable(false);
	update_jss_status();

	err = bt_nus_init(&nus_cb);
	if (err) {
		error_blink_forever(3);
	}

	advertising_start();

	for (;;) {
		if (!atomic_get(&pair_mode_active)) {
			led_set(&heartbeat_led, (++seq) % 2);
		} else {
			seq++;
		}

		if (atomic_get(&ble_connected_count)) {
			bool send_debug = (seq % STATUS_REPORT_INTERVAL_SECONDS) == 0;
			uint16_t ain1_raw = 0;
			uint16_t ain4_raw = 0;
			int32_t ain1_mv = 0;
			int32_t ain4_mv = 0;
			int32_t live_vbus_mv = 0;
			int32_t live_current_ma = 0;
			int32_t live_temp_x10 = 0;
			int ain1_err = cached_adc_init_err;
			int ain4_err = cached_adc_init_err;
			int32_t nrf_temp_x10 = 0;
			int nrf_temp_err;
			int pair_pressed = button_control_pair_pressed();
			int sw3_pressed = button_control_sw3_pressed();
			int sw4_pressed = button_control_sw4_pressed();
			char line[192];
			int len;

			if (!cached_adc_init_err) {
				ain1_err = zephyr_read_adc_mv(ADC_AIN1_CHANNEL, &ain1_mv, &ain1_raw);
				ain4_err = zephyr_read_adc_mv(ADC_AIN4_CHANNEL, &ain4_mv, &ain4_raw);
			}
			nrf_temp_err = nrf_temp_read_x10(&nrf_temp_x10);
			live_vbus_mv = ain1_mv;
			live_temp_x10 = nrf_temp_err ? 0 : nrf_temp_x10;

			if (ain1_err || ain4_err) {
				len = snprintk(line, sizeof(line),
					       "ADC,seq=%u,ain1_err=%d,ain4_err=%d,btn_init=%d,sw1=%d,sw3=%d,sw4=%d,pair=%ld/%ld,clear=%ld\r\n",
					       seq, ain1_err, ain4_err,
					       cached_button_init_err,
					       pair_pressed, sw3_pressed, sw4_pressed,
					       (long)atomic_get(&pair_short_press_count),
					       (long)atomic_get(&pair_mode_request_count),
					       (long)atomic_get(&clear_bonds_request_count));
			} else {
				len = snprintk(line, sizeof(line),
					       "ADC,seq=%u,ain1=%u/%ld,ain4=%u/%ld,btn_init=%d,sw1=%d,sw3=%d,sw4=%d,pair=%ld/%ld,clear=%ld\r\n",
					       seq, ain1_raw, (long)ain1_mv, ain4_raw, (long)ain4_mv,
					       cached_button_init_err,
					       pair_pressed, sw3_pressed, sw4_pressed,
					       (long)atomic_get(&pair_short_press_count),
					       (long)atomic_get(&pair_mode_request_count),
					       (long)atomic_get(&clear_bonds_request_count));
			}

			if (send_debug && len > 0) {
				nus_send_text(line);
			}

			if (atomic_get(&ina228_ready)) {
				struct ina228_sample sample;
				int ina_err = ina228_read_all(&sample);

				if (ina_err) {
					len = snprintk(line, sizeof(line),
						       "INA,seq=%u,init=%d,read=%d,id=%04x/%04x,nrf_temp_err=%d\r\n",
						       seq, cached_ina228_init_err, ina_err,
						       ina228_manufacturer_id, ina228_device_id,
						       nrf_temp_err);
				} else {
					if (nrf_temp_err) {
						len = snprintk(line, sizeof(line),
							       "INA,seq=%u,vbus_mv=%ld,shunt_uv=%ld,current_ma=%ld,power_mw=%ld,temp_x10=%ld,nrf_temp_err=%d\r\n",
							       seq,
							       (long)sample.bus_mv,
							       (long)sample.shunt_uv,
							       (long)sample.current_ma,
							       (long)sample.power_mw,
							       (long)sample.temp_x10,
							       nrf_temp_err);
					} else {
						len = snprintk(line, sizeof(line),
							       "INA,seq=%u,vbus_mv=%ld,shunt_uv=%ld,current_ma=%ld,power_mw=%ld,temp_x10=%ld,nrf_temp_x10=%ld\r\n",
							       seq,
							       (long)sample.bus_mv,
							       (long)sample.shunt_uv,
							       (long)sample.current_ma,
							       (long)sample.power_mw,
							       (long)sample.temp_x10,
							       (long)nrf_temp_x10);
					}
					live_vbus_mv = sample.bus_mv;
					live_current_ma = sample.current_ma;
					live_temp_x10 = sample.temp_x10;
				}
			} else {
				if (nrf_temp_err) {
					len = snprintk(line, sizeof(line),
						       "INA,seq=%u,init=%d,id=%04x/%04x,nrf_temp_err=%d\r\n",
						       seq, cached_ina228_init_err,
						       ina228_manufacturer_id, ina228_device_id,
						       nrf_temp_err);
				} else {
					len = snprintk(line, sizeof(line),
						       "INA,seq=%u,init=%d,id=%04x/%04x,nrf_temp_x10=%ld\r\n",
						       seq, cached_ina228_init_err,
						       ina228_manufacturer_id, ina228_device_id,
						       (long)nrf_temp_x10);
				}
			}

			if (send_debug && len > 0) {
				nus_send_text(line);
			}

			/* Official baseline: direct notify, no pipeline. */
			(void)snprintk(line, sizeof(line), "V=%ld.%03ld,I=%ld,T=%ld.%ld,SOC=88",
				       (long)(live_vbus_mv / 1000),
				       (long)(live_vbus_mv % 1000),
				       (long)live_current_ma,
				       (long)(live_temp_x10 / 10),
				       (long)(live_temp_x10 % 10));
			jss_service_set_live_data(line);

			{
				struct bt_conn *live_conn = first_live_subscribed_conn();

				if (live_conn) {
					(void)jss_service_notify_live_data(live_conn);
				}
			}

			update_jss_status();
		}

		k_sleep(K_SECONDS(1));
	}
}
