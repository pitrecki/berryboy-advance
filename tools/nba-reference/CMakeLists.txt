cmake_minimum_required(VERSION 3.24)
project(BerryBoyIndependentReferenceProbes LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(NBA_REFERENCE_COMMIT 55b5cf0ae3d929582ac5bfd486558173502b8354)
if(NOT IS_DIRECTORY "${NBA_SOURCE_DIR}/src/nba")
  message(FATAL_ERROR "Set NBA_SOURCE_DIR to an unmodified NanoBoyAdvance checkout")
endif()
execute_process(COMMAND git rev-parse HEAD WORKING_DIRECTORY "${NBA_SOURCE_DIR}"
  OUTPUT_VARIABLE nba_commit OUTPUT_STRIP_TRAILING_WHITESPACE COMMAND_ERROR_IS_FATAL ANY)
if(NOT nba_commit STREQUAL NBA_REFERENCE_COMMIT)
  message(FATAL_ERROR "Reference requires NanoBoyAdvance ${NBA_REFERENCE_COMMIT}")
endif()
execute_process(COMMAND git diff --exit-code HEAD -- src/nba thirdparty
  WORKING_DIRECTORY "${NBA_SOURCE_DIR}" OUTPUT_QUIET RESULT_VARIABLE nba_dirty)
if(NOT nba_dirty EQUAL 0)
  message(FATAL_ERROR "Reference source has tracked modifications")
endif()
# Select the original core and its bundled dependencies, without desktop/GL/SDL.
add_subdirectory("${NBA_SOURCE_DIR}/thirdparty/fmt-12.1.0" fmt)
add_subdirectory("${NBA_SOURCE_DIR}/thirdparty/atom-1.0.1" atom)
add_subdirectory("${NBA_SOURCE_DIR}/src/nba" nba)
foreach(probe IN ITEMS nba-dma-probe nba-color-probe)
  add_executable(${probe} ../${probe}.cc)
  target_include_directories(${probe} SYSTEM PRIVATE
    "${NBA_SOURCE_DIR}/src/nba/src" "${NBA_SOURCE_DIR}/src/nba/include"
    "${NBA_SOURCE_DIR}/thirdparty/atom-1.0.1/common/include"
    "${NBA_SOURCE_DIR}/thirdparty/atom-1.0.1/logger/include")
  target_link_libraries(${probe} PRIVATE nba)
  target_compile_options(${probe} PRIVATE -Wall -Wextra -Werror)
endforeach()
