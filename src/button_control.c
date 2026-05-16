#include "button_control.h"

#include <errno.h>

#include <zephyr/device.h>
#include <zephyr/devicetree.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>
#include <zephyr/sys/atomic.h>

LOG_MODULE_REGISTER(button_control, LOG_LEVEL_INF);

#define PAIR_DEBOUNCE_MS 30
#define PAIR_MODE_PRESS_MS 5000
#define CLEAR_BONDS_PRESS_MS 10000

static const struct gpio_dt_spec pair_button = GPIO_DT_SPEC_GET(DT_ALIAS(sw0), gpios);
static const struct gpio_dt_spec sw3_button = GPIO_DT_SPEC_GET(DT_ALIAS(sw1), gpios);
static const struct gpio_dt_spec sw4_button = GPIO_DT_SPEC_GET(DT_ALIAS(sw2), gpios);

static struct gpio_callback pair_button_cb;
static struct k_work_delayable debounce_work;
static struct k_work_delayable pair_mode_work;
static struct k_work_delayable clear_bonds_work;
static button_control_event_handler_t event_handler;
static void *event_user_data;
static atomic_t stable_pair_pressed;
static atomic_t pair_mode_sent;
static atomic_t clear_bonds_sent;

static int read_pressed(const struct gpio_dt_spec *button)
{
	int value = gpio_pin_get_dt(button);

	if (value < 0) {
		return value;
	}

	return value ? 1 : 0;
}

static void notify_event(enum button_control_event event)
{
	switch (event) {
	case BUTTON_CONTROL_EVENT_PAIR_SHORT_PRESS:
		LOG_INF("PAIR button short press");
		break;
	case BUTTON_CONTROL_EVENT_ENTER_PAIR_MODE:
		LOG_INF("PAIR MODE REQUESTED");
		break;
	case BUTTON_CONTROL_EVENT_CLEAR_BONDS_REQUESTED:
		LOG_INF("CLEAR BONDS REQUESTED");
		break;
	default:
		break;
	}

	if (event_handler) {
		event_handler(event, event_user_data);
	}
}

static void pair_mode_work_handler(struct k_work *work)
{
	ARG_UNUSED(work);

	if (!atomic_get(&stable_pair_pressed)) {
		return;
	}

	if (!atomic_cas(&pair_mode_sent, 0, 1)) {
		return;
	}

	notify_event(BUTTON_CONTROL_EVENT_ENTER_PAIR_MODE);
}

static void clear_bonds_work_handler(struct k_work *work)
{
	ARG_UNUSED(work);

	if (!atomic_get(&stable_pair_pressed)) {
		return;
	}

	if (!atomic_cas(&clear_bonds_sent, 0, 1)) {
		return;
	}

	notify_event(BUTTON_CONTROL_EVENT_CLEAR_BONDS_REQUESTED);
}

static void debounce_work_handler(struct k_work *work)
{
	int pressed;
	bool was_pressed;

	ARG_UNUSED(work);

	pressed = read_pressed(&pair_button);
	if (pressed < 0) {
		LOG_WRN("PAIR button read failed: %d", pressed);
		return;
	}

	was_pressed = atomic_get(&stable_pair_pressed) != 0;
	if ((pressed != 0) == was_pressed) {
		return;
	}

	if (pressed) {
		atomic_set(&stable_pair_pressed, 1);
		atomic_set(&pair_mode_sent, 0);
		atomic_set(&clear_bonds_sent, 0);
		(void)k_work_reschedule(&pair_mode_work, K_MSEC(PAIR_MODE_PRESS_MS));
		(void)k_work_reschedule(&clear_bonds_work, K_MSEC(CLEAR_BONDS_PRESS_MS));
		LOG_DBG("PAIR button pressed");
		return;
	}

	atomic_set(&stable_pair_pressed, 0);
	(void)k_work_cancel_delayable(&pair_mode_work);
	(void)k_work_cancel_delayable(&clear_bonds_work);
	LOG_DBG("PAIR button released");

	if (!atomic_get(&pair_mode_sent) && !atomic_get(&clear_bonds_sent)) {
		notify_event(BUTTON_CONTROL_EVENT_PAIR_SHORT_PRESS);
	}
}

static void pair_button_isr(const struct device *dev, struct gpio_callback *cb, uint32_t pins)
{
	ARG_UNUSED(dev);
	ARG_UNUSED(cb);
	ARG_UNUSED(pins);

	(void)k_work_reschedule(&debounce_work, K_MSEC(PAIR_DEBOUNCE_MS));
}

static int configure_input(const struct gpio_dt_spec *button)
{
	if (!gpio_is_ready_dt(button)) {
		return -ENODEV;
	}

	return gpio_pin_configure_dt(button, GPIO_INPUT);
}

int button_control_init(button_control_event_handler_t handler, void *user_data)
{
	int err;

	event_handler = handler;
	event_user_data = user_data;

	err = configure_input(&pair_button);
	if (err) {
		return err;
	}

	err = configure_input(&sw3_button);
	if (err) {
		return err;
	}

	err = configure_input(&sw4_button);
	if (err) {
		return err;
	}

	k_work_init_delayable(&debounce_work, debounce_work_handler);
	k_work_init_delayable(&pair_mode_work, pair_mode_work_handler);
	k_work_init_delayable(&clear_bonds_work, clear_bonds_work_handler);

	atomic_set(&stable_pair_pressed, read_pressed(&pair_button) > 0 ? 1 : 0);
	atomic_set(&pair_mode_sent, 0);
	atomic_set(&clear_bonds_sent, 0);

	gpio_init_callback(&pair_button_cb, pair_button_isr, BIT(pair_button.pin));
	err = gpio_add_callback(pair_button.port, &pair_button_cb);
	if (err) {
		return err;
	}

	err = gpio_pin_interrupt_configure_dt(&pair_button, GPIO_INT_EDGE_BOTH);
	if (err) {
		(void)gpio_remove_callback(pair_button.port, &pair_button_cb);
		return err;
	}

	LOG_INF("PAIR button ready");
	return 0;
}

int button_control_pair_pressed(void)
{
	return read_pressed(&pair_button);
}

int button_control_sw3_pressed(void)
{
	return read_pressed(&sw3_button);
}

int button_control_sw4_pressed(void)
{
	return read_pressed(&sw4_button);
}
