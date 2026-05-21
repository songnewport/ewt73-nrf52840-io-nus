cmake_minimum_required(VERSION 3.20.0)

find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})
project(jss_review_uart_bridge)

target_sources(app PRIVATE
	src/main.c
	src/button_control.c
	src/jss_service.c
)
