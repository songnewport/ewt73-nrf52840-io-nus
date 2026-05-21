/*
 * Justin Smart Shunt Test GATT Service.
 * Official baseline: direct bt_gatt_notify(), no pipeline.
 *
 * v0.3.0: Added UART bridge characteristics (def5/def6).
 *   def5 = UART Command (App→STM32): write-without-response
 *   def6 = UART Response (STM32→App): notify
 *
 * LED characteristic uses BT_GATT_PERM_WRITE_ENCRYPT.
 * Encryption is enforced by the Zephyr stack; no manual bond check here.
 */

#include "jss_service.h"

#include <errno.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include <zephyr/bluetooth/gatt.h>
#include <zephyr/bluetooth/uuid.h>
#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>

LOG_MODULE_REGISTER(jss_service, LOG_LEVEL_INF);

#define JSS_TEXT_MAX_LEN 256

static const struct bt_uuid_128 jss_service_uuid =
	BT_UUID_INIT_128(JSS_SERVICE_UUID_VAL);
static const struct bt_uuid_128 jss_live_data_uuid =
	BT_UUID_INIT_128(BT_UUID_128_ENCODE(0x12345678, 0x1234, 0x5678, 0x1234,
					    0x56789abcdef1));
static const struct bt_uuid_128 jss_led_control_uuid =
	BT_UUID_INIT_128(BT_UUID_128_ENCODE(0x12345678, 0x1234, 0x5678, 0x1234,
					    0x56789abcdef2));
static const struct bt_uuid_128 jss_device_status_uuid =
	BT_UUID_INIT_128(BT_UUID_128_ENCODE(0x12345678, 0x1234, 0x5678, 0x1234,
					    0x56789abcdef3));
static const struct bt_uuid_128 jss_secure_info_uuid =
	BT_UUID_INIT_128(BT_UUID_128_ENCODE(0x12345678, 0x1234, 0x5678, 0x1234,
					    0x56789abcdef4));
static const struct bt_uuid_128 jss_uart_cmd_uuid =
	BT_UUID_INIT_128(BT_UUID_128_ENCODE(0x12345678, 0x1234, 0x5678, 0x1234,
					    0x56789abcdef5));
static const struct bt_uuid_128 jss_uart_rsp_uuid =
	BT_UUID_INIT_128(BT_UUID_128_ENCODE(0x12345678, 0x1234, 0x5678, 0x1234,
					    0x56789abcdef6));

static struct jss_service_handlers service_handlers;

/* Mutex protection for shared text buffers written from main thread,
 * read from BLE system workqueue thread (GATT read / notify). */
static K_MUTEX_DEFINE(live_data_mutex);
static K_MUTEX_DEFINE(status_mutex);
static K_MUTEX_DEFINE(secure_info_mutex);
static K_MUTEX_DEFINE(uart_rsp_mutex);

static char live_data[JSS_TEXT_MAX_LEN] = "V=0.000,I=0,T=0,SOC=0";
static char device_status[JSS_TEXT_MAX_LEN] = "PAIR_MODE=0,BONDED_COUNT=0,LED=0,FW=" JSS_FW_VERSION ",IS_BONDED=0";
static char secure_info[JSS_TEXT_MAX_LEN] = "SERIAL=" JSS_DEVICE_SERIAL ",FW=" JSS_FW_VERSION ",PROVISIONED=0";
static char uart_rsp[JSS_TEXT_MAX_LEN];
static bool led_on;
static bool live_notify_enabled;
static bool status_notify_enabled;
static bool uart_rsp_notify_enabled;
static const struct bt_gatt_attr *live_data_attr;
static const struct bt_gatt_attr *device_status_attr;
static const struct bt_gatt_attr *uart_rsp_attr;
static uint32_t live_notify_attempts;
static uint32_t live_notify_successes;
static uint32_t live_notify_skips;
static int live_notify_last_err;
static int led_write_last_err;

/* ---------- GATT read callbacks ---------- */

static ssize_t read_live_data(struct bt_conn *conn, const struct bt_gatt_attr *attr,
			      void *buf, uint16_t len, uint16_t offset)
{
	ssize_t ret;

	k_mutex_lock(&live_data_mutex, K_FOREVER);
	ret = bt_gatt_attr_read(conn, attr, buf, len, offset,
				live_data, strlen(live_data));
	k_mutex_unlock(&live_data_mutex);

	return ret;
}

