#ifndef BUTTON_CONTROL_H_
#define BUTTON_CONTROL_H_

#include <stdint.h>

enum button_control_event {
	BUTTON_CONTROL_EVENT_PAIR_SHORT_PRESS,
	BUTTON_CONTROL_EVENT_ENTER_PAIR_MODE,
	BUTTON_CONTROL_EVENT_CLEAR_BONDS_REQUESTED,
};

typedef void (*button_control_event_handler_t)(enum button_control_event event, void *user_data);

int button_control_init(button_control_event_handler_t handler, void *user_data);
int button_control_pair_pressed(void);
int button_control_sw3_pressed(void);
int button_control_sw4_pressed(void);

#endif /* BUTTON_CONTROL_H_ */
