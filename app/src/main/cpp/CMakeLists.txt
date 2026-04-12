cmake_minimum_required(VERSION 3.22.1)
project(sigsys_handler C)

add_library(sigsys_handler SHARED sigsys_handler.c)

find_library(log-lib log)
target_link_libraries(sigsys_handler ${log-lib})

target_compile_options(sigsys_handler PRIVATE -Wall -Wextra -O2)