static ssize_t read_device_status(struct bt_conn *conn, const struct bt_gatt_attr *attr,
				  void *buf, uint16_t len, uint16_t offset)
{
	ssize_t ret;

	k_mutex_lock(&status_mutex, K_FOREVER);
	ret = bt_gatt_attr_read(conn, attr, buf, len, offset,
				device_status, strlen(device_status));
	k_mutex_unlock(&status_mutex);

	return ret;
}

static ssize_t read_secure_info(struct bt_conn *conn, const struct bt_gatt_attr *attr,
				void *buf, uint16_t len, uint16_t offset)
{
	ssize_t ret;

	k_mutex_lock(&secure_info_mutex, K_FOREVER);
	ret = bt_gatt_attr_read(conn, attr, buf, len, offset,
				secure_info, strlen(secure_info));
	k_mutex_unlock(&secure_info_mutex);

	return ret;
}

static ssize_t read_uart_rsp(struct bt_conn *conn, const struct bt_gatt_attr *attr,
			     void *buf, uint16_t len, uint16_t offset)
{
	ssize_t ret;

	k_mutex_lock(&uart_rsp_mutex, K_FOREVER);
	ret = bt_gatt_attr_read(conn, attr, buf, len, offset,
				uart_rsp, strlen(uart_rsp));
	k_mutex_unlock(&uart_rsp_mutex);

	return ret;
}

/* ---------- LED write callback ---------- */

static ssize_t write_led_control(struct bt_conn *conn, const struct bt_gatt_attr *attr,
				 const void *buf, uint16_t len, uint16_t offset,
				 uint8_t flags)
{
	const uint8_t *value = buf;

	ARG_UNUSED(conn);
	ARG_UNUSED(attr);
	ARG_UNUSED(flags);

	if (offset != 0) {
		led_write_last_err = BT_ATT_ERR_INVALID_OFFSET;
		return BT_GATT_ERR(BT_ATT_ERR_INVALID_OFFSET);
	}

	if (len != 1) {
		led_write_last_err = BT_ATT_ERR_INVALID_ATTRIBUTE_LEN;
		return BT_GATT_ERR(BT_ATT_ERR_INVALID_ATTRIBUTE_LEN);
	}

	if (value[0] != 0x00 && value[0] != 0x01) {
		led_write_last_err = BT_ATT_ERR_VALUE_NOT_ALLOWED;
		return BT_GATT_ERR(BT_ATT_ERR_VALUE_NOT_ALLOWED);
	}

	led_on = value[0] == 0x01;
	if (service_handlers.led_write) {
		service_handlers.led_write(led_on);
	}

	LOG_INF("LED control accepted: %u", led_on ? 1 : 0);
	led_write_last_err = 0;
	return len;
}

/* ---------- UART command write callback ---------- */

static ssize_t write_uart_cmd(struct bt_conn *conn, const struct bt_gatt_attr *attr,
			      const void *buf, uint16_t len, uint16_t offset,
			      uint8_t flags)
{
	ARG_UNUSED(conn);
	ARG_UNUSED(attr);
	ARG_UNUSED(flags);

	if (offset != 0) {
		return BT_GATT_ERR(BT_ATT_ERR_INVALID_OFFSET);
	}

	if (len == 0 || len > JSS_TEXT_MAX_LEN) {
		return BT_GATT_ERR(BT_ATT_ERR_INVALID_ATTRIBUTE_LEN);
	}

	if (service_handlers.uart_cmd) {
		service_handlers.uart_cmd(buf, len);
	}

	return len;
}

/* ---------- CCC callbacks ---------- */

static void live_ccc_changed(const struct bt_gatt_attr *attr, uint16_t value)
{
	ARG_UNUSED(attr);

	live_notify_enabled = value == BT_GATT_CCC_NOTIFY;
	LOG_INF("Live data notify %s", live_notify_enabled ? "enabled" : "disabled");
}

static void status_ccc_changed(const struct bt_gatt_attr *attr, uint16_t value)
{
	ARG_UNUSED(attr);

	status_notify_enabled = value == BT_GATT_CCC_NOTIFY;
	LOG_INF("Status notify %s", status_notify_enabled ? "enabled" : "disabled");
}

