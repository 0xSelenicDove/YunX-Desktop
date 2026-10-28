cmake_minimum_required(VERSION 3.21)
project(YunXHelper LANGUAGES C CXX)
set(CMAKE_MODULE_PATH "${CEF_ROOT}/cmake")
find_package(CEF REQUIRED)
add_subdirectory("${CEF_LIBCEF_DLL_WRAPPER_PATH}" libcef_dll_wrapper)
set(EXECUTABLE_NAME "jcef Helper")
set(PRODUCT_NAME "jcef Helper")
set(BUNDLE_ID_SUFFIX "")
configure_file("${JCEF_SOURCE}/native/resources/jcef-Info.plist" "${CMAKE_BINARY_DIR}/helper.plist")
add_executable(yunx_helper MACOSX_BUNDLE
    "${JCEF_SOURCE}/native/jcef_helper.cpp"
    "${JCEF_SOURCE}/native/util_posix.cpp")
SET_EXECUTABLE_TARGET_PROPERTIES(yunx_helper)
target_link_libraries(yunx_helper libcef_dll_wrapper ${CEF_STANDARD_LIBS})
set_target_properties(yunx_helper PROPERTIES
    OUTPUT_NAME "jcef Helper"
    MACOSX_BUNDLE_INFO_PLIST "${CMAKE_BINARY_DIR}/helper.plist")
