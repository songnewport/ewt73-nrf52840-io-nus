#ifndef JSS_SERVICE_H_
#define JSS_SERVICE_H_

#include <stdbool.h>

#include <zephyr/bluetooth/conn.h>

#define JSS_DEVICE_NAME "Justin_Shunt_Test"

#define JSS_SERVICE_UUID_STR "12345678-1234-5678-1234-56789abcdef0"
#define JSS_LIVE_DATA_UUID_STR "12345678-1234-5678-1234-56789abcdef1"
#define JSS_LED_CONTROL_UUID_STR "12345678-1234-5678-1234-56789abcdef2"
#define JSS_DEVICE_STATUS_UUID_STR "12345678-1234-5678-1234-56789abcdef3"
#define JSS_SECURE_INFO_UUID_STR "12345678-1234-5678-1234-56789abcdef4"
#define JSS_UART_CMD_UUID_STR "12345678-1234-5678-1234-56789abcdef5"
#define JSS_UART_RSP_UUID_STR "12345678-1234-5678-1234-56789abcdef6"

#define JSS_FW_VERSION "0.3.0-UART-BRIDGE"
#define JSS_DEVICE_SERIAL "DEMO-170526"

#define JSS_SERVICE_UUID_VAL BT_UUID_128_ENCODE(0x12345678, 0x1234, 0x5678, 0x1234, 0x56789abcdef0)

typedef void (*jss_led_write_handler_t)(bool led_on);
typedef void (*jss_uart_cmd_handler_t)(const uint8_t *data, uint16_t len);

struct jss_service_handlers {
	jss_led_write_handler_t led_write;
	jss_uart_cmd_handler_t uart_cmd;
};

void jss_service_init(const struct jss_service_handlers *handlers);
void jss_service_set_live_data(const char *text);
void jss_service_set_status(const char *text);
void jss_service_set_secure_info(const char *text);
int jss_service_notify_live_data(struct bt_conn *conn);
void jss_service_notify_status(void);

/* UART response: push STM32 reply text, notify subscribed peers */
void jss_service_set_uart_response(const char *text);
int jss_service_notify_uart_response(struct bt_conn *conn);
bool jss_service_uart_rsp_is_subscribed(struct bt_conn *conn);

bool jss_service_led_on(void);
bool jss_service_live_notify_enabled(void);
bool jss_service_status_notify_enabled(void);
bool jss_service_live_is_subscribed(struct bt_conn *conn);
uint32_t jss_service_live_notify_attempts(void);
uint32_t jss_service_live_notify_successes(void);
int jss_service_live_notify_last_err(void);
uint32_t jss_service_live_notify_skips(void);
int jss_service_led_write_last_err(void);

#endif /* JSS_SERVICE_H_ */