static void uart_rsp_ccc_changed(const struct bt_gatt_attr *attr, uint16_t value)
{
	ARG_UNUSED(attr);

	uart_rsp_notify_enabled = value == BT_GATT_CCC_NOTIFY;
	LOG_INF("UART response notify %s", uart_rsp_notify_enabled ? "enabled" : "disabled");
}

/* ---------- GATT service definition ---------- */

BT_GATT_SERVICE_DEFINE(jss_svc,
	BT_GATT_PRIMARY_SERVICE(&jss_service_uuid),
	/* def1: Live telemetry data (read + notify) */
	BT_GATT_CHARACTERISTIC(&jss_live_data_uuid.uuid,
			       BT_GATT_CHRC_READ | BT_GATT_CHRC_NOTIFY,
			       BT_GATT_PERM_READ,
			       read_live_data, NULL, NULL),
	BT_GATT_CCC(live_ccc_changed, BT_GATT_PERM_READ | BT_GATT_PERM_WRITE),
	/* def2: LED control (write, encrypted) */
	BT_GATT_CHARACTERISTIC(&jss_led_control_uuid.uuid,
			       BT_GATT_CHRC_WRITE,
			       BT_GATT_PERM_WRITE_ENCRYPT,
			       NULL, write_led_control, NULL),
	/* def3: Device status (read + notify) */
	BT_GATT_CHARACTERISTIC(&jss_device_status_uuid.uuid,
			       BT_GATT_CHRC_READ | BT_GATT_CHRC_NOTIFY,
			       BT_GATT_PERM_READ,
			       read_device_status, NULL, NULL),
	BT_GATT_CCC(status_ccc_changed, BT_GATT_PERM_READ | BT_GATT_PERM_WRITE),
	/* def4: Secure info (read, encrypted) */
	BT_GATT_CHARACTERISTIC(&jss_secure_info_uuid.uuid,
			       BT_GATT_CHRC_READ,
			       BT_GATT_PERM_READ_ENCRYPT,
			       read_secure_info, NULL, NULL),
	/* def5: UART command — App writes command text, forwarded to STM32 */
	BT_GATT_CHARACTERISTIC(&jss_uart_cmd_uuid.uuid,
			       BT_GATT_CHRC_WRITE | BT_GATT_CHRC_WRITE_WITHOUT_RESP,
			       BT_GATT_PERM_WRITE,
			       NULL, write_uart_cmd, NULL),
	/* def6: UART response — STM32 reply text, notified to App */
	BT_GATT_CHARACTERISTIC(&jss_uart_rsp_uuid.uuid,
			       BT_GATT_CHRC_READ | BT_GATT_CHRC_NOTIFY,
			       BT_GATT_PERM_READ,
			       read_uart_rsp, NULL, NULL),
	BT_GATT_CCC(uart_rsp_ccc_changed, BT_GATT_PERM_READ | BT_GATT_PERM_WRITE),
);

/* ---------- Public API ---------- */

void jss_service_init(const struct jss_service_handlers *handlers)
{
	if (handlers) {
		service_handlers = *handlers;
	}

	/* Runtime attribute lookup by UUID; no magic index dependency. */
	live_data_attr = bt_gatt_find_by_uuid(jss_svc.attrs, jss_svc.attr_count,
					      &jss_live_data_uuid.uuid);
	device_status_attr = bt_gatt_find_by_uuid(jss_svc.attrs, jss_svc.attr_count,
						  &jss_device_status_uuid.uuid);
	uart_rsp_attr = bt_gatt_find_by_uuid(jss_svc.attrs, jss_svc.attr_count,
					     &jss_uart_rsp_uuid.uuid);

	if (!live_data_attr) {
		LOG_ERR("Failed to find live data attribute by UUID");
	}
	if (!device_status_attr) {
		LOG_ERR("Failed to find device status attribute by UUID");
	}
	if (!uart_rsp_attr) {
		LOG_ERR("Failed to find UART response attribute by UUID");
	}
}

void jss_service_set_live_data(const char *text)
{
	if (!text) {
		return;
	}

	k_mutex_lock(&live_data_mutex, K_FOREVER);
	(void)snprintk(live_data, sizeof(live_data), "%s", text);
	k_mutex_unlock(&live_data_mutex);
}

