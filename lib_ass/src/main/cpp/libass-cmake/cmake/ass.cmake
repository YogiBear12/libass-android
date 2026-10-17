cmake_minimum_required(VERSION 3.10)
ExternalProject_Add(ep_ass
    DEPENDS ep_fontconfig ep_harfbuzz ep_freetype ep_fribidi ep_unibreak
    SOURCE_DIR ${CMAKE_CURRENT_SOURCE_DIR}
    SOURCE_SUBDIR "src/ass"
    INSTALL_DIR ${CMAKE_BINARY_DIR}
    CONFIGURE_COMMAND
        ${CMAKE_COMMAND} -E env ${PLATFORM_CONFIGURE_ENV}
        <SOURCE_DIR>/<SOURCE_SUBDIR>/configure
        ${PLATFORM_BUILD_AND_HOST} ${CONFIGURE_VERBOSE_ARG}
        --prefix=<INSTALL_DIR>
        --enable-static
        --disable-shared
        --enable-fontconfig
        --enable-libunibreak
        --srcdir=<SOURCE_DIR>/<SOURCE_SUBDIR>
    # Ninja restats the declared byproduct after the build step, so the install has to happen there
    # or an unchanged stale archive prunes it.
    BUILD_COMMAND make install
    BUILD_ALWAYS TRUE
    BUILD_BYPRODUCTS ${CMAKE_BINARY_DIR}/lib/libass.a
    INSTALL_COMMAND ""
)
add_library(ass_archive STATIC IMPORTED GLOBAL)
set_target_properties(ass_archive PROPERTIES IMPORTED_LOCATION ${CMAKE_BINARY_DIR}/lib/libass.a)
add_dependencies(ass_archive ep_ass)
add_dependencies(ep_ass ep_freetype ep_harfbuzz ep_fribidi ep_unibreak)
include_directories({CMAKE_BINARY_DIR}/include)
link_directories({CMAKE_BINARY_DIR}/lib)