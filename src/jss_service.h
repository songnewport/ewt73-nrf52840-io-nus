#ifndef JSS_SERVICE_H_
#define JSS_SERVICE_H_

#include <stdbool.h>

#include <zephyr/bluetooth/conn.h>

#define JSS_DEVICE_NAME "Justin_Shunt_Test"

#define JSS_SERVICE_UUID_STR "12345678-1234-5678-1234-56789abcdef0"
#define JSS_LIVE_DATA_UUID_STR "12345678-1234-5678-1234-56789abcdef1"
#define JSS_LED_CONTROL_UUID_STR "12345678-1234-5678-1234-56789abcdef2"
#define JSS_DEVICE_STATUS_UUID_STR "12345678-1234-5678-1234-56789abcdef3"

#define JSS_SERVICE_UUID_VAL BT_UUID_128_ENCODE(0x12345678, 0x1234, 0x5678, 0x1234, 0x56789abcdef0)

typedef void (*jss_led_write_handler_t)(bool led_on);
typedef bool (*jss_conn_is_bonded_handler_t)(struct bt_conn *conn);
typedef void (*jss_live_notify_state_handler_t)(bool enabled);
typedef void (*jss_live_notify_sent_handler_t)(void);

struct jss_service_handlers {
	jss_led_write_handler_t led_write;
	jss_conn_is_bonded_handler_t conn_is_bonded;
	jss_live_notify_state_handler_t live_notify_state;
	jss_live_notify_sent_handler_t live_notify_sent;
};

void jss_service_init(const struct jss_service_handlers *handlers);
void jss_service_set_live_data(const char *text);
void jss_service_set_status(const char *text);
int jss_service_notify_live_data(struct bt_conn *conn);
void jss_service_notify_status(void);
bool jss_service_led_on(void);
bool jss_service_live_notify_enabled(void);
bool jss_service_live_is_subscribed(struct bt_conn *conn);
uint32_t jss_service_live_notify_attempts(void);
uint32_t jss_service_live_notify_successes(void);
int jss_service_live_notify_last_err(void);
uint32_t jss_service_live_notify_skips(void);

#endif /* JSS_SERVICE_H_ */