void jss_service_set_status(const char *text)
{
	if (!text) {
		return;
	}

	k_mutex_lock(&status_mutex, K_FOREVER);
	(void)snprintk(device_status, sizeof(device_status), "%s", text);
	k_mutex_unlock(&status_mutex);
}

void jss_service_set_secure_info(const char *text)
{
	if (!text) {
		return;
	}

	k_mutex_lock(&secure_info_mutex, K_FOREVER);
	(void)snprintk(secure_info, sizeof(secure_info), "%s", text);
	k_mutex_unlock(&secure_info_mutex);
}

void jss_service_set_uart_response(const char *text)
{
	if (!text) {
		return;
	}

	k_mutex_lock(&uart_rsp_mutex, K_FOREVER);
	(void)snprintk(uart_rsp, sizeof(uart_rsp), "%s", text);
	k_mutex_unlock(&uart_rsp_mutex);
}

int jss_service_notify_live_data(struct bt_conn *conn)
{
	int err;
	char notify_data[JSS_TEXT_MAX_LEN];
	size_t notify_len;

	if (!conn) {
		live_notify_last_err = -ENOTCONN;
		return -ENOTCONN;
	}

	if (!live_data_attr) {
		live_notify_last_err = -ENOENT;
		return -ENOENT;
	}

	if (!bt_gatt_is_subscribed(conn, live_data_attr, BT_GATT_CCC_NOTIFY)) {
		live_notify_skips++;
		live_notify_last_err = -EACCES;
		return -EACCES;
	}

	live_notify_attempts++;

	k_mutex_lock(&live_data_mutex, K_FOREVER);
	notify_len = strlen(live_data);
	memcpy(notify_data, live_data, notify_len);
	k_mutex_unlock(&live_data_mutex);

	err = bt_gatt_notify(conn, live_data_attr, notify_data, notify_len);

	live_notify_last_err = err;
	if (err == 0) {
		live_notify_successes++;
	}
	return err;
}

void jss_service_notify_status(void)
{
	char notify_data[JSS_TEXT_MAX_LEN];
	size_t notify_len;

	if (!status_notify_enabled) {
		return;
	}

	if (!device_status_attr) {
		return;
	}

	k_mutex_lock(&status_mutex, K_FOREVER);
	notify_len = strlen(device_status);
	memcpy(notify_data, device_status, notify_len);
	k_mutex_unlock(&status_mutex);

	(void)bt_gatt_notify(NULL, device_status_attr, notify_data, notify_len);
}

int jss_service_notify_uart_response(struct bt_conn *conn)
{
	char notify_data[JSS_TEXT_MAX_LEN];
	size_t notify_len;

	if (!conn || !uart_rsp_attr) {
		return -ENOENT;
	}

	if (!bt_gatt_is_subscribed(conn, uart_rsp_attr, BT_GATT_CCC_NOTIFY)) {
		return -EACCES;
	}

	k_mutex_lock(&uart_rsp_mutex, K_FOREVER);
	notify_len = strlen(uart_rsp);
	memcpy(notify_data, uart_rsp, notify_len);
	k_mutex_unlock(&uart_rsp_mutex);

	return bt_gatt_notify(conn, uart_rsp_attr, notify_data, notify_len);
}

bool jss_service_uart_rsp_is_subscribed(struct bt_conn *conn)
{
	if (!conn || !uart_rsp_attr) {
		return false;
	}

	return bt_gatt_is_subscribed(conn, uart_rsp_attr, BT_GATT_CCC_NOTIFY);
}

bool jss_service_led_on(void)
{
	return led_on;
}

bool jss_service_live_notify_enabled(void)
{
	return live_notify_enabled;
}

bool jss_service_status_notify_enabled(void)
{
	return status_notify_enabled;
}

bool jss_service_live_is_subscribed(struct bt_conn *conn)
{
	if (!conn || !live_data_attr) {
		return false;
	}

	return bt_gatt_is_subscribed(conn, live_data_attr, BT_GATT_CCC_NOTIFY);
}

uint32_t jss_service_live_notify_attempts(void)
{
	return live_notify_attempts;
}

uint32_t jss_service_live_notify_successes(void)
{
	return live_notify_successes;
}

int jss_service_live_notify_last_err(void)
{
	return live_notify_last_err;
}

uint32_t jss_service_live_notify_skips(void)
{
	return live_notify_skips;
}

int jss_service_led_write_last_err(void)
{
	return led_write_last_err;
}
