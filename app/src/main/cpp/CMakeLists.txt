cmake_minimum_required(VERSION 3.22.1)
project(dds_bridge)

# Sources DDS dans app/dds-develop/dds-develop/library/src/
set(DDS_SRC ${CMAKE_SOURCE_DIR}/../../../dds-develop/dds-develop/library/src)

add_library(dds_jni SHARED
    dds_jni.cpp
    ${DDS_SRC}/ab_search.cpp
    ${DDS_SRC}/ab_stats.cpp
    ${DDS_SRC}/calc_dd_table.cpp
    ${DDS_SRC}/calc_par.cpp
    ${DDS_SRC}/calc_tables.cpp
    ${DDS_SRC}/dds.cpp
    ${DDS_SRC}/dealer_par.cpp
    ${DDS_SRC}/dump.cpp
    ${DDS_SRC}/init.cpp
    ${DDS_SRC}/later_tricks.cpp
    ${DDS_SRC}/par.cpp
    ${DDS_SRC}/pbn.cpp
    ${DDS_SRC}/play_analyser.cpp
    ${DDS_SRC}/quick_tricks.cpp
    ${DDS_SRC}/solve_board.cpp
    ${DDS_SRC}/solver_context_adapter.cpp
    ${DDS_SRC}/solver_if.cpp
    ${DDS_SRC}/heuristic_sorting/heuristic_sorting.cpp
    ${DDS_SRC}/lookup_tables/lookup_tables.cpp
    ${DDS_SRC}/moves/moves.cpp
    ${DDS_SRC}/solver_context/solver_context.cpp
    ${DDS_SRC}/system/memory.cpp
    ${DDS_SRC}/system/scheduler.cpp
    ${DDS_SRC}/system/system.cpp
    ${DDS_SRC}/system/thread_data.cpp
    ${DDS_SRC}/system/thread_mgr.cpp
    ${DDS_SRC}/system/time_stat.cpp
    ${DDS_SRC}/system/time_stat_list.cpp
    ${DDS_SRC}/system/timer.cpp
    ${DDS_SRC}/system/timer_group.cpp
    ${DDS_SRC}/system/timer_list.cpp
    ${DDS_SRC}/trans_table/trans_table_l.cpp
    ${DDS_SRC}/trans_table/trans_table_s.cpp
    ${DDS_SRC}/utility/constants.cpp
)

# Le répertoire library/src EST la racine des includes (api/dll.h, utility/constants.h, etc.)
target_include_directories(dds_jni PRIVATE ${DDS_SRC})

target_compile_definitions(dds_jni PRIVATE
    USES_CONSTRUCTOR   # active le __attribute__((constructor)) libInit → SetMaxThreads(0) au chargement
)

target_compile_options(dds_jni PRIVATE
    -std=c++17
    -O2
    -fexceptions
    -Wno-unused-parameter
    -Wno-unused-variable
    -Wno-sign-compare
)

find_library(log-lib log)
target_link_libraries(dds_jni ${log-lib})
