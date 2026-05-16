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

#define JSS_TEXT_MAX_LEN 160

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

static struct jss_service_handlers service_handlers;
static char live_data[JSS_TEXT_MAX_LEN] = "V=0.000,I=0,T=0,SOC=0";
static char device_status[JSS_TEXT_MAX_LEN] = "PAIR_MODE=0,BONDED_COUNT=0,LED=0,FW=0.1.0";
static bool led_on;
static bool live_notify_enabled;
static bool status_notify_enabled;
static const struct bt_gatt_attr *live_data_attr;
static const struct bt_gatt_attr *device_status_attr;
static uint32_t live_notify_attempts;
static uint32_t live_notify_successes;
static uint32_t live_notify_skips;
static int live_notify_last_err;

static ssize_t read_text(struct bt_conn *conn, const struct bt_gatt_attr *attr,
			 void *buf, uint16_t len, uint16_t offset)
{
	const char *value = attr->user_data;

	return bt_gatt_attr_read(conn, attr, buf, len, offset, value, strlen(value));
}

static bool conn_is_bonded(struct bt_conn *conn)
{
	if (!service_handlers.conn_is_bonded) {
		return false;
	}

	return service_handlers.conn_is_bonded(conn);
}

static ssize_t write_led_control(struct bt_conn *conn, const struct bt_gatt_attr *attr,
				 const void *buf, uint16_t len, uint16_t offset,
				 uint8_t flags)
{
	const uint8_t *value = buf;

	ARG_UNUSED(attr);
	ARG_UNUSED(flags);

	if (offset != 0) {
		return BT_GATT_ERR(BT_ATT_ERR_INVALID_OFFSET);
	}

	if (len != 1) {
		return BT_GATT_ERR(BT_ATT_ERR_INVALID_ATTRIBUTE_LEN);
	}

	if (!conn_is_bonded(conn)) {
		LOG_WRN("Rejecting encrypted LED write from unbonded peer");
		return BT_GATT_ERR(BT_ATT_ERR_AUTHORIZATION);
	}

	if (value[0] != 0x00 && value[0] != 0x01) {
		return BT_GATT_ERR(BT_ATT_ERR_VALUE_NOT_ALLOWED);
	}

	led_on = value[0] == 0x01;
	if (service_handlers.led_write) {
		service_handlers.led_write(led_on);
	}

	LOG_INF("LED control accepted: %u", led_on ? 1 : 0);
	return len;
}

static void live_ccc_changed(const struct bt_gatt_attr *attr, uint16_t value)
{
	ARG_UNUSED(attr);

	live_notify_enabled = value == BT_GATT_CCC_NOTIFY;
	LOG_INF("Live data notify %s", live_notify_enabled ? "enabled" : "disabled");
	if (service_handlers.live_notify_state) {
		service_handlers.live_notify_state(live_notify_enabled);
	}
}

static void status_ccc_changed(const struct bt_gatt_attr *attr, uint16_t value)
{
	ARG_UNUSED(attr);

	status_notify_enabled = value == BT_GATT_CCC_NOTIFY;
	LOG_INF("Status notify %s", status_notify_enabled ? "enabled" : "disabled");
}

BT_GATT_SERVICE_DEFINE(jss_svc,
	BT_GATT_PRIMARY_SERVICE(&jss_service_uuid),
	BT_GATT_CHARACTERISTIC(&jss_live_data_uuid.uuid,
			       BT_GATT_CHRC_READ | BT_GATT_CHRC_NOTIFY,
			       BT_GATT_PERM_READ,
			       read_text, NULL, live_data),
	BT_GATT_CCC(live_ccc_changed, BT_GATT_PERM_READ | BT_GATT_PERM_WRITE),
	BT_GATT_CHARACTERISTIC(&jss_led_control_uuid.uuid,
			       BT_GATT_CHRC_WRITE,
			       BT_GATT_PERM_WRITE_ENCRYPT,
			       NULL, write_led_control, NULL),
	BT_GATT_CHARACTERISTIC(&jss_device_status_uuid.uuid,
			       BT_GATT_CHRC_READ | BT_GATT_CHRC_NOTIFY,
			       BT_GATT_PERM_READ,
			       read_text, NULL, device_status),
	BT_GATT_CCC(status_ccc_changed, BT_GATT_PERM_READ | BT_GATT_PERM_WRITE),
);

void jss_service_init(const struct jss_service_handlers *handlers)
{
	if (handlers) {
		service_handlers = *handlers;
	}

	live_data_attr = &jss_svc.attrs[2];
	device_status_attr = &jss_svc.attrs[7];
}

void jss_service_set_live_data(const char *text)
{
	if (!text) {
		return;
	}

	(void)snprintk(live_data, sizeof(live_data), "%s", text);
}

void jss_service_set_status(const char *text)
{
	if (!text) {
		return;
	}

	(void)snprintk(device_status, sizeof(device_status), "%s", text);
}

static void live_notify_complete(struct bt_conn *conn, void *user_data)
{
	ARG_UNUSED(conn);
	ARG_UNUSED(user_data);

	live_notify_successes++;
	if (service_handlers.live_notify_sent) {
		service_handlers.live_notify_sent();
	}
}

int jss_service_notify_live_data(struct bt_conn *conn)
{
	int err;
	struct bt_gatt_notify_params params;

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

	memset(&params, 0, sizeof(params));
	params.attr = live_data_attr;
	params.data = live_data;
	params.len = strlen(live_data);
	params.func = live_notify_complete;

	live_notify_attempts++;
	err = bt_gatt_notify_cb(conn, &params);
	live_notify_last_err = err;
	return err;
}

void jss_service_notify_status(void)
{
	if (!status_notify_enabled) {
		return;
	}

	if (!device_status_attr) {
		return;
	}

	(void)bt_gatt_notify(NULL, device_status_attr, device_status, strlen(device_status));
}

bool jss_service_led_on(void)
{
	return led_on;
}

bool jss_service_live_notify_enabled(void)
{
	return live_notify_enabled;
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
