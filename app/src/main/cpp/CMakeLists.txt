cmake_minimum_required(VERSION 3.22.1)
project(umavpn_openvpn LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../..")
set(VCPKG_ROOT "${REPO_ROOT}/tools/vcpkg")
set(OPENVPN3_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/vendor/openvpn3")

if(NOT EXISTS "${OPENVPN3_ROOT}/client/ovpncli.cpp")
    message(FATAL_ERROR "OpenVPN3 source not found at ${OPENVPN3_ROOT}")
endif()

if(NOT EXISTS "${VCPKG_ROOT}/installed")
    message(FATAL_ERROR "vcpkg installed tree not found at ${VCPKG_ROOT}/installed")
endif()

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(VCPKG_ANDROID_TRIPLET "arm64-android")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(VCPKG_ANDROID_TRIPLET "arm-neon-android")
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(VCPKG_ANDROID_TRIPLET "x64-android")
else()
    message(FATAL_ERROR "Unsupported ANDROID_ABI for OpenVPN native integration: ${ANDROID_ABI}")
endif()

list(APPEND CMAKE_PREFIX_PATH "${VCPKG_ROOT}/installed/${VCPKG_ANDROID_TRIPLET}")

set(asio_DIR "${VCPKG_ROOT}/installed/${VCPKG_ANDROID_TRIPLET}/share/asio")
set(fmt_DIR "${VCPKG_ROOT}/installed/${VCPKG_ANDROID_TRIPLET}/share/fmt")
set(lz4_DIR "${VCPKG_ROOT}/installed/${VCPKG_ANDROID_TRIPLET}/share/lz4")
set(xxHash_DIR "${VCPKG_ROOT}/installed/${VCPKG_ANDROID_TRIPLET}/share/xxhash")
set(MbedTLS_DIR "${VCPKG_ROOT}/installed/${VCPKG_ANDROID_TRIPLET}/share/mbedtls")

find_package(asio CONFIG REQUIRED)
find_package(fmt CONFIG REQUIRED)
find_package(lz4 CONFIG REQUIRED)
find_package(xxHash CONFIG REQUIRED)
find_package(MbedTLS CONFIG REQUIRED)

add_library(
    umavpn_openvpn
    SHARED
    jni/OpenVpnJni.cpp
    vendor/openvpn3/client/ovpncli.cpp
    vendor/openvpn3/openvpn/crypto/data_epoch.cpp
)

find_library(log-lib log)

target_include_directories(
    umavpn_openvpn
    PRIVATE
    "${OPENVPN3_ROOT}"
)

target_compile_definitions(
    umavpn_openvpn
    PRIVATE
    USE_MBEDTLS
    ASIO_STANDALONE
    USE_ASIO
)

target_link_libraries(
    umavpn_openvpn
    asio::asio
    fmt::fmt
    lz4::lz4
    xxHash::xxhash
    MbedTLS::mbedtls
    MbedTLS::mbedx509
    MbedTLS::mbedcrypto
    ${log-lib}
)

# ===========================================================================
# OpenVPN2 engine (ics-openvpn based) — optional, guarded by submodule presence.
# Enable with: -PenableNativeOpenVpn2=true in Gradle which passes -DENABLE_OPVN2=ON.
#
# Source layout (after `git submodule update --init --recursive`):
#   vendor/ics-openvpn/main/src/main/cpp/
#     openvpn/src/compat/    — compat shims
#     openvpn/src/openvpn/   — core C sources (openvpn_main() is in openvpn.c)
#     openvpn/include/       — public headers
#     openvpn-config/        — Android-specific config.h
#     lzo/                   — bundled minilzo (HAVE_LZO required by config.h)
# ===========================================================================
set(ICS_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/vendor/ics-openvpn/main/src/main/cpp")
set(ICS_OPENVPN "${ICS_ROOT}/openvpn")
set(VCPKG_INC "${VCPKG_ROOT}/installed/${VCPKG_ANDROID_TRIPLET}/include")

if(ENABLE_OPVN2 AND EXISTS "${ICS_OPENVPN}/src/openvpn/openvpn.c")
    message(STATUS "umavpn_openvpn2: enabled (ics-openvpn found at ${ICS_ROOT})")

    # ---- LZO (bundled in ics-openvpn, required by openvpn-config/config.h) ----
    file(GLOB LZO_SRCS "${ICS_ROOT}/lzo/src/*.c")
    add_library(uma_lzo STATIC ${LZO_SRCS})
    target_include_directories(uma_lzo PUBLIC "${ICS_ROOT}/lzo/include")
    target_compile_options(uma_lzo PRIVATE -O2)

    # ---- OpenVPN2 C source list (mirrors ics-openvpn CMakeLists.txt exactly) ----
    set(OPENVPN2_SRCS
        ${ICS_OPENVPN}/src/compat/compat-basename.c
        ${ICS_OPENVPN}/src/compat/compat-daemon.c
        ${ICS_OPENVPN}/src/compat/compat-dirname.c
        ${ICS_OPENVPN}/src/compat/compat-gettimeofday.c
        ${ICS_OPENVPN}/src/openvpn/argv.c
        ${ICS_OPENVPN}/src/openvpn/auth_token.c
        ${ICS_OPENVPN}/src/openvpn/base64.c
        ${ICS_OPENVPN}/src/openvpn/buffer.c
        ${ICS_OPENVPN}/src/openvpn/clinat.c
        ${ICS_OPENVPN}/src/openvpn/console.c
        ${ICS_OPENVPN}/src/openvpn/console_builtin.c
        ${ICS_OPENVPN}/src/openvpn/crypto.c
        ${ICS_OPENVPN}/src/openvpn/crypto_epoch.c
        ${ICS_OPENVPN}/src/openvpn/crypto_openssl.c
        ${ICS_OPENVPN}/src/openvpn/crypto_mbedtls.c
        ${ICS_OPENVPN}/src/openvpn/crypto_mbedtls_legacy.c
        ${ICS_OPENVPN}/src/openvpn/cryptoapi.c
        ${ICS_OPENVPN}/src/openvpn/dhcp.c
        ${ICS_OPENVPN}/src/openvpn/dns.c
        ${ICS_OPENVPN}/src/openvpn/error.c
        ${ICS_OPENVPN}/src/openvpn/event.c
        ${ICS_OPENVPN}/src/openvpn/env_set.c
        ${ICS_OPENVPN}/src/openvpn/fdmisc.c
        ${ICS_OPENVPN}/src/openvpn/forward.c
        ${ICS_OPENVPN}/src/openvpn/fragment.c
        ${ICS_OPENVPN}/src/openvpn/gremlin.c
        ${ICS_OPENVPN}/src/openvpn/helper.c
        ${ICS_OPENVPN}/src/openvpn/httpdigest.c
        ${ICS_OPENVPN}/src/openvpn/init.c
        ${ICS_OPENVPN}/src/openvpn/interval.c
        ${ICS_OPENVPN}/src/openvpn/list.c
        ${ICS_OPENVPN}/src/openvpn/lladdr.c
        ${ICS_OPENVPN}/src/openvpn/lzo.c
        ${ICS_OPENVPN}/src/openvpn/manage.c
        ${ICS_OPENVPN}/src/openvpn/mbuf.c
        ${ICS_OPENVPN}/src/openvpn/misc.c
        ${ICS_OPENVPN}/src/openvpn/mroute.c
        ${ICS_OPENVPN}/src/openvpn/mss.c
        ${ICS_OPENVPN}/src/openvpn/mtcp.c
        ${ICS_OPENVPN}/src/openvpn/mtu.c
        ${ICS_OPENVPN}/src/openvpn/mudp.c
        ${ICS_OPENVPN}/src/openvpn/multi.c
        ${ICS_OPENVPN}/src/openvpn/multi_io.c
        ${ICS_OPENVPN}/src/openvpn/ntlm.c
        ${ICS_OPENVPN}/src/openvpn/occ.c
        # openvpn.c is intentionally excluded: openvpn_main() is static there.
        # jni/openvpn2_entry.c #includes openvpn.c and exposes a public openvpn_main().
        ${ICS_OPENVPN}/src/openvpn/options.c
        ${ICS_OPENVPN}/src/openvpn/options_parse.c
        ${ICS_OPENVPN}/src/openvpn/options_util.c
        ${ICS_OPENVPN}/src/openvpn/otime.c
        ${ICS_OPENVPN}/src/openvpn/packet_id.c
        ${ICS_OPENVPN}/src/openvpn/ping.c
        ${ICS_OPENVPN}/src/openvpn/pkcs11.c
        ${ICS_OPENVPN}/src/openvpn/pkcs11_openssl.c
        ${ICS_OPENVPN}/src/openvpn/pkcs11_mbedtls.c
        ${ICS_OPENVPN}/src/openvpn/platform.c
        ${ICS_OPENVPN}/src/openvpn/plugin.c
        ${ICS_OPENVPN}/src/openvpn/pool.c
        ${ICS_OPENVPN}/src/openvpn/proto.c
        ${ICS_OPENVPN}/src/openvpn/proxy.c
        ${ICS_OPENVPN}/src/openvpn/ps.c
        ${ICS_OPENVPN}/src/openvpn/push.c
        ${ICS_OPENVPN}/src/openvpn/push_util.c
        ${ICS_OPENVPN}/src/openvpn/reflect_filter.c
        ${ICS_OPENVPN}/src/openvpn/reliable.c
        ${ICS_OPENVPN}/src/openvpn/route.c
        ${ICS_OPENVPN}/src/openvpn/run_command.c
        ${ICS_OPENVPN}/src/openvpn/schedule.c
        ${ICS_OPENVPN}/src/openvpn/session_id.c
        ${ICS_OPENVPN}/src/openvpn/shaper.c
        ${ICS_OPENVPN}/src/openvpn/sig.c
        ${ICS_OPENVPN}/src/openvpn/socket.c
        ${ICS_OPENVPN}/src/openvpn/socket_util.c
        ${ICS_OPENVPN}/src/openvpn/socks.c
        ${ICS_OPENVPN}/src/openvpn/ssl.c
        ${ICS_OPENVPN}/src/openvpn/ssl_openssl.c
        ${ICS_OPENVPN}/src/openvpn/ssl_mbedtls.c
        ${ICS_OPENVPN}/src/openvpn/ssl_ncp.c
        ${ICS_OPENVPN}/src/openvpn/ssl_pkt.c
        ${ICS_OPENVPN}/src/openvpn/ssl_util.c
        ${ICS_OPENVPN}/src/openvpn/ssl_verify.c
        ${ICS_OPENVPN}/src/openvpn/ssl_verify_openssl.c
        ${ICS_OPENVPN}/src/openvpn/ssl_verify_mbedtls.c
        ${ICS_OPENVPN}/src/openvpn/status.c
        ${ICS_OPENVPN}/src/openvpn/tls_crypt.c
        ${ICS_OPENVPN}/src/openvpn/tun.c
        ${ICS_OPENVPN}/src/openvpn/tun_afunix.c
        ${ICS_OPENVPN}/src/openvpn/vlan.c
        ${ICS_OPENVPN}/src/openvpn/xkey_helper.c
        ${ICS_OPENVPN}/src/openvpn/xkey_provider.c
        ${ICS_OPENVPN}/src/openvpn/comp-lz4.c
        ${ICS_OPENVPN}/src/openvpn/comp.c
        ${ICS_OPENVPN}/src/openvpn/compstub.c
    )

    # ---- Build umavpn_openvpn2 shared library --------------------------------
    add_library(umavpn_openvpn2 SHARED
        jni/OpenVpn2Jni.cpp
        jni/openvpn2_entry.c   # includes openvpn.c and exposes public openvpn_main()
        ${OPENVPN2_SRCS}
    )

    # OpenVPN2 core is C (config.h uses C99/C11 features).
    # OpenVpn2Jni.cpp is C++20. Apply per-file language flags.
    set_source_files_properties(jni/OpenVpn2Jni.cpp
        PROPERTIES COMPILE_FLAGS "-std=c++20"
    )
    set_source_files_properties(jni/openvpn2_entry.c
        PROPERTIES COMPILE_FLAGS "-std=c11"
    )
    foreach(src ${OPENVPN2_SRCS})
        set_source_files_properties("${src}" PROPERTIES COMPILE_FLAGS "-std=c11")
    endforeach()

    target_compile_options(umavpn_openvpn2 PRIVATE
        -O2
        -Wno-deprecated-declarations
        -Wno-unused-function
        -Wno-unused-variable
    )

    target_compile_definitions(umavpn_openvpn2 PRIVATE
        HAVE_CONFIG_H                   # uses openvpn-config/config.h
        # TARGET_ANDROID is already defined in openvpn-config/config.h — do not redefine
        ENABLE_MANAGEMENT               # management interface (required for our JNI bridge)
        ENABLE_CRYPTO_MBEDTLS=1         # use mbedtls from vcpkg (not OpenSSL)
        HAVE_BASENAME                   # suppress compat.h redeclaration; NDK string.h has its own
        CONFIGURE_GIT_REVISION=\"embedded\"
        CONFIGURE_GIT_FLAGS=\"\"
        TARGET_ABI=\"${ANDROID_ABI}\"
        DEFAULT_DNS_UPDOWN=\"/not/supported/on/android\"
    )

    target_include_directories(umavpn_openvpn2 PRIVATE
        "${ICS_ROOT}/openvpn-config"     # config.h (Android-specific)
        "${ICS_ROOT}/openvpn/src/compat" # compat shims
        "${ICS_ROOT}/openvpn/include"    # OpenVPN public headers
        "${ICS_ROOT}/openvpn"            # internal include base
        "${ICS_ROOT}/lzo/include"        # LZO headers (required by config.h)
        "${VCPKG_INC}"                   # MbedTLS headers
    )

    target_link_libraries(umavpn_openvpn2
        uma_lzo                          # bundled LZO from ics-openvpn
        lz4::lz4                         # LZ4 from vcpkg
        MbedTLS::mbedtls
        MbedTLS::mbedcrypto
        MbedTLS::mbedx509
        ${log-lib}
    )

    # Redirect openvpn_exit() → __wrap_openvpn_exit() so the process is not
    # killed by exit() when OpenVPN shuts down (e.g. on user disconnect).
    target_link_options(umavpn_openvpn2 PRIVATE -Wl,--wrap=openvpn_exit)

else()
    if(ENABLE_OPVN2)
        message(WARNING "umavpn_openvpn2: SKIPPED — ics-openvpn submodule sources not found at ${ICS_OPENVPN}. Run: git submodule update --init --recursive")
    else()
        message(STATUS "umavpn_openvpn2: SKIPPED (ENABLE_OPVN2 not set). Pass -PenableNativeOpenVpn2=true to enable.")
    endif()
endif()
